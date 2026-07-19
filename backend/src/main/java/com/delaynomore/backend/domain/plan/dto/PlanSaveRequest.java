package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.validation.ValidPlanTasks;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

// 계획 보관/수정 요청(POST·PUT 공용). 형식 검증은 여기서(@Valid) 하고, 위반은 400 + fieldErrors.
// tasks는 형식(@ValidPlanTasks)만 검증하고 내용은 원본 그대로 보관한다(정규화·변조 없음).
public record PlanSaveRequest(

        @NotBlank(message = "목표를 공백 제외 2자 이상 입력해주세요.")
        @Pattern(regexp = "\\s*\\S[\\s\\S]*\\S\\s*", message = "목표를 공백 제외 2자 이상 입력해주세요.")
        String goalName,

        // [범위 규칙의 소유권] 초안 생성 상한은 AiDraftRequest(1~14일)가 서버에서 강제한다.
        // 보관 상한을 14로 조이면 안 되는 이유: 이월(carry-over)·대화 수정("기간 +3일" 반복)이
        // endDate/duration을 계속 +1 연장할 수 있어, 보관 시점의 duration은 14를 넘는 것이 정상이다.
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
        @ValidPlanTasks
        Map<String, Object> tasks,

        // 아래는 선택 필드 — 프론트 상태를 그대로 왕복시킨다(status 미지정(null) 시 DRAFT).
        // @Pattern은 null을 통과시키므로 기본값 보정과 충돌하지 않는다. POST로 CONFIRMED를
        // 바로 만드는 것은 허용한다(API 일관성 — 고정 가드는 update에만 걸린다).
        @Pattern(regexp = "DRAFT|CONFIRMED", message = "status는 DRAFT 또는 CONFIRMED만 허용됩니다.")
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
