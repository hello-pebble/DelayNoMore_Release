package com.delaynomore.backend.domain.challenge;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.jdbc.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "챌린지는 최대 한 번 정산된다"를 실제 PostgreSQL에서 증명하는 테스트 —
 * ChallengeJoinConcurrencyIT와 같은 구성(naive 대조군 + safe 실험군)이다.
 *
 *   naive — "SELECT settled_at → 자바에서 if → 지급". 두 문장 사이가 열려 있어 동시 조회
 *           여러 건이 모두 "미정산"을 읽고 모두 지급한다. **환불이 겹쳐 돈이 불어난다.**
 *   safe  — ChallengeService.settleDue. 정산권 판정이 UPDATE의 WHERE 절 안에 있어
 *           (WHERE settled_at IS NULL) 정확히 한 호출만 지급 경로에 들어간다.
 *
 * naive 코드는 이 파일 안에만 있다 — 프로덕션에 시연용 unsafe 분기를 남기지 않는다.
 * 배경 설명은 docs/CONCURRENCY.md 8절.
 */
class ChallengeSettlementConcurrencyIT extends AbstractPostgresIntegrationTest {

    private static final int ENTRY_FEE = 100;
    private static final int INITIAL_BALANCE = 1000;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private void plan(String owner) {
        String now = Instant.now().toString();
        planRepository.save(new Plan(null, owner, "정보처리기사 실기", 14, 2, "초급",
                Map.of("2026-09-01", List.of(Map.of("id", "t1", "content", "공부", "completed", false))),
                "CONFIRMED", now, null, "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증"));
    }

    // durationDays=0 → 정원이 차는 순간 만기. 미완주 계획뿐이라 정산 결과는 전원 환불이다.
    private long dueChallenge(String suffix) {
        long id = challengeRepository.save(new Challenge(null, "system", "정산 경합 " + suffix, 0, 2,
                ENTRY_FEE, 0, Instant.now().toString(), "자격증:14", null, null)).id();
        for (String guest : List.of("guest-settle-a-" + suffix, "guest-settle-b-" + suffix)) {
            plan(guest);
            challengeService.join(id, guest);
        }
        return id;
    }

    private int balanceOf(String owner) {
        return jdbc.queryForObject("SELECT balance FROM point_wallets WHERE owner = ?", Integer.class, owner);
    }

    @Test
    void naive_검사후지급은_동시정산에서_환불이_겹친다() throws Exception {
        long challengeId = dueChallenge("naive");
        int racers = 5;

        // 모든 스레드가 "읽기"를 끝낸 뒤에 "지급"을 시작하도록 배리어를 둔다 — 확률이 아니라
        // 구조로 재현한다(ChallengeJoinConcurrencyIT의 naive와 같은 방식).
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch allRead = new CountDownLatch(racers);
        CountDownLatch payGate = new CountDownLatch(1);

        for (int i = 0; i < racers; i++) {
            pool.submit(() -> {
                try {
                    // 1) 읽기 — 각자 자기 오토커밋 트랜잭션에서 정산 여부를 읽는다.
                    String settledAt = jdbc.queryForObject(
                            "SELECT settled_at FROM challenges WHERE id = ?", String.class, challengeId);
                    boolean unsettled = settledAt == null;   // 2) 자바에서 판정 — 여기가 틈이다
                    allRead.countDown();
                    payGate.await();
                    if (unsettled) {
                        // 3) 지급 — 판정의 근거는 이미 낡았지만 그대로 환불한다.
                        jdbc.update("UPDATE challenges SET settled_at = ? WHERE id = ?",
                                Instant.now().toString(), challengeId);
                        jdbc.update("""
                                UPDATE point_wallets SET balance = balance + ?
                                 WHERE owner IN (SELECT owner FROM challenge_participants WHERE challenge_id = ?)
                                """, ENTRY_FEE, challengeId);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        allRead.await();
        payGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        // 환불이 다섯 번 겹쳤다 — 참가비 100을 냈는데 잔액이 원금을 넘는다. 이것이 막아야 할 상태다.
        assertThat(balanceOf("guest-settle-a-naive")).isGreaterThan(INITIAL_BALANCE);
        assertThat(balanceOf("guest-settle-a-naive"))
                .isEqualTo(INITIAL_BALANCE - ENTRY_FEE + ENTRY_FEE * racers);
    }

    @Test
    void safe_claimSettlement은_동시정산에서도_지급이_정확히_1회분이다() throws Exception {
        long challengeId = dueChallenge("safe");
        int racers = 5;

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < racers; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    challengeService.settleDue();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        // 완주자 0명 → 전원 환불이 정확히 1회 — 잔액이 원금과 같고, payout도 한 번만 기록됐다.
        assertThat(balanceOf("guest-settle-a-safe")).isEqualTo(INITIAL_BALANCE);
        assertThat(balanceOf("guest-settle-b-safe")).isEqualTo(INITIAL_BALANCE);
        assertThat(challengeRepository.findById(challengeId).orElseThrow().settled()).isTrue();
        Integer refunded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM challenge_participants WHERE challenge_id = ? AND payout = ?",
                Integer.class, challengeId, ENTRY_FEE);
        assertThat(refunded).isEqualTo(2);
    }
}
