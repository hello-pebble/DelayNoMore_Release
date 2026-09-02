package com.delaynomore.backend.domain.challenge;

import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.repository.jdbc.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 생성의 중복 방지를 실제 PostgreSQL에서 증명하는 테스트.
 *
 * 인메모리 판본(ChallengeAutoGenerationTest)이 검증하는 규칙은 같지만, 여기서 확인하려는 것은
 * 그 규칙을 지키는 주체가 애플리케이션 코드가 아니라 <b>부분 UNIQUE 인덱스</b>
 * (uq_challenges_open_condition, WHERE participant_count &lt; capacity)라는 사실이다.
 * 임계치를 동시에 넘긴 고정 요청이 여러 개여도 같은 조건의 모집 중 챌린지는 하나만 남아야 한다.
 */
class ChallengeAutoGenerationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private com.delaynomore.backend.domain.challenge.repository.ChallengeRepository challengeRepository;

    @Test
    void 임계치를_동시에_넘겨도_같은_조건의_챌린지는_하나만_열린다() throws Exception {
        // 이미 2명이 모여 있어, 다음 고정 한 건이면 임계치(3명)를 넘는 상태.
        challengeService.onPlanConfirmed("guest-a-0001", "어학:14");
        challengeService.onPlanConfirmed("guest-b-0001", "어학:14");

        int racers = 8;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < racers; i++) {
            String owner = String.format("guest-race-%04d", i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    challengeService.onPlanConfirmed(owner, "어학:14");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        // 사전 조회로 막았다면 여기서 여러 건이 나온다 — 판정은 인덱스가 단독으로 했다.
        assertThat(challengeRepository.findAll()).singleElement()
                .satisfies(c -> assertThat(c.conditionKey()).isEqualTo("어학:14"));
    }

    @Test
    void 정원이_차면_같은_조건의_다음_챌린지가_열린다() {
        // 인덱스의 WHERE participant_count < capacity 덕분에 마감된 챌린지는 조건을 점유하지 않는다.
        challengeService.onPlanConfirmed("guest-a-0001", "어학:14");
        challengeService.onPlanConfirmed("guest-b-0001", "어학:14");
        challengeService.onPlanConfirmed("guest-c-0001", "어학:14");
        long first = challengeRepository.findAll().getFirst().id();
        for (int i = 0; i < 5; i++) {
            challengeService.join(first, "guest-filler-" + i);
        }

        challengeService.onPlanConfirmed("guest-d-0001", "어학:14");

        assertThat(challengeRepository.findAll()).hasSize(2);
    }

    @Test
    void 같은_소유자가_여러_번_고정해도_씨앗은_하나다() {
        challengeService.onPlanConfirmed("guest-a-0001", "어학:14");
        challengeService.onPlanConfirmed("guest-a-0001", "어학:14");
        challengeService.onPlanConfirmed("guest-a-0001", "어학:14");

        assertThat(challengeRepository.findAll()).isEmpty();
    }
}
