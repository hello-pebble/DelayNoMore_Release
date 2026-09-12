package com.delaynomore.backend.domain.ai.client;

import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.ai.usage.AiUsageLogger;
import com.delaynomore.backend.domain.ai.usage.TokenUsage;
import com.delaynomore.backend.global.config.LangChainConfig;
import com.delaynomore.backend.domain.ai.usage.AiRateLimiter;
import com.delaynomore.backend.global.config.AiRateLimitProperties;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 전송 계약 검증. 전송 자체는 LangChain4j의 몫이 됐지만, <b>무엇이 요청에 실리고 무엇이 계측되는가</b>는
 * 여전히 이 서비스의 계약이다 — 가짜 업스트림(JDK HttpServer)으로 실제 HTTP를 주고받아 확인한다.
 *
 * <p>특히 reasoning off가 요청 바디에 실리는지가 중요하다 — 안 실리면 추론 계열 모델의 사고
 * 텍스트가 응답에 섞이고 수십 초 지연이 생긴다(마이그레이션의 중단 판단 기준이었던 항목).
 */
class OpenRouterClientTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AiUsageLogger usageLogger = mock(AiUsageLogger.class);

    private HttpServer server;
    private volatile String lastRequestBody;
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenRouterClient clientWith(boolean streamUsage, String contentType, String responseBody)
            throws IOException {
        // 한도를 끈 리미터 — 이 테스트들의 관심사는 전송 계약이다(한도는 아래 전용 테스트가 본다).
        return clientWith(streamUsage, contentType, responseBody,
                new AiRateLimiter(new AiRateLimitProperties(0, 0)));
    }

    private OpenRouterClient clientWith(boolean streamUsage, String contentType, String responseBody,
                                        AiRateLimiter rateLimiter) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            upstreamCalls.incrementAndGet();
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        OpenRouterProperties properties = new OpenRouterProperties(baseUrl, "key", "test-model", true, streamUsage);
        // 운영과 같은 구성으로 모델을 만든다 — 설정(LangChainConfig)이 곧 검증 대상이다.
        return new OpenRouterClient(
                LangChainConfig.chatModel(properties),
                LangChainConfig.streamingChatModel(properties),
                RestClient.builder().baseUrl(baseUrl).build(),
                properties,
                usageLogger,
                rateLimiter);
    }

    private JsonNode requestBody() {
        return jsonMapper.readTree(lastRequestBody);
    }

    private static String sse(String... payloads) {
        StringBuilder body = new StringBuilder();
        for (String payload : payloads) {
            body.append("data: ").append(payload).append("\n\n");
        }
        return body.append("data: [DONE]\n\n").toString();
    }

    @Test
    void 비스트리밍_usage를_기록하고_값으로도_돌려주며_reasoning_off가_요청에_실린다() throws IOException {
        OpenRouterClient client = clientWith(true, "application/json", """
                {"id":"c1","object":"chat.completion","created":1,"model":"test-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"안녕하세요"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":812,"completion_tokens":143,"total_tokens":955}}""");

        OpenRouterClient.Completion completion =
                client.completeWithTools(AiCallSite.CHAT, List.of(message()), 1200, null);

        assertThat(completion.content()).isEqualTo("안녕하세요");
        // 값으로도 돌려주는 이유: 에이전트 루프가 턴별 값을 더해 요청 단위 합계를 내야 한다.
        assertThat(completion.usage()).isEqualTo(new TokenUsage(812, 143, 955, null));
        verify(usageLogger).record(AiCallSite.CHAT, new TokenUsage(812, 143, 955, null));
        // reasoning off — LangChain4j 표준 필드가 아니라 customParameters로 실었으므로 실제로
        // 바디에 나가는지를 여기서 증명해야 한다.
        assertThat(requestBody().path("reasoning").path("enabled").asBoolean(true)).isFalse();
        assertThat(requestBody().path("messages").path(0).path("content").asString()).isEqualTo("안녕하세요");
    }

    @Test
    void 도구스펙을_요청에_싣고_id없는_도구호출에는_id를_합성한다() throws IOException {
        OpenRouterClient client = clientWith(true, "application/json", """
                {"id":"c2","object":"chat.completion","created":1,"model":"test-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,
                   "tool_calls":[{"type":"function","function":{"name":"get_today_tasks","arguments":"{}"}}]},
                   "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""");

        // AgentToolRegistry.specsFor가 만드는 것과 같은 OpenAI function 형식.
        List<Map<String, Object>> tools = List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_today_tasks",
                        "description", "오늘 할 일 조회",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of("date", Map.of(
                                        "type", "string",
                                        "description", "Target date in YYYY-MM-DD.")),
                                "required", List.of()))));

        OpenRouterClient.Completion completion =
                client.completeWithTools(AiCallSite.AGENT_TURN, List.of(message()), 1200, tools);

        // id가 없는 모델(qwen 등)도 tool 응답 메시지를 짝지을 수 있어야 한다.
        assertThat(completion.toolCalls())
                .containsExactly(new OpenRouterClient.ToolCall("call_0", "get_today_tasks", "{}"));
        JsonNode toolNode = requestBody().path("tools").path(0).path("function");
        assertThat(toolNode.path("name").asString()).isEqualTo("get_today_tasks");
        assertThat(toolNode.path("parameters").path("properties").path("date").path("type").asString())
                .isEqualTo("string");
    }

    @Test
    void 스트리밍_마지막_usage청크를_기록하되_화면으로는_흘리지_않는다() throws IOException {
        OpenRouterClient client = clientWith(true, "text/event-stream", sse(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"오늘 \"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"1개 완료\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                // usage 청크 — choices가 빈 배열이다
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":400,\"completion_tokens\":12,\"total_tokens\":412}}"));

        List<String> deltas = new ArrayList<>();
        client.streamCompletion(AiCallSite.CHAT_STREAM, List.of(message()), 1200, deltas::add);

        // usage 청크는 델타를 만들지 않는다 — 만들면 빈 token 이벤트가 프론트로 샌다
        assertThat(deltas).containsExactly("오늘 ", "1개 완료");
        verify(usageLogger).record(AiCallSite.CHAT_STREAM, new TokenUsage(400, 12, 412, null));
    }

    @Test
    void 스트리밍_usage청크가_없으면_빈값을_기록한다() throws IOException {
        // 업스트림이 include_usage를 무시하는 경우. 계측만 비고 스트리밍 자체는 정상이어야 한다.
        OpenRouterClient client = clientWith(true, "text/event-stream", sse(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"안녕\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"));

        List<String> deltas = new ArrayList<>();
        client.streamCompletion(AiCallSite.CHAT_STREAM, List.of(message()), 1200, deltas::add);

        assertThat(deltas).containsExactly("안녕");
        verify(usageLogger).record(AiCallSite.CHAT_STREAM, TokenUsage.EMPTY);
    }

    @Test
    void 스트림usage스위치를_끄면_기록하지_않는다() throws IOException {
        // 탈출구의 의미가 바뀌었다: 예전에는 요청의 stream_options 자체를 뺐지만 그 필드는 이제
        // LangChain4j가 관리한다. 스위치는 이상 동작 시 로그 오염을 막는 계측 차단으로 남는다.
        OpenRouterClient client = clientWith(false, "text/event-stream", sse(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"안녕\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":400,\"completion_tokens\":12,\"total_tokens\":412}}"));

        client.streamCompletion(AiCallSite.CHAT_STREAM, List.of(message()), 1200, delta -> { });

        verify(usageLogger, never()).record(eq(AiCallSite.CHAT_STREAM), any());
    }

    @Test
    void 전역_일일_상한을_넘기면_업스트림을_부르지_않고_막는다() throws IOException {
        // 상한 1 — 첫 호출만 실제로 나가고, 두 번째는 네트워크 이전에 끊긴다(비용 방어선의 정의).
        OpenRouterClient client = clientWith(true, "application/json", """
                {"id":"c1","object":"chat.completion","created":1,"model":"test-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"안녕하세요"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                new AiRateLimiter(new AiRateLimitProperties(0, 1)));

        client.completeWithTools(AiCallSite.CHAT, List.of(message()), 1200, null);

        assertThatThrownBy(() -> client.completeWithTools(AiCallSite.CHAT, List.of(message()), 1200, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_DAILY_LIMIT_EXCEEDED);
        // 스트리밍 경로도 같은 상한을 지난다 — 진입점이 늘어도 여기 하나로 덮인다.
        assertThatThrownBy(() -> client.streamCompletion(AiCallSite.CHAT_STREAM, List.of(message()), 1200, d -> { }))
                .isInstanceOf(BusinessException.class);
        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    private static Map<String, Object> message() {
        return Map.of("role", "user", "content", "안녕하세요");
    }
}
