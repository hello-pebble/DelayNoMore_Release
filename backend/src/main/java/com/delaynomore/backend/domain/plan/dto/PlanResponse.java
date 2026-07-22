package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.support.WorkloadRecommendation;
import com.delaynomore.backend.global.time.KstDates;

import java.util.Map;

// 보관된 계획 응답. 목록 조회도 이 DTO를 그대로 쓴다 — 데모 규모에서는 tasks까지 전체를
// 반환하는 편이 단순하다. 완료 진행률은 서버가 progress로 내려준다(계산 소유권은 서버 —
// 추후 목록 API가 tasks 전체를 안 내려도 되는 기반).
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
        long savedAt,
        Progress progress,
        // 다음 계획 분량 추천 버튼 노출 여부 — 완료했거나 3일 이상 실행한 계획일 때 true. 규칙을
        // 서버가 소유해(WorkloadRecommendation.isEligible) 프론트는 이 플래그만 보고 버튼을 그린다.
        boolean recommendationEligible
) {

    // 전 날짜 합산 완료/전체 — 프론트 보관함 목록 행의 진행률 표시가 그대로 쓴다.
    public record Progress(int done, int total) {
    }

    public static PlanResponse from(Plan plan) {
        Plan.TaskCounts counts = plan.countAllTasks();
        boolean eligible = WorkloadRecommendation.isEligible(plan, KstDates.today(), counts);
        return new PlanResponse(plan.id(), plan.goalName(), plan.duration(), plan.dailyHours(),
                plan.currentLevel(), plan.tasks(), plan.status(), plan.confirmedAt(),
                plan.startDate(), plan.endDate(), plan.createdAt(), plan.savedAt(),
                new Progress(counts.completed(), counts.total()), eligible);
    }
}
