package com.delaynomore.backend.domain.ai.client;

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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenRouterClient {

    private final RestClient openRouterRestClient;
    private final OpenRouterProperties properties;
    private final JsonMapper jsonMapper;

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
    public record Completion(String content, List<ToolCall> toolCalls) {

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
    public String complete(List<Map<String, Object>> messages, int maxTokens) {
        return completeWithTools(messages, maxTokens, null).content();
    }

    /**
     * 도구 목록을 함께 보내는 비스트리밍 호출(에이전트 루프용). tools가 null·빈 목록이면 기존
     * complete()와 완전히 같은 요청이 나간다 — 기존 경로의 동작을 바꾸지 않기 위해서다.
     *
     * 스트리밍이 아닌 이유: 도구 호출 인자는 델타로 쪼개져 오고 인덱스별로 이어 붙여야 완성되는데,
     * 어차피 인자가 다 모이기 전에는 도구를 실행할 수 없다. 루프의 중간 턴은 비스트리밍으로 받고,
     * 사용자가 기다리는 동안의 체감은 도구 호출 진행 상황을 SSE로 흘려보내 채운다.
     */
    public Completion completeWithTools(List<Map<String, Object>> messages, int maxTokens,
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
            JsonNode message = jsonMapper.readTree(responseBody).path("choices").path(0).path("message");
            return new Completion(message.path("content").asString(""), extractToolCalls(message));
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
    public void streamCompletion(List<Map<String, Object>> messages, int maxTokens, DeltaConsumer onDelta) {
        openRouterRestClient.post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(buildBody(messages, maxTokens, true, null))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
                    }
                    relayDeltas(response, onDelta);
                    return null;
                });
    }

    private void relayDeltas(ClientHttpResponse response, DeltaConsumer onDelta) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue; // 빈 줄/주석(: OPENROUTER PROCESSING)
                if (!line.startsWith(SSE_DATA_PREFIX)) continue;
                String payload = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (SSE_DONE.equals(payload)) break;
                String delta = extractDelta(payload);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        }
    }

    private String extractDelta(String payload) {
        try {
            JsonNode node = jsonMapper.readTree(payload);
            return node.path("choices").path(0).path("delta").path("content").asString("");
        } catch (Exception e) {
            return null; // keep-alive/부분 라인 등은 무시
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
