package com.delaynomore.backend.domain.ai.usage;

/**
 * LLM을 호출하는 지점. 로그의 {@code site=} 라벨이 되며, 이 값으로 집계해야 "에이전트 경로가
 * 예전 자유 대화보다 토큰을 몇 배 쓰는가" 같은 질문에 답할 수 있다.
 *
 * <p>자유 문자열 대신 enum으로 둔 이유는 오타로 라벨이 갈라지면 집계가 조용히 틀리기 때문이다
 * (로그 집계는 컴파일러가 봐주지 않는 영역이라 이름을 코드로 고정한다).
 */
public enum AiCallSite {

    /** 계획 초안 생성(비스트리밍). */
    DRAFT("draft"),
    /** 계획 초안 생성(스트리밍) — 하루 한 줄 NDJSON. */
    DRAFT_STREAM("draft.stream"),
    /** 자유 대화(비스트리밍) — 에이전트 이전 경로. */
    CHAT("chat"),
    /** 자유 대화 스트리밍 — 에이전트의 1차 폴백이자 토큰 비교 기준선. */
    CHAT_STREAM("chat.stream"),
    /** 에이전트 루프의 한 턴(도구 목록을 함께 보내는 호출). */
    AGENT_TURN("agent.turn"),
    /** 루프 상한에 닿아 도구 없이 산문을 강제하는 마지막 호출. */
    AGENT_FINAL("agent.final"),
    /** 에이전트 요청 하나의 합계(턴별 호출을 모두 더한 값) — 개별 호출이 아니라 집계 라벨이다. */
    AGENT_TOTAL("agent.total"),
    /** 분량 추천 이유 문장 생성. */
    RECOMMENDATION_REASON("recommendation.reason");

    private final String label;

    AiCallSite(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
