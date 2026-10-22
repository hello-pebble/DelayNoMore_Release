package com.delaynomore.backend.domain.challenge;

import com.delaynomore.backend.domain.challenge.dto.ChallengeResponse;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.jdbc.AbstractPostgresIntegrationTest;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.time.KstDates;
import com.delaynomore.backend.support.PlanFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "기간 내 완주 기록"(v0.34.0)의 동시성·잠금 규약을 실제 PostgreSQL에서 증명한다 — 챌린지 행 잠금을
 * 정산·참가·완주 기록이 공유하는지, 목표 행 잠금 아래에서 참가가 낡은 상태를 기록하지 않는지, 챌린지별
 * 정산 트랜잭션이 서로 독립인지. 일반 SELECT는 다른 트랜잭션의 미커밋 변경을 못 보므로 인터리빙을
 * 래치로 구조적으로 재현한다(확률에 기대지 않는다). 규약의 설명은 docs/CONCURRENCY.md 12장.
 */
class ChallengeCompletionRecordIT extends AbstractPostgresIntegrationTest {

    private static final int FEE = 100;
    private static final int INITIAL = 1000;
    private static final String CATEGORY = "자격증";
    private static final String CONDITION = "자격증:14";

    @Autowired private ChallengeService challengeService;
    @Autowired private ChallengeRepository challengeRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private PlanService planService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager tm;

    // 체크 토글은 PlanService를 거친다(지난 날짜 잠금) — 할 일 날짜는 오늘(KST)이어야 한다.
    private final String today = KstDates.today().toString();

    private Map<String, Object> tasks(boolean secondDone) {
        return Map.of(today, List.of(
                Map.of("id", "t1", "content", "공부", "completed", true),
                Map.of("id", "t2", "content", "복습", "completed", secondDone)));
    }

    private long plan(String owner, boolean done) {
        return plan(owner, done, CATEGORY);
    }

    private long plan(String owner, boolean done, String category) {
        return planRepository.save(PlanFixture.confirmed(owner, category, tasks(done))).id();
    }

    private long activeChallenge(int durationDays, int capacity, long endsInSeconds) {
        return activeChallenge(durationDays, capacity, endsInSeconds, CONDITION);
    }

    /**
     * 기간 durationDays, 마감까지 endsInSeconds초 남은 진행 중 챌린지(미충원 — JDBC join은 시작 상태를 허용한다).
     * 같은 조건의 미충원·미정산 챌린지는 동시에 하나뿐이라(부분 UNIQUE 인덱스) 한 테스트에서 둘을 만들 땐 조건을 달리한다.
     */
    private long activeChallenge(int durationDays, int capacity, long endsInSeconds, String condition) {
        String startedAt = Instant.now().plusSeconds(endsInSeconds - durationDays * 86_400L).toString();
        return challengeRepository.save(new Challenge(null, "system", "IT 챌린지", durationDays, capacity, FEE, 0,
                Instant.now().toString(), condition, startedAt, null)).id();
    }

    private long recruitingChallenge(int durationDays, int capacity) {
        return challengeRepository.save(new Challenge(null, "system", "IT 모집", durationDays, capacity, FEE, 0,
                Instant.now().toString(), CONDITION, null, null)).id();
    }

    private String completedAt(long challengeId, String owner) {
        return jdbc.queryForObject("SELECT completed_at FROM challenge_participants WHERE challenge_id = ? AND owner = ?",
                String.class, challengeId, owner);
    }

    private Integer payout(long challengeId, String owner) {
        return jdbc.queryForObject("SELECT payout FROM challenge_participants WHERE challenge_id = ? AND owner = ?",
                Integer.class, challengeId, owner);
    }

    private int balance(String owner) {
        return jdbc.queryForObject("SELECT balance FROM point_wallets WHERE owner = ?", Integer.class, owner);
    }

    private int burned() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE account = 'system:burn'",
                Integer.class);
    }

    private boolean settled(long challengeId) {
        return challengeRepository.findById(challengeId).orElseThrow().settled();
    }

    @Test
    void 기동_시_정산용_트랜잭션이_주입돼_있다() {
        assertThat(challengeService.hasSettlementTransaction()).isTrue();
    }

    @Test
    void 정산은_미커밋_완주_기록_변경이_커밋될_때까지_기다린_뒤_최신_기록으로_판정한다() throws Exception {
        long challengeId = activeChallenge(1, 5, 3); // 마감까지 3초
        String owner = "guest-race-a";
        long planId = plan(owner, false);
        challengeService.join(challengeId, owner);
        assertThat(completedAt(challengeId, owner)).isNull();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A: 마감 전에 마지막 할 일을 체크한다 — 챌린지 행을 잠근 채 아직 커밋하지 않는다.
            CompletableFuture<Void> recorder = CompletableFuture.runAsync(() ->
                    new TransactionTemplate(tm).executeWithoutResult(status -> {
                        planService.updateTaskCompletion(planId, "t2", true, owner, "it-session");
                        locked.countDown();
                        await(release);
                    }), pool);
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // 마감이 지나도록 기다린 뒤 B가 정산을 시작한다 — claim UPDATE가 A의 잠금에 막혀야 한다.
            Thread.sleep(3_500);
            CompletableFuture<Void> settler = CompletableFuture.runAsync(challengeService::settleDue, pool);
            Thread.sleep(700);
            assertThat(settler.isDone()).as("정산은 커밋되지 않은 기록 변경 뒤에 줄을 서야 한다").isFalse();

            release.countDown();
            recorder.get(10, TimeUnit.SECONDS);
            settler.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // 기록은 마감 전(잠금을 얻은 시각)의 것이고, 정산은 그 최신 기록을 읽어 완주로 판정했다.
        assertThat(completedAt(challengeId, owner)).isNotNull();
        assertThat(settled(challengeId)).isTrue();
        assertThat(payout(challengeId, owner)).isEqualTo(FEE); // 풀 100을 혼자 받는다
        assertThat(balance(owner)).isEqualTo(INITIAL);
    }

    @Test
    void 마감_후_체크는_JDBC에서도_완주로_기록되지_않는다() {
        long challengeId = activeChallenge(1, 5, -60); // 1분 전 마감
        String owner = "guest-late-a";
        long planId = plan(owner, false);
        challengeService.join(challengeId, owner);

        planService.updateTaskCompletion(planId, "t2", true, owner, "it-session"); // 마감 뒤에 100%
        challengeService.settleDue();

        assertThat(completedAt(challengeId, owner)).isNull();
        assertThat(payout(challengeId, owner)).isZero();
        assertThat(burned()).isEqualTo(FEE);
    }

    @Test
    void 참가가_읽은_100퍼센트가_다른_탭의_체크_해제_뒤에_낡은_완주_기록으로_남지_않는다() throws Exception {
        long challengeId = recruitingChallenge(14, 5);
        String owner = "guest-join-a";
        long planId = plan(owner, true); // 참가 직전엔 100%

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // U: 다른 탭에서 체크를 해제한다 — 목표 행을 잠근 채 아직 커밋하지 않는다.
            CompletableFuture<Void> unchecker = CompletableFuture.runAsync(() ->
                    new TransactionTemplate(tm).executeWithoutResult(status -> {
                        planService.updateTaskCompletion(planId, "t2", false, owner, "it-session");
                        locked.countDown();
                        await(release);
                    }), pool);
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // J: 참가가 낡은 100%를 읽고 들어가려 한다 — 목표 행 잠금에서 U 뒤에 줄을 서야 한다.
            CompletableFuture<Void> joiner = CompletableFuture.runAsync(() -> challengeService.join(challengeId, owner), pool);
            Thread.sleep(700);
            assertThat(joiner.isDone()).as("참가는 목표 변경이 커밋될 때까지 기다린다").isFalse();

            release.countDown();
            unchecker.get(10, TimeUnit.SECONDS);
            joiner.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // 참가는 잠금 뒤 최신 상태(미완료)로 시드했다 — 낡은 100%의 완주 기록이 남지 않는다.
        assertThat(completedAt(challengeId, owner)).isNull();
    }

    @Test
    void 마감_전_삭제는_완주_기록이_사라지고_마감_후_삭제는_보존된다() {
        long before = activeChallenge(14, 5, 14 * 86_400L, CONDITION);
        long after = activeChallenge(1, 5, -60, "어학:14");
        String ownerBefore = "guest-del-before";
        String ownerAfter = "guest-del-after";
        long planBefore = plan(ownerBefore, true, CATEGORY);
        long planAfter = plan(ownerAfter, true, "어학");
        challengeService.join(before, ownerBefore);
        challengeService.join(after, ownerAfter);
        assertThat(completedAt(before, ownerBefore)).isNotNull();
        assertThat(completedAt(after, ownerAfter)).isNotNull();

        planService.delete(planBefore, ownerBefore, "it-session");
        planService.delete(planAfter, ownerAfter, "it-session");

        assertThat(completedAt(before, ownerBefore)).as("마감 전 삭제 = 증거 소실").isNull();
        assertThat(completedAt(after, ownerAfter)).as("마감 후 삭제 = 동결된 기록 보존").isNotNull();
        // 어느 쪽이든 plan_id는 FK SET NULL로 끊긴다
        assertThat(jdbc.queryForObject("SELECT plan_id FROM challenge_participants WHERE challenge_id = ? AND owner = ?",
                Long.class, after, ownerAfter)).isNull();
        challengeService.settleDue();
        assertThat(payout(after, ownerAfter)).isEqualTo(FEE);
    }

    @Test
    void 같은_목표로_두_챌린지에_참가해_동시에_정산돼도_교착_없이_각각_한_번만_정산된다() throws Exception {
        plan("guest-multi-a", true);   // 완주자
        plan("guest-multi-b", false);  // 미완주
        // 같은 조건의 모집 중 챌린지는 동시에 하나뿐이다(부분 UNIQUE 인덱스) — X를 정원까지 채워 시작(=만기,
        // 기간 0)시킨 뒤에 Y를 연다. 같은 목표(a의 계획 하나)가 X·Y 둘 다에 걸린다.
        long x = challengeRepository.save(new Challenge(null, "system", "IT X", 0, 2, FEE, 0,
                Instant.now().toString(), CONDITION, null, null)).id();
        challengeService.join(x, "guest-multi-a");
        challengeService.join(x, "guest-multi-b");
        long y = challengeRepository.save(new Challenge(null, "system", "IT Y", 0, 2, FEE, 0,
                Instant.now().toString(), CONDITION, null, null)).id();
        challengeService.join(y, "guest-multi-a");
        challengeService.join(y, "guest-multi-b");

        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < racers; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                ready.countDown();
                await(start);
                challengeService.settleDue();
            }, pool));
        }
        ready.await();
        start.countDown();
        for (CompletableFuture<Void> f : futures) {
            f.get(30, TimeUnit.SECONDS); // 교착이면 여기서 시간 초과
        }
        pool.shutdown();

        assertThat(settled(x)).isTrue();
        assertThat(settled(y)).isTrue();
        // a: 두 챌린지에서 각각 풀 200을 독식 → 1000 - 200 + 400, b: 두 번 모두 미완주
        assertThat(balance("guest-multi-a")).isEqualTo(INITIAL - 2 * FEE + 2 * 2 * FEE);
        assertThat(balance("guest-multi-b")).isEqualTo(INITIAL - 2 * FEE);
        assertThat(payout(x, "guest-multi-a")).isEqualTo(2 * FEE);
        assertThat(payout(y, "guest-multi-a")).isEqualTo(2 * FEE);
        assertThat(burned()).isZero();
    }

    @Test
    void 한_챌린지의_정산_실패는_다른_챌린지를_막지_않고_바깥_트랜잭션과도_독립이다() {
        for (String owner : List.of("guest-iso-a", "guest-iso-b")) {
            plan(owner, false);
        }
        // 같은 조건의 모집 중 챌린지는 동시에 하나뿐이라 ok를 먼저 채워 시작시킨 뒤 bad를 연다.
        long ok = challengeRepository.save(new Challenge(null, "system", "IT OK", 0, 2, FEE, 0,
                Instant.now().toString(), CONDITION, null, null)).id();
        challengeService.join(ok, "guest-iso-a");
        challengeService.join(ok, "guest-iso-b");
        long bad = challengeRepository.save(new Challenge(null, "system", "IT BAD", 0, 2, FEE, 0,
                Instant.now().toString(), CONDITION, null, null)).id();
        challengeService.join(bad, "guest-iso-a");
        challengeService.join(bad, "guest-iso-b");
        // bad 챌린지의 지급 기록에서만 실패하게 하는 임시 트리거 — 지급 도중의 실패를 재현한다.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_fail_settle() RETURNS trigger AS $$
                BEGIN
                  IF NEW.challenge_id = %d AND NEW.payout IS NOT NULL THEN RAISE EXCEPTION 'boom'; END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql
                """.formatted(bad));
        jdbc.execute("CREATE TRIGGER it_fail_settle_trg AFTER UPDATE ON challenge_participants "
                + "FOR EACH ROW EXECUTE FUNCTION it_fail_settle()");
        try {
            // 호출자가 바깥 트랜잭션 안에 있어도(그리고 그것이 롤백돼도) 챌린지별 정산은 따로 커밋/롤백된다.
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                assertThatThrownBy(challengeService::settleDue).isInstanceOf(RuntimeException.class);
                status.setRollbackOnly();
            });

            assertThat(settled(ok)).as("실패한 건이 다른 건의 정산을 막지 않는다 + 바깥 롤백과 독립").isTrue();
            assertThat(settled(bad)).as("실패 건은 정산권 선점째 롤백된다").isFalse();
            assertThat(payout(bad, "guest-iso-a")).isNull();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS it_fail_settle_trg ON challenge_participants");
            jdbc.execute("DROP FUNCTION IF EXISTS it_fail_settle()");
        }

        challengeService.settleDue(); // 다음 턴에 재시도되어 정산된다
        assertThat(settled(bad)).isTrue();
        assertThat(burned()).isEqualTo(2 * 2 * FEE); // 두 챌린지 모두 미완주 → 풀 200씩 소멸
    }

    @Test
    void 정산_보류_건은_정산되지_않고_REVIEWING으로_보이며_해제_후에만_정산된다() {
        long challengeId = activeChallenge(1, 5, -60);
        String owner = "guest-hold-a";
        plan(owner, true);
        challengeService.join(challengeId, owner);
        jdbc.update("UPDATE challenges SET settlement_hold = TRUE WHERE id = ?", challengeId);

        challengeService.settleDue();

        assertThat(settled(challengeId)).isFalse();
        ChallengeResponse view = challengeService.list(owner).challenges().getFirst();
        assertThat(view.status()).isEqualTo("REVIEWING");

        // 운영자가 완주 근거를 기록하고(join이 이미 시드) 보류를 푼 뒤에야 정산된다.
        jdbc.update("UPDATE challenges SET settlement_hold = FALSE WHERE id = ?", challengeId);
        challengeService.settleDue();
        assertThat(settled(challengeId)).isTrue();
        assertThat(payout(challengeId, owner)).isEqualTo(FEE);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
