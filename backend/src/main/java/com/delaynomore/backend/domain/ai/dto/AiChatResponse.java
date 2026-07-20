package com.delaynomore.backend.domain.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

// 자유 대화 응답: 산문 reply + 계획 변경 여부. tasks는 LLM patch(변경된 날짜만)를 서버가 현재
// 계획에 병합한 정규화된 전체 계획({id, content, completed} 객체) — 프론트는 채택만 한다.
// 예전엔 patch(변경분만)를 내려보내 프론트가 병합했지만, 병합 규칙의 소유권을 서버로 옮겼다.
public record AiChatResponse(
        String reply,
        boolean planUpdated,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> tasks
) {

    public static AiChatResponse replyOnly(String reply) {
        return new AiChatResponse(reply, false, null);
    }

    public static AiChatResponse withTasks(String reply, Map<String, Object> tasks) {
        return new AiChatResponse(reply, true, tasks);
    }
}
