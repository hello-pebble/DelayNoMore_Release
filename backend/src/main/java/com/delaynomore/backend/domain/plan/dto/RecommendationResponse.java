package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.ReflectionReason;
import com.delaynomore.backend.domain.plan.support.WorkloadRecommendation.Recommendation;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 다음 계획 분량 추천 응답 — 화면 "지난 계획" 통계 + 서버 규칙이 결정한 추천 분량 + AI(또는 템플릿)
 * 이유. recommendedTasksPerDay는 서버 규칙이 소유하고, reason은 표시용 설명일 뿐이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationResponse(
        long sourcePlanId,
        int currentTasksPerDay,
        int recommendedTasksPerDay,
        int observedDays,
        int completedCount,
        int totalCount,
        int completionRate,
        int hardCount,
        MetaOptionResponse topReason,     // 최빈 회고 이유 {code,label}, 없으면 null
        boolean insufficientHistory,      // 관찰 3일 미만 — 추천 없이 기존 분량 유지
        int observedPlanCount,            // 합산에 쓴 계획 수(1~3) — 같은 목표 최근 계획
        String reason,                    // AI 또는 서버 템플릿 설명(항상 존재)
        boolean aiReasonUsed              // 이유가 AI 생성인지(false면 서버 템플릿 폴백)
) {

    public static RecommendationResponse from(Plan plan, Recommendation rec, String reason, boolean aiReasonUsed) {
        MetaOptionResponse topReason = rec.topReasonCode() == null
                ? null
                : new MetaOptionResponse(rec.topReasonCode(), reasonLabel(rec.topReasonCode()));
        return new RecommendationResponse(plan.id(), rec.currentTasksPerDay(), rec.recommendedTasksPerDay(),
                rec.observedDays(), rec.completedCount(), rec.totalCount(), rec.completionRate(),
                rec.hardCount(), topReason, rec.insufficientHistory(), rec.observedPlanCount(),
                reason, aiReasonUsed);
    }

    private static String reasonLabel(String reasonCode) {
        try {
            return ReflectionReason.valueOf(reasonCode).getLabel();
        } catch (IllegalArgumentException e) {
            return reasonCode;
        }
    }
}
