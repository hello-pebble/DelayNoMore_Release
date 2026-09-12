package com.delaynomore.backend.domain.ai.client;

import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.ai.usage.AiRateLimiter;
import com.delaynomore.backend.domain.ai.usage.AiUsageLogger;
import com.delaynomore.backend.domain.ai.usage.TokenUsage;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenRouter 게이트웨이. HTTP 전송(요청 바디 조립·SSE 파싱·tool_calls 추출)은 LangChain4j에
 * 맡기고, 이 클래스는 <b>이 서비스 고유의 계약</b>만 담당한다: 호출부가 쓰는 메시지·도구 형식
 * (OpenAI 호환 Map — 프롬프트 조립부와 도구 카탈로그 API가 그대로 쓴다)을 LangChain4j 타입으로
 * 변환하고, 호출 라벨({@link AiCallSite})별 토큰 사용량을 계측한다. 프롬프트 조립과 응답
 * 해석(정제·파싱)은 여전히 Service 쪽(AiPromptBuilder/AiResponseParser)의 몫이다.
 *
 * <p>토큰 사용량 계측이 여기 집중되는 이유는 전과 같다 — 모든 업스트림 호출이 이 클래스를
 * 지나므로, 호출부가 각자 세는 것보다 여기서 한 번 세는 편이 빠뜨릴 여지가 없다.
 * <b>전역 일일 상한(v0.30.0)도 같은 이유로 여기 있다</b> — 진입점마다 거는 대신 이 한 곳에
 * 두면 레거시 경로(/chats·/drafts)든 나중에 생길 경로든 자동으로 덮인다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenRouterClient {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final RestClient openRouterRestClient;
    private final OpenRouterProperties properties;
    private final AiUsageLogger usageLogger;
    private final AiRateLimiter rateLimiter;

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

    // 키 점검은 LLM 호출이 아니라 LangChain4j 밖이다 — 기존 RestClient로 그대로 확인한다.
    public KeyCheck checkKey() {
        try {
            openRouterRestClient.get().uri("/auth/key").retrieve().toBodilessEntity();
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
     * 스트리밍이 아닌 이유: 도구 호출 인자는 다 모이기 전에는 실행할 수 없다. 루프의 중간 턴은
     * 비스트리밍으로 받고, 사용자가 기다리는 동안의 체감은 도구 호출 진행 상황을 SSE로 채운다.
     */
    public Completion completeWithTools(AiCallSite site, List<Map<String, Object>> messages, int maxTokens,
                                        List<Map<String, Object>> tools) {
        requireDailyBudget(site);
        try {
            ChatResponse response = chatModel.chat(buildRequest(messages, maxTokens, tools));
            TokenUsage usage = toDomainUsage(response.metadata().tokenUsage());
            usageLogger.record(site, usage);
            AiMessage message = response.aiMessage();
            String content = message.text() == null ? "" : message.text();
            return new Completion(content, extractToolCalls(message), usage);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling OpenRouter", e);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
    }

    /**
     * 스트리밍 호출 — content 델타만 onDelta로 넘긴다. LangChain4j 콜백은 별도 스레드에서 오지만
     * 호출부는 블로킹 servlet SSE 릴레이라 완료까지 기다리는 동기 브리지를 둔다.
     *
     * <p>사용량은 스트림이 끝나야 알 수 있으므로 완료 후 기록한다. 중간에 끊기면(업스트림 오류,
     * 프론트 연결 종료) 그 요청의 사용량은 기록하지 않는다 — 추정치를 지어내기보다 비워 두는
     * 쪽을 택한 기존 정책 그대로다.
     */
    public void streamCompletion(AiCallSite site, List<Map<String, Object>> messages, int maxTokens,
                                 DeltaConsumer onDelta) {
        requireDailyBudget(site);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.EMPTY);

        streamingChatModel.chat(buildRequest(messages, maxTokens, null), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String delta) {
                if (failure.get() != null || delta == null || delta.isEmpty()) {
                    return; // 이미 실패했으면(프론트 연결 종료 등) 남은 델타는 버린다
                }
                try {
                    onDelta.accept(delta);
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                usage.set(toDomainUsage(response.metadata().tokenUsage()));
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                failure.compareAndSet(null, error);
                done.countDown();
            }
        });

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }

        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof IOException io) {
                throw new UncheckedIOException(io); // 프론트 연결 종료 — 호출부의 실패 경로가 처리한다
            }
            log.error("Error streaming from OpenRouter", error);
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
        }
        // 스위치를 끄면 계측만 생략한다. 예전에는 요청의 stream_options 자체를 뺐지만, 그 필드는
        // 이제 LangChain4j가 관리한다 — 탈출구의 목적(이상 동작 시 로그 오염 방지)은 유지된다.
        if (properties.isStreamUsageEnabled()) {
            usageLogger.record(site, usage.get());
        }
    }

    /**
     * 전역 일일 상한 확인(v0.30.0). 한도를 넘으면 업스트림을 부르지 않고 429로 끊는다 —
     * 호출부는 이 예외를 기존 실패 경로로 받아 mock 폴백까지 수렴하므로 화면은 멈추지 않는다.
     * 소진은 운영자가 알아야 하는 사건이라 WARN으로 남긴다(정상 호출은 ai.usage INFO).
     */
    private void requireDailyBudget(AiCallSite site) {
        if (!rateLimiter.tryAcquireGlobal()) {
            AiRateLimiter.Snapshot snapshot = rateLimiter.snapshot();
            log.warn("ai.ratelimit blocked scope=global site={} used={} limit={}",
                    site.label(), snapshot.globalUsed(), snapshot.globalLimit());
            throw new BusinessException(ErrorCode.AI_DAILY_LIMIT_EXCEEDED);
        }
    }

    // ── OpenAI 호환 Map ↔ LangChain4j 타입 변환 ─────────────────────────────────────────

    private ChatRequest buildRequest(List<Map<String, Object>> messages, int maxTokens,
                                     List<Map<String, Object>> tools) {
        ChatRequest.Builder builder = ChatRequest.builder().messages(toChatMessages(messages));
        if (maxTokens > 0) {
            builder.maxOutputTokens(maxTokens);
        }
        if (tools != null && !tools.isEmpty()) {
            // tool_choice는 기본(auto)에 맡긴다 — 노출 자체를 계획 상태로 제한하고 있으므로
            // 여기서 더 조일 필요가 없다.
            builder.toolSpecifications(toSpecifications(tools));
        }
        return builder.build();
    }

    /**
     * 호출부의 OpenAI 호환 메시지(Map)를 LangChain4j 메시지로 옮긴다. 호출부(프롬프트 조립부·
     * 에이전트 루프)의 형식을 바꾸지 않기 위한 변환이다 — 대화 이력을 되돌려 넣는 assistant
     * tool_calls 턴과 tool 결과 턴까지 네 가지 role을 전부 다룬다.
     */
    @SuppressWarnings("unchecked")
    private static List<ChatMessage> toChatMessages(List<Map<String, Object>> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            String role = String.valueOf(message.get("role"));
            String content = message.get("content") == null ? "" : String.valueOf(message.get("content"));
            switch (role) {
                case "system" -> result.add(SystemMessage.from(content));
                case "user" -> result.add(UserMessage.from(content));
                case "assistant" -> {
                    List<Map<String, Object>> calls = (List<Map<String, Object>>) message.get("tool_calls");
                    if (calls == null || calls.isEmpty()) {
                        result.add(AiMessage.from(content));
                    } else {
                        result.add(AiMessage.builder()
                                .text(content)
                                .toolExecutionRequests(calls.stream().map(call -> {
                                    Map<String, Object> function = (Map<String, Object>) call.get("function");
                                    return ToolExecutionRequest.builder()
                                            .id(String.valueOf(call.get("id")))
                                            .name(String.valueOf(function.get("name")))
                                            .arguments(String.valueOf(function.get("arguments")))
                                            .build();
                                }).toList())
                                .build());
                    }
                }
                case "tool" -> result.add(ToolExecutionResultMessage.from(
                        String.valueOf(message.get("tool_call_id")), null, content));
                default -> result.add(UserMessage.from(content));
            }
        }
        return result;
    }

    // 모델의 도구 호출 요청 → ToolCall 목록. 이름 없는 호출은 버린다(실행할 수 없다).
    // id가 없는 모델도 있다 — tool 응답 메시지를 짝지으려면 반드시 있어야 하므로 합성한다.
    private static List<ToolCall> extractToolCalls(AiMessage message) {
        if (!message.hasToolExecutionRequests()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            String name = request.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            String id = request.id();
            if (id == null || id.isBlank()) {
                id = "call_" + result.size();
            }
            String arguments = request.arguments();
            result.add(new ToolCall(id, name, (arguments == null || arguments.isBlank()) ? "{}" : arguments));
        }
        return result;
    }

    // 도구 카탈로그의 OpenAI function 스펙(Map — AgentToolRegistry.specsFor가 만들고 카탈로그
    // API도 같은 것을 내린다)을 LangChain4j 스펙으로 옮긴다. 권한 필터링은 레지스트리가 이미
    // 끝냈다 — 여기서는 형식만 바꾼다.
    @SuppressWarnings("unchecked")
    private static List<ToolSpecification> toSpecifications(List<Map<String, Object>> tools) {
        List<ToolSpecification> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            result.add(ToolSpecification.builder()
                    .name(String.valueOf(function.get("name")))
                    .description(String.valueOf(function.get("description")))
                    .parameters(toObjectSchema((Map<String, Object>) function.get("parameters")))
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static JsonObjectSchema toObjectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (schema.get("description") instanceof String description) {
            builder.description(description);
        }
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        properties.forEach((name, sub) -> builder.addProperty(name, toElement((Map<String, Object>) sub)));
        if (schema.get("required") instanceof List<?> required && !required.isEmpty()) {
            builder.required((List<String>) required);
        }
        return builder.build();
    }

    // ponytail: 현재 도구 스키마는 string·object뿐 — 다른 타입(integer·array·enum 등)을 쓰기
    // 시작하면 case를 추가한다.
    private static JsonSchemaElement toElement(Map<String, Object> schema) {
        String description = schema.get("description") instanceof String d ? d : null;
        return switch (String.valueOf(schema.getOrDefault("type", "string"))) {
            case "object" -> toObjectSchema(schema);
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    // LangChain4j 사용량 → 도메인 값 객체. cost는 OpenRouter 확장 필드라 LangChain4j가 내려주지
    // 않는다 — 기존에도 usage accounting을 켜지 않아 항상 null이었으므로 실질 변화는 없다.
    private static TokenUsage toDomainUsage(dev.langchain4j.model.output.TokenUsage usage) {
        if (usage == null) {
            return TokenUsage.EMPTY;
        }
        int prompt = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int completion = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        int total = usage.totalTokenCount() == null ? prompt + completion : usage.totalTokenCount();
        return new TokenUsage(prompt, completion, total, null);
    }
}
