package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

// 계획 보관/수정 요청(POST·PUT 공용). 형식 검증은 여기서(@Valid) 하고, 위반은 400 + fieldErrors.
// tasks 내부 구조는 프론트 렌더링이 방어적이므로 서버는 원본 그대로 보관만 한다.
public record PlanSaveRequest(

        @NotBlank(message = "목표를 공백 제외 2자 이상 입력해주세요.")
        @Pattern(regexp = "\\s*\\S[\\s\\S]*\\S\\s*", message = "목표를 공백 제외 2자 이상 입력해주세요.")
        String goalName,

        // 초안 생성은 1~14일이지만, "기간 +3일" 반복으로 보관 시점엔 더 길 수 있어 상한을 느슨하게 둔다.
        @NotNull(message = "기간은 1~365일 사이의 정수여야 합니다.")
        @Min(value = 1, message = "기간은 1~365일 사이의 정수여야 합니다.")
        @Max(value = 365, message = "기간은 1~365일 사이의 정수여야 합니다.")
        Integer duration,

        @NotNull(message = "하루 투자 시간은 1~24시간 사이의 정수여야 합니다.")
        @Min(value = 1, message = "하루 투자 시간은 1~24시간 사이의 정수여야 합니다.")
        @Max(value = 24, message = "하루 투자 시간은 1~24시간 사이의 정수여야 합니다.")
        Integer dailyHours,

        @NotBlank(message = "현재 수준을 2자 이상 입력해주세요.")
        @Pattern(regexp = "\\s*\\S[\\s\\S]*\\S\\s*", message = "현재 수준을 2자 이상 입력해주세요.")
        String currentLevel,

        @NotEmpty(message = "계획 내용(tasks)이 비어 있습니다.")
        Map<String, Object> tasks,

        // 아래는 선택 필드 — 프론트 상태를 그대로 왕복시킨다(status 미지정 시 DRAFT).
        String status,
        String confirmedAt,
        String startDate,
        String endDate,
        String createdAt
) {

    private static final String DEFAULT_STATUS = "DRAFT";

    public Plan toPlan(Long id, long savedAt) {
        String resolvedStatus = (status == null || status.isBlank()) ? DEFAULT_STATUS : status;
        return new Plan(id, goalName, duration, dailyHours, currentLevel, tasks,
                resolvedStatus, confirmedAt, startDate, endDate, createdAt, savedAt);
    }
}
