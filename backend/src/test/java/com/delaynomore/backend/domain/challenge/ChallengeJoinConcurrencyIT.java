package com.delaynomore.backend.domain.challenge;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.repository.jdbc.AbstractPostgresIntegrationTest;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이 기능이 존재하는 이유를 실제 PostgreSQL에서 증명하는 테스트.
 *
 * 두 테스트는 완전히 같은 시나리오(정원 5명 · 남은 자리 1개 · 5명이 동시에 참가)를 서로 다른
 * 구현으로 실행한다:
 *
 *   naive — "SELECT participant_count → 자바에서 if → UPDATE +1". 두 문장 사이가 열려 있어
 *           다섯 스레드가 모두 같은 값을 읽고 모두 통과한다. **정원을 넘긴다.**
 *   safe  — ChallengeService.join. 정원 검사가 UPDATE의 WHERE 절 안에 있어
 *           (WHERE participant_count &lt; capacity) 검사와 증가 사이에 틈이 없다. **정확히 1명.**
 *
 * naive 코드는 이 파일 안에만 있다 — 프로덕션에 시연용 unsafe 분기를 남기지 않는다.
 * 배경 설명은 docs/CONCURRENCY.md.
 */
class ChallengeJoinConcurrencyIT extends AbstractPostgresIntegrationTest {

    private static final int CAPACITY = 5;
    private static final int ENTRY_FEE = 100;
    private static final int INITIAL_BALANCE = 1000;
    private static final String HOST = "guest-host-0001";
    private static final List<String> CONTENDERS = List.of(
            "guest-a-0001", "guest-b-0001", "guest-c-0001", "guest-d-0001", "guest-e-0001");

    @Autowired
    private ChallengeService challengeService;

    // 개설 API가 없어졌으므로(v0.23.0) 픽스처는 저장소에 직접 넣는다 — 이 테스트가 검증하는 것은
    // 참가의 동시성이지 챌린지가 어떻게 생기는지가 아니다.
    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private long openChallenge(String title) {
        return challengeRepository.save(new Challenge(null, HOST, title, 14, CAPACITY, ENTRY_FEE, 0,
                java.time.Instant.now().toString(), null)).id();
    }

    // 정원 CAPACITY, 이미 CAPACITY-1명이 참가해 남은 자리가 정확히 1개인 챌린지를 만든다.
    private long challengeWithOneSeatLeft() {
        long id = openChallenge("자격증 공부 14일");
        for (int i = 0; i < CAPACITY - 1; i++) {
            challengeService.join(id, "guest-early-000" + i);
        }
        assertThat(participantCount(id)).isEqualTo(CAPACITY - 1);
        return id;
    }

    private int participantCount(long id) {
        return jdbc.queryForObject("SELECT participant_count FROM challenges WHERE id = ?", Integer.class, id);
    }

    @Test
    void naive_검사후쓰기는_동시요청에서_정원을_초과한다() throws Exception {
        long challengeId = challengeWithOneSeatLeft();
        int racers = CONTENDERS.size();

        // 모든 스레드가 "읽기"를 끝낸 뒤에 "쓰기"를 시작하도록 배리어를 하나 더 둔다. 실제 운영에서
        // 이 인터리빙은 확률적으로 발생하지만, 테스트는 확률에 기대지 않고 재현한다 — 증명하려는
        // 것은 "가끔 깨진다"가 아니라 "이 인터리빙이 가능한 구조다"이기 때문이다.
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch allRead = new CountDownLatch(racers);
        CountDownLatch writeGate = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        for (String contender : CONTENDERS) {
            pool.submit(() -> {
                try {
                    // 1) 읽기 — 각자 자기 오토커밋 트랜잭션에서 현재 인원을 읽는다.
                    int seen = participantCount(challengeId);
                    boolean hasSeat = seen < CAPACITY;   // 2) 자바에서 판정 — 여기가 틈이다
                    allRead.countDown();
                    writeGate.await();
                    if (hasSeat) {
                        // 3) 쓰기 — 판정의 근거였던 값은 이미 낡았지만 그대로 증가시킨다.
                        jdbc.update("UPDATE challenges SET participant_count = participant_count + 1 WHERE id = ?",
                                challengeId);
                        jdbc.update("INSERT INTO challenge_participants (challenge_id, owner, joined_at) "
                                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", challengeId, contender, "2026-08-17T00:00:00Z");
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        allRead.await();
        writeGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        // 남은 자리는 1개였는데 5명 전원이 통과했다 — 이것이 막아야 할 상태다.
        assertThat(accepted.get()).isEqualTo(racers);
        assertThat(participantCount(challengeId)).isGreaterThan(CAPACITY);
        assertThat(participantCount(challengeId)).isEqualTo(CAPACITY - 1 + racers);
    }

    @Test
    void safe_조건부UPDATE는_동시요청에서도_정확히_1명만_받는다() throws Exception {
        long challengeId = challengeWithOneSeatLeft();
        int racers = CONTENDERS.size();

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        var winners = ConcurrentHashMap.<String>newKeySet();

        for (String contender : CONTENDERS) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    challengeService.join(challengeId, contender);
                    success.incrementAndGet();
                    winners.add(contender);
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.CHALLENGE_FULL) {
                        full.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(1);
        assertThat(full.get()).isEqualTo(racers - 1);
        assertThat(participantCount(challengeId)).isEqualTo(CAPACITY);

        // 카운터와 실제 참가자 행 수가 어긋나지 않는다 — 둘은 같은 트랜잭션에서만 움직인다.
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM challenge_participants WHERE challenge_id = ?", Integer.class, challengeId);
        assertThat(rows).isEqualTo(CAPACITY);

        // 탈락자의 포인트는 롤백으로 되돌아왔다 — 자리를 못 얻었는데 참가비만 잃는 일은 없다.
        for (String contender : CONTENDERS) {
            int expected = winners.contains(contender) ? INITIAL_BALANCE - ENTRY_FEE : INITIAL_BALANCE;
            assertThat(balanceOf(contender)).as("잔액 of %s", contender).isEqualTo(expected);
        }
    }

    // 탈락자는 지갑 행 자체가 롤백으로 사라졌을 수 있다(그 트랜잭션 안에서 처음 만들어졌으므로).
    // 행이 없으면 아직 한 번도 쓰지 않은 것이므로 초기 잔액과 같다.
    private int balanceOf(String owner) {
        List<Integer> found = jdbc.queryForList(
                "SELECT balance FROM point_wallets WHERE owner = ?", Integer.class, owner);
        return found.isEmpty() ? INITIAL_BALANCE : found.getFirst();
    }
}
