package com.delaynomore.backend.domain.plan.repository.jdbc;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.Reflection;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// JdbcReflectionRepository 통합 테스트 — 업서트 createdAt 보존, 날짜 내림차순 조회, 캐스케이드 삭제.
class JdbcReflectionRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private ReflectionRepository reflectionRepository;

    private long newPlanId() {
        Plan saved = planRepository.save(new Plan(null, "guest-1", "목표", 1, 1, "초급",
                Map.of(), "DRAFT", null, "2026-07-21", "2026-07-21", "2026-07-21T09:00:00Z", 1L));
        return saved.id();
    }

    @Test
    void upsert_insertThenUpdate_preservesCreatedAt_advancesUpdatedAt() {
        long planId = newPlanId();

        Reflection first = reflectionRepository.upsert(planId, "2026-07-21", existing ->
                new Reflection(planId, "2026-07-21", 1, 3, "NORMAL", "AS_PLANNED",
                        "2026-07-21T10:00:00Z", "2026-07-21T10:00:00Z"));
        assertThat(first.createdAt()).isEqualTo("2026-07-21T10:00:00Z");

        // 재저장 — 서비스 람다처럼 기존 createdAt을 보존하고 updatedAt만 전진시킨다.
        Reflection second = reflectionRepository.upsert(planId, "2026-07-21", existing ->
                new Reflection(planId, "2026-07-21", 3, 3, "HARD", "TOO_MUCH_WORK",
                        existing.createdAt(), "2026-07-21T20:00:00Z"));

        assertThat(second.createdAt()).isEqualTo("2026-07-21T10:00:00Z"); // 보존
        assertThat(second.updatedAt()).isEqualTo("2026-07-21T20:00:00Z"); // 전진
        assertThat(second.completedCount()).isEqualTo(3);
        assertThat(second.difficulty()).isEqualTo("HARD");

        // (planId, date)당 1건 — 업서트라 행이 늘지 않는다.
        assertThat(reflectionRepository.findAllByPlanId(planId)).hasSize(1);
    }

    @Test
    void findAllByPlanId_ordersByDateDesc() {
        long planId = newPlanId();
        for (String date : List.of("2026-07-20", "2026-07-22", "2026-07-21")) {
            reflectionRepository.upsert(planId, date, existing ->
                    new Reflection(planId, date, 1, 1, "EASY", "AS_PLANNED",
                            "2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z"));
        }

        assertThat(reflectionRepository.findAllByPlanId(planId))
                .extracting(Reflection::date)
                .containsExactly("2026-07-22", "2026-07-21", "2026-07-20");
    }

    @Test
    void deleteAllByPlanId_removesOnlyThatPlansReflections() {
        long planA = newPlanId();
        long planB = newPlanId();
        reflectionRepository.upsert(planA, "2026-07-21", e -> new Reflection(planA, "2026-07-21",
                1, 1, "EASY", "AS_PLANNED", "t", "t"));
        reflectionRepository.upsert(planB, "2026-07-21", e -> new Reflection(planB, "2026-07-21",
                1, 1, "EASY", "AS_PLANNED", "t", "t"));

        reflectionRepository.deleteAllByPlanId(planA);

        assertThat(reflectionRepository.findAllByPlanId(planA)).isEmpty();
        assertThat(reflectionRepository.findAllByPlanId(planB)).hasSize(1);
    }
}
