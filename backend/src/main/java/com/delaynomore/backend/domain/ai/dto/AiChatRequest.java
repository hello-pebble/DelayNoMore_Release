package com.delaynomore.backend.domain.ai.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

// 초안 생성 이후의 자유 대화 요청. message 외 필드는 컨텍스트용이라 없으면 기본값으로 보정한다.
public record AiChatRequest(
        String goalName,
        Integer duration,
        Integer dailyHours,
        String currentLevel,

        @NotBlank(message = "message가 비어 있습니다.")
        String message,

        // 현재 계획({날짜: [{id,content,completed}] | [문자열]})
        Map<String, Object> tasks,

        // 최근 대화 이력([{role, content}, ...])
        List<ChatTurn> history
) {

    public record ChatTurn(String role, String content) {
    }

    public int durationOrDefault() {
        return duration == null ? 1 : Math.max(1, duration);
    }

    public int dailyHoursOrDefault() {
        return dailyHours == null ? 0 : Math.max(0, dailyHours);
    }

    public List<ChatTurn> historyOrEmpty() {
        return history == null ? List.of() : history;
    }
}
