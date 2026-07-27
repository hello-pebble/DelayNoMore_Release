package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentEventSink;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.AgentToolRegistry;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.ai.client.OpenRouterClient;
import com.delaynomore.backend.domain.ai.dto.AiChatRequest;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * 에이전트 루프 — "모델이 도구를 고르고, 서버가 실행하고 검증한다"를 구현한 곳.
 *
 * <pre>
 *   프롬프트 → LLM(+상태별 도구 목록)
 *      ├─ tool_calls 있음 → 서버가 실행 → 결과를 tool 메시지로 붙여 다시 LLM  (최대 4턴)
 *      └─ tool_calls 없음 → 그 content가 최종 답
 * </pre>
 *
 * 기존 {@link AiService#streamChat}과의 차이는 계획 변경 방식뿐이다. 예전에는 모델이 산문 뒤에
 * ===PLAN=== 구분자와 patch JSON을 붙였고 서버가 그 문자열을 갈라 파싱했다(구분자가 델타 경계에
 * 걸리지 않게 홀드하는 상태머신까지 필요했다). 이제는 도구 스키마가 그 계약을 대신하므로
 * 파싱 실패라는 실패 모드 자체가 없다.
 *
 * SSE 이벤트(기존 token/plan/done/error에 3종 추가):
 * <pre>
 *   {"type":"step","n":1}                                    턴 시작
 *   {"type":"tool_call","id":"...","name":"...","args":{}}   도구 호출 시작
 *   {"type":"tool_result","id":"...","ok":true,"summary":"…"} 도구 실행 완료
 *   {"type":"token","t":"..."}                               최종 답변
 *   {"type":"plan","tasks":{...}}                            미저장 계획 변경(프론트가 채택)
 *   {"type":"plan_refresh","planId":12}                      서버가 이미 저장함(프론트가 재조회)
 *   {"type":"done"} / {"type":"error","m":"..."}
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunner {

    // 도구 호출 왕복 상한. 이 데모의 도구는 서로 의존하지 않아 보통 1~2턴이면 끝난다 —
    // 4턴은 "회고 조회 → 주간 요약 조회 → 계획 수정 → 마무리" 같은 최장 시나리오의 여유값이고,
    // 동시에 모델이 같은 도구를 무한히 부르는 폭주를 끊는 방어선이다.
    static final int MAX_TOOL_TURNS = 4;
    // 한 턴에 실행할 도구 개수 상한 — 초과분은 잘라내고 모델에게 알린다(조용히 버리지 않는다).
    static final int MAX_CALLS_PER_TURN = 3;
    // 최종 답변 토큰 상한 — 기존 자유 대화(MAX_CHAT_TOKENS)와 같은 기준.
    private static final int MAX_REPLY_TOKENS = 1200;
    // 도구 결과를 화면 추적 패널에 요약해 보낼 때의 길이 상한(전체 JSON은 서버 로그에만).
    private static final int MAX_SUMMARY_CHARS = 300;
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;

    private final OpenRouterClient openRouterClient;
    private final AiPromptBuilder promptBuilder;
    private final AiResponseParser responseParser;
    private final AgentToolRegistry toolRegistry;
    private final PlanService planService;
    private final ExecutorService sseExecutor;
    private final JsonMapper jsonMapper;

    public SseEmitter stream(AiChatRequest request, String owner, String sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        sseExecutor.submit(() -> relay(request, owner, sessionId, emitter));
        return emitter;
    }

    // SSE 어댑터 — 이벤트 하나를 data: <compact JSON>\n\n 으로 내보낸다(AiService와 같은 관례).
    private void relay(AiChatRequest request, String owner, String sessionId, SseEmitter emitter) {
        AgentEventSink sink = event -> emitter.send(jsonMapper.writeValueAsString(event));
        try {
            run(request, owner, sessionId, sink);
            sink.emit(Map.of("type", "done"));
            emitter.complete();
        } catch (BusinessException e) {
            // 알려진 실패(업스트림 오류·루프 상한) — 사용자용 한국어 메시지가 이미 ErrorCode에 있다.
            log.warn("Agent loop failed: {}", e.getErrorCode());
            trySend(sink, Map.of("type", "error", "m", e.getMessage()));
            emitter.complete();
        } catch (Exception e) {
            log.error("Agent loop failed", e);
            // 토큰을 한 개도 못 보낸 경우 프론트가 기존 자유 대화 경로로 폴백하도록 error를 보낸다.
            trySend(sink, Map.of("type", "error", "m", "에이전트 응답 중 오류가 발생했습니다."));
            emitter.complete();
        }
    }

    /**
     * 루프 본체 — SSE를 모르는 순수 오케스트레이션이라 이 메서드가 테스트의 진입점이다.
     * done/error 이벤트는 전송 계층({@link #relay})이 붙인다(성공·실패 판정은 예외로 전달).
     */
    void run(AiChatRequest request, String owner, String sessionId, AgentEventSink sink) throws IOException {
        AgentContext context = buildContext(request, owner, sessionId);
        List<Map<String, Object>> tools = toolRegistry.specsFor(context.status());
        List<Map<String, Object>> messages = promptBuilder.agentMessages(request);

        String reply = runLoop(messages, tools, context, sink);

        if (reply != null && !reply.isBlank()) {
            sink.emit(Map.of("type", "token", "t", reply));
        }
        // 계획 변경은 답변 뒤에 알린다 — 프론트가 말풍선을 먼저 확정하고 체크리스트를 갱신하게.
        if (context.planChanged()) {
            sink.emit(Map.of("type", "plan", "tasks", context.currentTasks()));
        }
        if (context.refreshRequested()) {
            sink.emit(Map.of("type", "plan_refresh", "planId", context.planId()));
        }
    }

    /**
     * 실행 문맥 조립. 상태(PlanStatus)를 <b>서버 저장본에서</b> 읽는 것이 핵심이다 — 요청 바디의
     * 값을 믿으면 클라이언트가 "이 계획은 DRAFT입니다"라고 주장해 고정된 계획의 수정 도구를
     * 열어젖힐 수 있다. 보관 전 초안(planId 없음)만 DRAFT로 본다.
     */
    private AgentContext buildContext(AiChatRequest request, String owner, String sessionId) {
        PlanStatus status = PlanStatus.DRAFT;
        Long planId = request.planId();
        if (planId != null) {
            try {
                PlanResponse plan = planService.getPlan(planId, owner);
                status = PlanStatus.fromStored(plan.status());
            } catch (BusinessException e) {
                // 삭제됐거나 남의 계획 — 존재를 숨기는 서비스 규약대로 "보관 전 초안"으로 강등한다.
                // 서버 데이터를 읽는 도구는 어차피 같은 이유로 실패를 돌려준다.
                planId = null;
            }
        }
        return new AgentContext(owner, sessionId, planId, status, request.goalName(),
                request.dailyHoursOrDefault(), request.tasks());
    }

    // 루프 본체. 최종 답변 문자열을 돌려준다(정제 완료).
    private String runLoop(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                           AgentContext context, AgentEventSink sink) throws IOException {
        for (int turn = 1; turn <= MAX_TOOL_TURNS; turn++) {
            sink.emit(Map.of("type", "step", "n", turn));
            OpenRouterClient.Completion completion =
                    openRouterClient.completeWithTools(messages, MAX_REPLY_TOKENS, tools);

            if (!completion.hasToolCalls()) {
                return responseParser.stripCjk(completion.content());
            }

            messages.add(assistantToolCallMessage(completion));
            executeCalls(completion.toolCalls(), messages, context, sink);
        }

        // 상한까지 갔다 — 도구 없이 한 번 더 물어 "지금까지 알아낸 것으로 답하라"고 강제한다.
        // 도구를 빼면 모델이 또 도구를 부를 수단이 없으므로 이 호출은 반드시 산문으로 끝난다.
        log.warn("Agent loop hit MAX_TOOL_TURNS={}, forcing a final answer without tools", MAX_TOOL_TURNS);
        OpenRouterClient.Completion forced =
                openRouterClient.completeWithTools(messages, MAX_REPLY_TOKENS, null);
        String reply = responseParser.stripCjk(forced.content());
        if (reply == null || reply.isBlank()) {
            throw new BusinessException(ErrorCode.AI_TOOL_LOOP_EXCEEDED);
        }
        return reply;
    }

    // 한 턴의 도구 호출들을 순차 실행하고, 각 결과를 tool 메시지로 messages에 붙인다.
    private void executeCalls(List<OpenRouterClient.ToolCall> calls, List<Map<String, Object>> messages,
                              AgentContext context, AgentEventSink sink) throws IOException {
        int limit = Math.min(calls.size(), MAX_CALLS_PER_TURN);
        for (int i = 0; i < limit; i++) {
            OpenRouterClient.ToolCall call = calls.get(i);
            JsonNode args = parseArgs(call.argumentsJson());

            Map<String, Object> callEvent = new LinkedHashMap<>();
            callEvent.put("type", "tool_call");
            callEvent.put("id", call.id());
            callEvent.put("name", call.name());
            callEvent.put("args", args == null ? Map.of() : args);
            sink.emit(callEvent);

            ToolResult result = execute(call, args, context);

            Map<String, Object> resultEvent = new LinkedHashMap<>();
            resultEvent.put("type", "tool_result");
            resultEvent.put("id", call.id());
            resultEvent.put("ok", result.ok());
            resultEvent.put("summary", summarize(result));
            sink.emit(resultEvent);

            messages.add(toolMessage(call.id(), result));
        }
        // 잘라낸 호출도 모델에게 알린다 — 조용히 버리면 모델이 "실행됐다"고 착각한 채 답한다.
        for (int i = limit; i < calls.size(); i++) {
            messages.add(toolMessage(calls.get(i).id(),
                    ToolResult.fail("한 번에 실행할 수 있는 도구는 " + MAX_CALLS_PER_TURN + "개까지입니다. 다음 턴에 다시 호출하세요.")));
        }
    }

    /**
     * 도구 하나 실행. 레지스트리에서 <b>현재 상태에 노출된</b> 도구만 찾으므로, 모델이 이전
     * 대화의 기억이나 환각으로 없는 도구를 불러도 실행되지 않고 사유만 돌아간다.
     * 도구 내부의 예외는 여기서 흡수한다 — 도구 하나가 실패해도 루프 전체를 끝내지 않는다.
     */
    private ToolResult execute(OpenRouterClient.ToolCall call, JsonNode args, AgentContext context) {
        Optional<AgentTool> tool = toolRegistry.find(call.name(), context.status());
        if (tool.isEmpty()) {
            return ToolResult.fail("'" + call.name() + "' 도구는 현재 계획 상태("
                    + context.status().getLabel() + ")에서 사용할 수 없습니다.");
        }
        if (args == null) {
            return ToolResult.fail("도구 인자가 올바른 JSON 객체가 아닙니다.");
        }
        try {
            return tool.get().execute(args, context);
        } catch (BusinessException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.error("Tool '{}' threw", call.name(), e);
            return ToolResult.fail("도구 실행 중 오류가 발생했습니다.");
        }
    }

    // 모델이 만든 arguments 문자열을 파싱한다. 깨진 JSON은 null — 호출부가 사유를 돌려준다.
    private JsonNode parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return jsonMapper.createObjectNode(); // 인자 없는 도구는 빈 객체가 정상이다
        }
        try {
            JsonNode node = jsonMapper.readTree(argumentsJson);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    // 모델의 도구 호출 요청을 대화 이력에 되돌려 넣는 assistant 턴. tool 메시지와 id로 짝지어지므로
    // 이 턴이 빠지면 다음 요청에서 업스트림이 400을 낸다.
    private Map<String, Object> assistantToolCallMessage(OpenRouterClient.Completion completion) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (OpenRouterClient.ToolCall call : completion.toolCalls()) {
            calls.add(Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.argumentsJson())));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", completion.content() == null ? "" : completion.content());
        message.put("tool_calls", calls);
        return message;
    }

    // 도구 실행 결과를 모델에게 돌려주는 tool 턴. 결과 문자열에도 CJK 필터를 적용한다 —
    // 모델이 도구 결과를 그대로 인용할 때 한자가 화면으로 새는 경로를 막는다.
    private Map<String, Object> toolMessage(String callId, ToolResult result) {
        String content;
        try {
            content = jsonMapper.writeValueAsString(result.toModelPayload());
        } catch (Exception e) {
            content = "{\"ok\":false,\"error\":\"결과를 직렬화하지 못했습니다.\"}";
        }
        return Map.of(
                "role", "tool",
                "tool_call_id", callId,
                "content", responseParser.stripCjk(content));
    }

    // 추적 패널에 보낼 한 줄 요약. 성공이면 결과 JSON을 잘라 보내고, 실패면 사유를 그대로 보낸다.
    private String summarize(ToolResult result) {
        if (!result.ok()) {
            return result.message();
        }
        String json;
        try {
            json = jsonMapper.writeValueAsString(result.payload());
        } catch (Exception e) {
            return "(결과 요약 실패)";
        }
        return json.length() > MAX_SUMMARY_CHARS ? json.substring(0, MAX_SUMMARY_CHARS) + "…" : json;
    }

    // 실패 경로에서 예외를 삼키고 이벤트 전송을 시도한다(이미 닫혔으면 무시) — AiService와 같은 관례.
    private void trySend(AgentEventSink sink, Map<String, Object> event) {
        try {
            sink.emit(event);
        } catch (Exception ignored) {
            // 연결이 이미 닫힌 경우 등 — 무시
        }
    }
}
