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
        List<ChatTurn> history,

        // 보관된 계획 id — 에이전트 경로에서만 쓴다. 도구가 서버 저장본(완료율·회고·이력)을
        // 읽거나 도메인 액션(이월)을 부르려면 대상이 필요하기 때문이다. 아직 보관 전 초안이면
        // null이고, 그 경우 서버 데이터를 요구하는 도구는 실행 대신 사유를 돌려준다.
        // 기존 /chats·/chats/stream은 이 값을 무시한다(additive 필드).
        Long planId
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
