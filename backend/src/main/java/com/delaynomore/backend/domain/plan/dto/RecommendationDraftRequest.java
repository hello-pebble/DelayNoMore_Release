package com.delaynomore.backend.domain.plan.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 추천 초안 생성 요청 — 사용자가 고른 하루 분량만 담는다(목표·기간·수준은 서버가 원본 계획에서 승계).
public record RecommendationDraftRequest(

        @NotNull(message = "하루 할 일 개수는 1~5개여야 합니다.")
        @Min(value = 1, message = "하루 할 일 개수는 1~5개여야 합니다.")
        @Max(value = 5, message = "하루 할 일 개수는 1~5개여야 합니다.")
        Integer selectedTasksPerDay
) {
}
