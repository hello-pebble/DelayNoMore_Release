package com.delaynomore.backend.domain.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// 프론트 헤더의 AI 연결 상태 LED용 점검 결과. connected=false면 reason에 한국어 사유를 담는다.
// toolCalling은 에이전트(도구 호출) 경로를 쓸 수 있는지 — 프론트가 이 값을 보고 에이전트
// 엔드포인트를 쓸지 기존 자유 대화 경로를 쓸지 고른다(additive 필드, 기존 계약 불변).
public record AiHealthResponse(
        boolean connected,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
        boolean toolCalling
) {

    public static AiHealthResponse up(boolean toolCalling) {
        return new AiHealthResponse(true, null, toolCalling);
    }

    // 연결이 안 되면 도구 호출도 불가능하다 — 사유와 함께 항상 toolCalling=false.
    public static AiHealthResponse down(String reason) {
        return new AiHealthResponse(false, reason, false);
    }
}
