package com.delaynomore.backend.domain.ai.client;

import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.ai.usage.AiUsageLogger;
import com.delaynomore.backend.domain.ai.usage.TokenUsage;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter HTTP 게이트웨이. HTTP 호출·SSE 델타 추출까지만 담당하고,
 * 프롬프트 조립과 응답 해석(정제·파싱)은 Service 쪽(AiPromptBuilder/AiResponseParser)에 맡긴다.
 *
 * <p>토큰 사용량 계측도 여기서 한다 — 모든 업스트림 호출이 이 클래스를 지나므로, 호출부가
 * 각자 세는 것보다 여기서 한 번 세는 편이 빠뜨릴 여지가 없다. 그래서 모든 호출 메서드가
 * {@link AiCallSite}를 첫 인자로 받는다(어느 경로가 얼마나 쓰는지 구분하려면 호출부만 아는
 * 정보라 라벨을 넘겨받아야 한다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenRouterClient {

    private final RestClient openRouterRestClient;
    private final OpenRouterProperties properties;
    private final JsonMapper jsonMapper;
    private final AiUsageLogger usageLogger;

    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String KEY_CHECK_PATH = "/auth/key";
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";

    // 스트리밍 델타 소비자 — SSE 전송(IOException)을 그대로 던질 수 있게 별도 함수형 인터페이스로 둔다.
    @FunctionalInterface
    public interface DeltaConsumer {
        void accept(String delta) throws IOException;
    }

    // 키 점검 결과. connected=false면 failureReason에 화면 표시용 사유를 담는다.
    public record KeyCheck(boolean connected, String failureReason) {
    }

    // 모델이 요청한 도구 호출 하나. arguments는 모델이 만든 JSON 문자열 원문이라 이 계층에서는
    // 파싱하지 않는다(신뢰할 수 없는 입력의 해석은 도구 실행 계층의 책임).
    public record ToolCall(String id, String name, String argumentsJson) {
    }

    // 한 번의 완료 응답. 도구 호출이 있으면 toolCalls가 비어 있지 않고, 없으면 content가 최종 답이다.
    // 둘 다 올 수도 있다(모델이 짧은 안내와 함께 도구를 부르는 경우) — 루프는 toolCalls를 우선한다.
    // usage는 이 호출 한 번의 토큰 사용량이다. 에이전트 루프가 턴별 값을 더해 요청 단위 합계를
    // 내야 해서, 클라이언트가 로그만 남기고 버리지 않고 값으로도 돌려준다.
    public record Completion(String content, List<ToolCall> toolCalls, TokenUsage usage) {

        public Completion(String content, List<ToolCall> toolCalls) {
            this(content, toolCalls, TokenUsage.EMPTY);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    public KeyCheck checkKey() {
        try {
            openRouterRestClient.get().uri(KEY_CHECK_PATH).retrieve().toBodilessEntity();
            return new KeyCheck(true, null);
        } catch (RestClientResponseException e) {
            return new KeyCheck(false, "인증 오류 (" + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            log.warn("OpenRouter health check failed", e);
            return new KeyCheck(false, "네트워크 연결 오류");
        }
    }

    // 비스트리밍 호출 — assistant content 원문을 그대로 돌려준다(정제는 호출부에서).
    public String complete(AiCallSite site, List<Map<String, Object>> messages, int maxTokens) {
        return completeWithTools(site, messages, maxTokens, null).content();
    }

    /**
     * 도구 목록을 함께 보내는 비스트리밍 호출(에이전트 루프용). tools가 null·빈 목록이면 기존
     * complete()와 완전히 같은 요청이 나간다 — 기존 경로의 동작을 바꾸지 않기 위해서다.
     *
     * 스트리밍이 아닌 이유: 도구 호출 인자는 델타로 쪼개져 오고 인덱스별로 이어 붙여야 완성되는데,
     * 어차피 인자가 다 모이기 전에는 도구를 실행할 수 없다. 루프의 중간 턴은 비스트리밍으로 받고,
     * 사용자가 기다리는 동안의 체감은 도구 호출 진행 상황을 SSE로 흘려보내 채운다.
     */
    public Completion completeWithTools(AiCallSite site, List<Map<String, Object>> messages, int maxTokens,
                                        List<Map<String, Object>> tools) {
        try {
            String responseBody = openRouterRestClient.post()
                    .uri(COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildBody(messages, maxTokens, false, tools))
                    .retrieve()
                    .body(String.class);
            if (responseBody == null) {
                throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
            }
            JsonNode root = jsonMapper.readTree(responseBody);
            JsonNode message = root.path("choices").path(0).path("message");
            TokenUsage usage = TokenUsage.from(root.path("usage"));
            usageLogger.record(site, usage);
            return new Completion(message.path("content").asString(""), extractToolCalls(message), usage);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling OpenRouter", e);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
    }

    // message.tool_calls[] → ToolCall 목록. 필드가 없거나 형식이 어긋나면 빈 목록으로 본다
    // (도구 미지원 모델이 이 필드를 아예 안 내려주므로, 없음을 정상 경로로 다뤄야 폴백이 작동한다).
    private static List<ToolCall> extractToolCalls(JsonNode message) {
        JsonNode calls = message.path("tool_calls");
        if (!calls.isArray() || calls.isEmpty()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            String name = function.path("name").asString("");
            if (name.isBlank()) {
                continue;
            }
            // id가 없는 모델도 있다 — tool 응답 메시지를 짝지으려면 반드시 있어야 하므로 합성한다.
            String id = call.path("id").asString("");
            if (id.isBlank()) {
                id = "call_" + result.size();
            }
            result.add(new ToolCall(id, name, function.path("arguments").asString("{}")));
        }
        return result;
    }

    // 스트리밍 호출 — 업스트림 SSE를 라인 단위로 읽어 content 델타만 onDelta로 넘긴다.
    // 사용량은 스트림 맨 끝의 usage 청크에서만 오므로(stream_options.include_usage), 릴레이가
    // 끝난 뒤에 기록한다. 중간에 예외로 끊기면 그 요청의 사용량은 알 수 없다 — 업스트림이 아직
    // 안 보냈기 때문이라, 추정치를 지어내기보다 기록을 남기지 않는 쪽을 택했다.
    public void streamCompletion(AiCallSite site, List<Map<String, Object>> messages, int maxTokens,
                                 DeltaConsumer onDelta) {
        openRouterRestClient.post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(buildBody(messages, maxTokens, true, null))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
                    }
                    usageLogger.record(site, relayDeltas(response, onDelta));
                    return null;
                });
    }

    // 델타를 흘려보내면서 usage 청크를 골라 담는다. usage 청크는 choices가 빈 배열이라
    // extractDelta가 자연스럽게 빈 문자열을 돌려주고, 프론트로는 아무것도 새지 않는다.
    private TokenUsage relayDeltas(ClientHttpResponse response, DeltaConsumer onDelta) throws IOException {
        TokenUsage usage = TokenUsage.EMPTY;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue; // 빈 줄/주석(: OPENROUTER PROCESSING)
                if (!line.startsWith(SSE_DATA_PREFIX)) continue;
                String payload = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (SSE_DONE.equals(payload)) break;
                TokenUsage chunkUsage = extractUsage(payload);
                if (!chunkUsage.isEmpty()) {
                    usage = chunkUsage; // 누적이 아니라 교체 — 업스트림이 내려주는 값이 이미 누계다
                }
                String delta = extractDelta(payload);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        }
        return usage;
    }

    private String extractDelta(String payload) {
        try {
            JsonNode node = jsonMapper.readTree(payload);
            return node.path("choices").path(0).path("delta").path("content").asString("");
        } catch (Exception e) {
            return null; // keep-alive/부분 라인 등은 무시
        }
    }

    // 스트림 청크에서 usage를 읽는다. 대부분의 청크에는 없으므로 없음이 정상 경로다.
    private TokenUsage extractUsage(String payload) {
        try {
            return TokenUsage.from(jsonMapper.readTree(payload).path("usage"));
        } catch (Exception e) {
            return TokenUsage.EMPTY;
        }
    }

    // 공통 요청 바디 조립. maxTokens<=0 이면 상한 없음, stream이면 SSE 스트리밍을 켠다.
    // tools가 비어 있지 않으면 function calling을 켠다 — 기존 두 경로(초안·자유 대화)는 null을
    // 넘겨 요청 형태가 예전과 한 글자도 달라지지 않는다.
    private Map<String, Object> buildBody(List<Map<String, Object>> messages, int maxTokens, boolean stream,
                                          List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        // 추론(thinking) 계열 모델의 사고를 끈다 — 이 용도엔 불필요하고, 켜두면 응답이 수십 초 걸리고
        // 사고 텍스트가 섞여 파싱을 방해한다. 지원하지 않는 모델은 이 값을 무시한다.
        body.put("reasoning", Map.of("enabled", false));
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        if (stream) {
            body.put("stream", true);
            // 스트리밍은 마지막에 usage 청크를 따로 요청해야 사용량을 알 수 있다(비스트리밍은 응답
            // 본문에 항상 들어 있다). OpenAI 호환 필드지만 업스트림 모델에 따라 무시될 수 있어,
            // 이상 동작 시 코드 배포 없이 끌 수 있게 스위치를 뒀다 — tool-calling과 같은 방식.
            if (properties.isStreamUsageEnabled()) {
                body.put("stream_options", Map.of("include_usage", true));
            }
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            // auto — 도구를 쓸지 말지는 모델이 정한다. 단순 인사에도 도구를 부르게 강제하면
            // 왕복만 늘어난다. 노출 자체를 상태로 제한하고 있으므로 여기서 더 조일 필요가 없다.
            body.put("tool_choice", "auto");
        }
        return body;
    }
}
