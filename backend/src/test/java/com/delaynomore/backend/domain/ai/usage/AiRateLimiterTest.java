package com.delaynomore.backend.domain.ai.usage;

import com.delaynomore.backend.global.config.AiRateLimitProperties;
import com.delaynomore.backend.global.time.KstDates;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AiRateLimiterTest {

    private static AiRateLimiter limiter(int perOwner, int global) {
        return new AiRateLimiter(new AiRateLimitProperties(perOwner, global));
    }

    @Test
    void 전역_상한까지는_통과하고_그_다음부터_막는다() {
        AiRateLimiter limiter = limiter(0, 3);

        assertThat(limiter.tryAcquireGlobal()).isTrue();
        assertThat(limiter.tryAcquireGlobal()).isTrue();
        assertThat(limiter.tryAcquireGlobal()).isTrue();
        assertThat(limiter.tryAcquireGlobal()).isFalse();
        // 막힌 호출은 소비되지 않는다 — 카운터가 상한을 넘어 부풀면 리셋 전까지 복구가 안 된다.
        assertThat(limiter.snapshot().globalUsed()).isEqualTo(3);
    }

    @Test
    void 소유자_카운터는_서로_독립이고_전역과도_별개다() {
        AiRateLimiter limiter = limiter(1, 0);

        assertThat(limiter.tryAcquireOwner("user-1")).isTrue();
        assertThat(limiter.tryAcquireOwner("user-1")).isFalse();
        assertThat(limiter.tryAcquireOwner("user-2")).isTrue(); // 다른 소유자는 영향 없음
        assertThat(limiter.snapshot().globalUsed()).isZero(); // 소유자 소비는 전역에 안 쌓인다
    }

    @Test
    void 소유자를_모르는_호출은_전역_상한에만_맡긴다() {
        AiRateLimiter limiter = limiter(1, 0);

        assertThat(limiter.tryAcquireOwner(null)).isTrue();
        assertThat(limiter.tryAcquireOwner("  ")).isTrue();
        assertThat(limiter.tryAcquireOwner("")).isTrue();
    }

    @Test
    void 상한이_0_이하면_무제한이다() {
        AiRateLimiter limiter = limiter(0, -1);

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquireGlobal()).isTrue();
            assertThat(limiter.tryAcquireOwner("user-1")).isTrue();
        }
        assertThat(limiter.snapshot().globalExhausted()).isFalse(); // 끈 상한은 소진되지 않는다
    }

    @Test
    void 날짜가_바뀌면_카운터가_통째로_리셋된다() {
        AiRateLimiter limiter = limiter(1, 1);
        assertThat(limiter.tryAcquireGlobal()).isTrue();
        assertThat(limiter.tryAcquireOwner("user-1")).isTrue();
        assertThat(limiter.tryAcquireGlobal()).isFalse();
        assertThat(limiter.snapshot().globalExhausted()).isTrue();

        limiter.countersFor(KstDates.today().plusDays(1)); // KST 자정 통과

        assertThat(limiter.tryAcquireGlobal()).isTrue();
        assertThat(limiter.tryAcquireOwner("user-1")).isTrue(); // 소유자 카운터도 함께 비워진다
    }

    @Test
    void 동시_호출에서도_상한을_넘겨_통과시키지_않는다() throws Exception {
        int limit = 20;
        int threads = 64;
        AiRateLimiter limiter = limiter(0, limit);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (limiter.tryAcquireGlobal()) {
                            granted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        // "확인 후 증가"였다면 여기서 limit를 넘긴다 — 증가 후 되돌리기가 정확히 limit에서 멈춘다.
        assertThat(granted.get()).isEqualTo(limit);
        assertThat(limiter.snapshot().globalUsed()).isEqualTo(limit);
    }
}
