package com.delaynomore.backend.domain.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

// 계획 초안 생성 요청. 형식 검증은 여기서(@Valid) 하고, 위반은 400 + fieldErrors로 응답한다.
// (프론트가 막더라도 API는 직접 호출될 수 있으므로 서버에서 다시 차단한다.)
public record AiDraftRequest(

        // "공백 제외 2자 이상" — 정규식은 비공백 문자가 2개 이상인 문자열만 통과시킨다.
        @NotBlank(message = "목표를 공백 제외 2자 이상 입력해주세요.")
        @Pattern(regexp = "\\s*\\S[\\s\\S]*\\S\\s*", message = "목표를 공백 제외 2자 이상 입력해주세요.")
        String goalName,

        @NotNull(message = "기간은 1~14일 사이의 정수여야 합니다.")
        @Min(value = 1, message = "기간은 1~14일 사이의 정수여야 합니다.")
        @Max(value = 14, message = "기간은 1~14일 사이의 정수여야 합니다.")
        Integer duration,

        @NotNull(message = "하루 투자 시간은 1~24시간 사이의 정수여야 합니다.")
        @Min(value = 1, message = "하루 투자 시간은 1~24시간 사이의 정수여야 합니다.")
        @Max(value = 24, message = "하루 투자 시간은 1~24시간 사이의 정수여야 합니다.")
        Integer dailyHours,

        @NotBlank(message = "현재 수준을 2자 이상 입력해주세요.")
        @Pattern(regexp = "\\s*\\S[\\s\\S]*\\S\\s*", message = "현재 수준을 2자 이상 입력해주세요.")
        String currentLevel,

        String refinementPrompt,

        // 재수정 시 함께 오는 직전 초안({날짜: [{id,content,completed}] | [문자열]})
        Map<String, Object> previousTasks
) {

    // 재수정 요청 판별 — 직전 초안과 수정 지시문이 함께 오면 "처음부터 다시"가 아니라
    // 직전 계획을 assistant 턴으로 이어 붙여 고쳐 달라고 멀티턴으로 지시한다.
    public boolean isRefinement() {
        return refinementPrompt != null && !refinementPrompt.isBlank()
                && previousTasks != null && !previousTasks.isEmpty();
    }
}
