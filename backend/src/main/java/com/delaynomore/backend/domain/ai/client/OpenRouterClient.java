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
        try {
            String responseBody = openRouterRestClient.post()
                    .uri(COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildBody(messages, maxTokens, false))
                    .retrieve()
                    .body(String.class);
            if (responseBody == null) {
                throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
            }
            JsonNode root = jsonMapper.readTree(responseBody);
            return root.path("choices").path(0).path("message").path("content").asString("");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling OpenRouter", e);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
    }

    // 스트리밍 호출 — 업스트림 SSE를 라인 단위로 읽어 content 델타만 onDelta로 넘긴다.
    public void streamCompletion(List<Map<String, Object>> messages, int maxTokens, DeltaConsumer onDelta) {
        openRouterRestClient.post()
                .uri(COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(buildBody(messages, maxTokens, true))
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
    private Map<String, Object> buildBody(List<Map<String, Object>> messages, int maxTokens, boolean stream) {
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
        return body;
    }
}
