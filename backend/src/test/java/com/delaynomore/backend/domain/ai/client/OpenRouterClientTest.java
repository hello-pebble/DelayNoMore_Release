package com.delaynomore.backend.domain.ai.client;

import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.ai.usage.AiUsageLogger;
import com.delaynomore.backend.domain.ai.usage.TokenUsage;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 토큰 사용량 계측의 계약. 업스트림 응답을 가짜 서버로 고정해 "이렇게 오면 이렇게 센다"를 검증한다.
 *
 * <p>스트리밍이 특히 중요하다 — 비스트리밍과 달리 usage가 본문에 없고 <b>맨 끝 청크로만</b> 오며,
 * 그 청크는 {@code choices}가 빈 배열이라 델타 추출 경로와 겹친다. 잘못 다루면 사용량을 놓치거나
 * 반대로 빈 토큰이 화면으로 샌다.
 */
class OpenRouterClientTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AiUsageLogger usageLogger = mock(AiUsageLogger.class);

    private MockRestServiceServer server;

    private OpenRouterClient clientWith(boolean streamUsage) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.example");
        server = MockRestServiceServer.bindTo(builder).build();
        OpenRouterProperties properties =
                new OpenRouterProperties("https://openrouter.example", "key", "test-model", true, streamUsage);
        return new OpenRouterClient(builder.build(), properties, jsonMapper, usageLogger);
    }

    private static String sse(String... payloads) {
        StringBuilder body = new StringBuilder();
        for (String payload : payloads) {
            body.append("data: ").append(payload).append("\n\n");
        }
        return body.append("data: [DONE]\n\n").toString();
    }

    @Test
    void 비스트리밍_응답의_usage를_기록하고_값으로도_돌려준다() {
        OpenRouterClient client = clientWith(true);
        server.expect(requestTo("https://openrouter.example/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"안녕하세요"}}],
                         "usage":{"prompt_tokens":812,"completion_tokens":143,"total_tokens":955}}""",
                        MediaType.APPLICATION_JSON));

        OpenRouterClient.Completion completion =
                client.completeWithTools(AiCallSite.CHAT, List.of(message()), 1200, null);

        // 값으로도 돌려주는 이유: 에이전트 루프가 턴별 값을 더해 요청 단위 합계를 내야 한다.
        assertThat(completion.usage()).isEqualTo(new TokenUsage(812, 143, 955, null));
        verify(usageLogger).record(AiCallSite.CHAT, new TokenUsage(812, 143, 955, null));
        server.verify();
    }

    @Test
    void 스트리밍_마지막_usage청크를_기록하되_화면으로는_흘리지_않는다() {
        OpenRouterClient client = clientWith(true);
        server.expect(requestTo("https://openrouter.example/chat/completions"))
                // 요청에 stream_options가 실려야 업스트림이 usage 청크를 준다
                .andExpect(jsonPath("$.stream_options.include_usage").value(true))
                .andRespond(withSuccess(sse(
                                "{\"choices\":[{\"delta\":{\"content\":\"오늘 \"}}]}",
                                "{\"choices\":[{\"delta\":{\"content\":\"1개 완료\"}}]}",
                                // usage 청크 — choices가 빈 배열이다
                                "{\"choices\":[],\"usage\":{\"prompt_tokens\":400,\"completion_tokens\":12,"
                                        + "\"total_tokens\":412}}"),
                        MediaType.TEXT_EVENT_STREAM));

        List<String> deltas = new ArrayList<>();
        client.streamCompletion(AiCallSite.CHAT_STREAM, List.of(message()), 1200, deltas::add);

        // usage 청크는 델타를 만들지 않는다 — 만들면 빈 token 이벤트가 프론트로 샌다
        assertThat(deltas).containsExactly("오늘 ", "1개 완료");
        verify(usageLogger).record(AiCallSite.CHAT_STREAM, new TokenUsage(400, 12, 412, null));
        server.verify();
    }

    @Test
    void 스트리밍_usage청크가_없으면_기록할것이없다() {
        // 업스트림이 stream_options를 무시하는 경우. 계측만 비고 스트리밍 자체는 정상이어야 한다.
        OpenRouterClient client = clientWith(true);
        server.expect(requestTo("https://openrouter.example/chat/completions"))
                .andRespond(withSuccess(sse("{\"choices\":[{\"delta\":{\"content\":\"안녕\"}}]}"),
                        MediaType.TEXT_EVENT_STREAM));

        List<String> deltas = new ArrayList<>();
        client.streamCompletion(AiCallSite.CHAT_STREAM, List.of(message()), 1200, deltas::add);

        assertThat(deltas).containsExactly("안녕");
        verify(usageLogger).record(AiCallSite.CHAT_STREAM, TokenUsage.EMPTY);
        server.verify();
    }

    @Test
    void 스트림usage스위치를_끄면_요청에_stream_options를_싣지않는다() {
        // 이 필드를 이상하게 처리하는 모델로 갈아끼웠을 때의 탈출구 — 끄면 요청 형태가 예전 그대로다.
        OpenRouterClient client = clientWith(false);
        server.expect(requestTo("https://openrouter.example/chat/completions"))
                .andExpect(jsonPath("$.stream_options").doesNotExist())
                .andRespond(withSuccess(sse("{\"choices\":[{\"delta\":{\"content\":\"안녕\"}}]}"),
                        MediaType.TEXT_EVENT_STREAM));

        client.streamCompletion(AiCallSite.CHAT_STREAM, List.of(message()), 1200, delta -> { });

        verify(usageLogger, never()).record(AiCallSite.CHAT_STREAM, new TokenUsage(400, 12, 412, null));
        server.verify();
    }

    private static Map<String, Object> message() {
        return Map.of("role", "user", "content", "안녕하세요");
    }
}
