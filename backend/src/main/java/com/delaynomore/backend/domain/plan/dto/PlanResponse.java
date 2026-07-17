package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;

import java.util.Map;

// 보관된 계획 응답. 목록 조회도 이 DTO를 그대로 쓴다 — 데모 규모에서는 tasks까지 전체를
// 반환하는 편이 단순하고, 프론트가 목록에서 완료 진행률을 바로 계산할 수 있다.
public record PlanResponse(
        long id,
        String goalName,
        Integer duration,
        Integer dailyHours,
        String currentLevel,
        Map<String, Object> tasks,
        String status,
        String confirmedAt,
        String startDate,
        String endDate,
        String createdAt,
        long savedAt
) {

    public static PlanResponse from(Plan plan) {
        return new PlanResponse(plan.id(), plan.goalName(), plan.duration(), plan.dailyHours(),
                plan.currentLevel(), plan.tasks(), plan.status(), plan.confirmedAt(),
                plan.startDate(), plan.endDate(), plan.createdAt(), plan.savedAt());
    }
}
