package com.delaynomore.backend.domain.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// 프론트 헤더의 AI 연결 상태 LED용 점검 결과. connected=false면 reason에 한국어 사유를 담는다.
public record AiHealthResponse(
        boolean connected,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason
) {

    public static AiHealthResponse up() {
        return new AiHealthResponse(true, null);
    }

    public static AiHealthResponse down(String reason) {
        return new AiHealthResponse(false, reason);
    }
}
