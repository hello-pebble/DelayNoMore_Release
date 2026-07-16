package com.delaynomore.backend.domain.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

// 자유 대화 응답: 산문 reply + 계획 변경 여부. patch는 변경된 날짜만 담고, 값이 null인 날짜는 삭제를 뜻한다.
public record AiChatResponse(
        String reply,
        boolean planUpdated,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> patch
) {

    public static AiChatResponse replyOnly(String reply) {
        return new AiChatResponse(reply, false, null);
    }

    public static AiChatResponse withPatch(String reply, Map<String, Object> patch) {
        return new AiChatResponse(reply, true, patch);
    }
}
