package com.delaynomore.backend.domain.ai.agent;

import java.io.IOException;
import java.util.Map;

/**
 * 에이전트 루프가 진행 상황을 흘려보내는 출구. 루프에서 SSE를 분리하는 이음매다 —
 * 루프는 "무슨 일이 일어났는지"만 알리고, 그것을 SseEmitter로 보낼지 테스트용 리스트에
 * 모을지는 호출부가 정한다.
 *
 * IOException을 그대로 던지는 이유는 {@code OpenRouterClient.DeltaConsumer}와 같다 —
 * SSE 전송 실패는 삼키면 안 되는 신호라 루프 밖으로 올라가야 한다.
 */
@FunctionalInterface
public interface AgentEventSink {

    void emit(Map<String, Object> event) throws IOException;
}
