package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.validation.ValidPlanTasks;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 추천 초안 승인(저장) 요청 — 사용자가 미리보기에서 승인한 tasks를 그대로 보관한다. 목표·수준은
 * 서버가 원본에서 다시 승계하고 날짜·기간은 tasks에서 산출하므로, 클라이언트가 실을 값은 이 세 가지뿐이다.
 * accepted(추천 채택/변경)는 서버가 selected == recommended로 도출한다(클라이언트 신뢰 최소화).
 * tasks 형식은 계획 저장과 같은 규칙(@ValidPlanTasks)으로 검증한다.
 */
public record RecommendationConfirmRequest(

        @NotEmpty(message = "계획 내용(tasks)이 비어 있습니다.")
        @ValidPlanTasks
        Map<String, Object> tasks,

        @NotNull(message = "선택한 하루 할 일 개수가 필요합니다.")
        @Min(value = 1, message = "하루 할 일 개수는 1~5개여야 합니다.")
        @Max(value = 5, message = "하루 할 일 개수는 1~5개여야 합니다.")
        Integer selectedTasksPerDay,

        @NotNull(message = "추천 하루 할 일 개수가 필요합니다.")
        @Min(value = 1, message = "하루 할 일 개수는 1~5개여야 합니다.")
        @Max(value = 5, message = "하루 할 일 개수는 1~5개여야 합니다.")
        Integer recommendedTasksPerDay
) {

    // 추천대로 골랐는지 — 서버가 도출한다(WORKLOAD_RECOMMENDATION_ACCEPTED vs OVERRIDDEN 판정).
    public boolean accepted() {
        return selectedTasksPerDay != null && selectedTasksPerDay.equals(recommendedTasksPerDay);
    }
}
