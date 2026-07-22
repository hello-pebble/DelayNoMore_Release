package com.delaynomore.backend.domain.ai.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;
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
        Map<String, Object> previousTasks,

        // 다음 계획 분량 추천 경로 전용 — 지정 시 프롬프트가 "하루 정확히 N개"를 요구하고 서버가
        // 응답의 날짜별 개수를 검증한다(불일치 → AI_RESPONSE_INVALID). null이면 기존 동작
        // (dailyHours 비례 범위) 그대로 — 완전 하위호환.
        @Min(value = 1, message = "하루 할 일 개수는 1~5개여야 합니다.")
        @Max(value = 5, message = "하루 할 일 개수는 1~5개여야 합니다.")
        Integer tasksPerDay
) {

    // 초안 생성 상한(일) — TemplatePlanGenerator.MAX_DURATION과 같은 값. 원본 duration이 이월로
    // 14를 넘겼을 수 있으므로, 다음 계획은 다루기 좋은 짧은 지평선으로 클램프한다.
    private static final int MAX_DURATION = 14;

    // 추천 흐름의 초안 생성 요청 — 원본 계획에서 목표·기간·수준을 승계하고 선택된 분량만 지정한다
    // (@Valid를 거치지 않는 내부 생성 경로이므로 duration을 여기서 안전 범위로 클램프한다).
    public static AiDraftRequest fromSource(Plan source, int tasksPerDay) {
        int duration = Math.max(1, Math.min(MAX_DURATION, source.duration() == null ? 1 : source.duration()));
        return new AiDraftRequest(source.goalName(), duration, source.dailyHours(),
                source.currentLevel(), null, null, tasksPerDay);
    }

    // 재수정 요청 판별 — 직전 초안과 수정 지시문이 함께 오면 "처음부터 다시"가 아니라
    // 직전 계획을 assistant 턴으로 이어 붙여 고쳐 달라고 멀티턴으로 지시한다.
    public boolean isRefinement() {
        return refinementPrompt != null && !refinementPrompt.isBlank()
                && previousTasks != null && !previousTasks.isEmpty();
    }
}
