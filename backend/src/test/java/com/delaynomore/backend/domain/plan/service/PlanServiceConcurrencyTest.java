package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 동시성 검증 — 한도 가드(TOCTOU)와 소유자 가드(원자성)가 병렬 요청에서도 유지되는지 확인한다.
// 인메모리라 Mock 없이 실제 저장소를 주입한다.
class PlanServiceConcurrencyTest {

    private static final String OWNER = "guest-a";
    private static final String HIJACKER = "guest-b";

    private final PlanRepository planRepository = new PlanRepository();
    private final PlanService planService = new PlanService(planRepository,
            new ReflectionRepository(), new AuditEventService(new AuditEventRepository()));

    private PlanSaveRequest request(String goalName) {
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(Map.of("id", "t-1", "content", "단어 암기", "completed", false)));
        return new PlanSaveRequest(goalName, 3, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z");
    }

    @Test
    void create_동시20건_소유자한도10정확히유지_TOCTOU없음() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            int n = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    planService.create(request("목표 " + n), OWNER, null);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.PLAN_LIMIT_EXCEEDED) {
                        limited.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 정확히 한도까지만 성공 — synchronized create가 검사·저장을 원자로 묶어 TOCTOU를 막는다.
        assertThat(success.get()).isEqualTo(10);
        assertThat(limited.get()).isEqualTo(10);
        assertThat(planService.getPlans(OWNER)).hasSize(10);
    }

    @Test
    void update와delete_동시실행_소유자가드원자성_부활도탈취도없음() throws Exception {
        // 매 라운드: 소유자 delete와 타인 update(탈취 시도)를 동시에 — 어떤 인터리빙에서도
        // 결과는 항상 "삭제됨 + 탈취 실패"여야 한다(계획 부활 금지, 소유자 변경 금지).
        for (int round = 0; round < 50; round++) {
            PlanResponse saved = planService.create(request("r" + round), OWNER, null);
            CountDownLatch start = new CountDownLatch(1);

            Thread deleter = new Thread(() -> {
                await(start);
                tryIgnoringBusiness(() -> planService.delete(saved.id(), OWNER, null));
            });
            Thread hijacker = new Thread(() -> {
                await(start);
                tryIgnoringBusiness(() -> planService.update(saved.id(), request("탈취"), HIJACKER, null));
            });
            deleter.start();
            hijacker.start();
            start.countDown();
            deleter.join();
            hijacker.join();

            assertThat(planRepository.findById(saved.id())).isEmpty();   // 부활 금지
            assertThat(planService.getPlans(HIJACKER)).isEmpty();        // 탈취 금지
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void tryIgnoringBusiness(Runnable action) {
        try {
            action.run();
        } catch (BusinessException ignored) {
            // 인터리빙에 따라 PLAN_NOT_FOUND/PLAN_LOCKED가 나는 것은 정상 — 삼킨다.
        }
    }
}
