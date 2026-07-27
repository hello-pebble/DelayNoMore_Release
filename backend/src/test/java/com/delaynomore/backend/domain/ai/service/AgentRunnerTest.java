package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.agent.AgentEventSink;
import com.delaynomore.backend.domain.ai.agent.AgentToolRegistry;
import com.delaynomore.backend.domain.ai.agent.tools.CarryOverTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetReflectionHistoryTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetTodayTasksTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetWeeklySummaryTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetWorkloadRecommendationTool;
import com.delaynomore.backend.domain.ai.agent.tools.UpdatePlanTasksTool;
import com.delaynomore.backend.domain.ai.client.OpenRouterClient;
import com.delaynomore.backend.domain.ai.dto.AiChatRequest;
import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.ai.usage.AiUsageLogger;
import com.delaynomore.backend.domain.ai.usage.TokenUsage;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.domain.plan.service.WorkloadRecommendationService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.time.KstDates;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 에이전트 루프의 계약 검증. 업스트림(OpenRouterClient)만 모킹하고 프롬프트 조립·도구
 * 레지스트리·병합은 실제 구현을 쓴다 — 루프가 "모델 → 도구 → 모델"을 제대로 잇는지,
 * 그리고 폭주·환각·권한 위반에서 어떻게 버티는지가 검증 대상이다.
 */
class AgentRunnerTest {

    private static final String OWNER = "guest-1234abcd";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OpenRouterClient openRouterClient = mock(OpenRouterClient.class);
    private final AiUsageLogger usageLogger = mock(AiUsageLogger.class);
    private final PlanService planService = mock(PlanService.class);
    private final ReflectionService reflectionService = mock(ReflectionService.class);
    private final WorkloadRecommendationService recommendationService = mock(WorkloadRecommendationService.class);

    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(
            new GetTodayTasksTool(planService),
            new GetWeeklySummaryTool(planService),
            new GetReflectionHistoryTool(reflectionService),
            new GetWorkloadRecommendationTool(recommendationService),
            new UpdatePlanTasksTool(),
            new CarryOverTool(planService)));

    private final AgentRunner runner = new AgentRunner(openRouterClient, new AiPromptBuilder(jsonMapper),
            new AiResponseParser(jsonMapper), registry, planService,
            Executors.newSingleThreadExecutor(), jsonMapper, usageLogger);

    // 이벤트를 모으는 sink — SSE 대신 리스트에 쌓아 순서와 내용을 그대로 검증한다.
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final AgentEventSink sink = events::add;

    private List<String> eventTypes() {
        return events.stream().map(e -> String.valueOf(e.get("type"))).toList();
    }

    private Map<String, Object> firstEvent(String type) {
        return events.stream().filter(e -> type.equals(e.get("type"))).findFirst().orElse(null);
    }

    private static OpenRouterClient.Completion reply(String content) {
        return new OpenRouterClient.Completion(content, List.of());
    }

    // 사용량이 실린 응답 — 요청 단위 합계가 턴별 값을 제대로 더하는지 보기 위한 스텁.
    private static OpenRouterClient.Completion reply(String content, int prompt, int completion) {
        return new OpenRouterClient.Completion(content, List.of(),
                new TokenUsage(prompt, completion, prompt + completion, null));
    }

    private static OpenRouterClient.Completion callsTool(String name, String argsJson, int prompt, int completion) {
        return new OpenRouterClient.Completion("",
                List.of(new OpenRouterClient.ToolCall("call-1", name, argsJson)),
                new TokenUsage(prompt, completion, prompt + completion, null));
    }

    private static OpenRouterClient.Completion callsTool(String name, String argsJson) {
        return new OpenRouterClient.Completion("",
                List.of(new OpenRouterClient.ToolCall("call-1", name, argsJson)));
    }

    private AiChatRequest request(String message, Long planId, Map<String, Object> tasks) {
        return new AiChatRequest("정보처리기사", 3, 2, "초급", message, tasks, List.of(), planId);
    }

    // 서버 저장본 스텁 — 루프는 상태를 요청 바디가 아니라 여기서 읽는다.
    private void givenStoredPlan(long id, PlanStatus status, Map<String, Object> tasks) {
        Plan plan = new Plan(id, OWNER, "정보처리기사", 3, 2, "초급", tasks, status.name(),
                null, null, "2026-07-27", "2026-07-29", "2026-07-27T00:00:00Z", 1L);
        when(planService.getPlan(id, OWNER)).thenReturn(PlanResponse.from(plan));
    }

    private static Map<String, Object> planWith(String date, String content, boolean completed) {
        return Map.of(date, List.of(Map.of("id", "t-1", "content", content, "completed", completed)));
    }

    @Test
    void run_도구없이바로답변_토큰이벤트만나온다() throws IOException {
        // given — 인사말처럼 도구가 필요 없는 요청. 루프는 1턴에 끝나야 한다.
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(reply("안녕하세요! 무엇을 도와드릴까요?"));

        // when
        runner.run(request("안녕하세요", null, Map.of()), OWNER, "sess-1", sink);

        // then
        assertThat(eventTypes()).containsExactly("step", "token");
        assertThat(firstEvent("token")).containsEntry("t", "안녕하세요! 무엇을 도와드릴까요?");
    }

    @Test
    void run_도구호출후답변_호출과결과가추적이벤트로흐른다() throws IOException {
        // given — 1턴: 오늘 할 일 조회 도구 호출 / 2턴: 결과를 근거로 답변
        givenStoredPlan(7L, PlanStatus.CONFIRMED, planWith(KstDates.today().toString(), "SQL 예제 풀이", true));
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("get_today_tasks", "{}"))
                .thenReturn(reply("오늘 1개 중 1개를 끝내셨어요."));

        // when
        runner.run(request("오늘 얼마나 했지?", 7L, Map.of()), OWNER, "sess-1", sink);

        // then — 추적 패널이 그릴 수 있도록 호출과 결과가 짝지어 흐른다
        assertThat(eventTypes()).containsExactly("step", "tool_call", "tool_result", "step", "token");
        assertThat(firstEvent("tool_call")).containsEntry("name", "get_today_tasks");
        Map<String, Object> result = firstEvent("tool_result");
        assertThat(result).containsEntry("ok", true);
        assertThat(String.valueOf(result.get("summary"))).contains("\"doneCount\":1");
    }

    @Test
    void run_여러턴을돌면_요청단위합계를한번기록한다() throws IOException {
        // given — 2턴 시나리오. 턴마다 직전 도구 결과가 붙은 대화 전체를 다시 보내므로 입력 토큰이
        // 누적으로 늘어난다(1200 → 1800). 호출당 로그만 보면 이 누적이 눈에 띄지 않는다.
        givenStoredPlan(7L, PlanStatus.CONFIRMED, planWith(KstDates.today().toString(), "SQL 예제 풀이", true));
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("get_today_tasks", "{}", 1200, 30))
                .thenReturn(reply("오늘 1개 중 1개를 끝내셨어요.", 1800, 40));

        // when
        runner.run(request("오늘 얼마나 했지?", 7L, Map.of()), OWNER, "sess-1", sink);

        // then — 합계 한 줄에 "업스트림을 몇 번 때렸는가(calls)"와 누적 토큰이 함께 남는다
        verify(usageLogger).recordTotal(AiCallSite.AGENT_TOTAL, 2,
                new TokenUsage(3000, 70, 3070, null));
    }

    @Test
    void run_업스트림이실패해도_그때까지쓴토큰은기록한다() throws IOException {
        // given — 1턴은 성공(토큰 소모), 2턴에서 업스트림 오류. 실패한 요청도 이미 쓴 토큰은
        // 청구되므로, 성공한 요청만 세면 비용이 실제보다 적게 보인다.
        givenStoredPlan(7L, PlanStatus.CONFIRMED, planWith(KstDates.today().toString(), "SQL 예제 풀이", true));
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("get_today_tasks", "{}", 1200, 30))
                .thenThrow(new BusinessException(ErrorCode.AI_UPSTREAM_ERROR));

        // when
        catchThrowableOfType(BusinessException.class,
                () -> runner.run(request("오늘 얼마나 했지?", 7L, Map.of()), OWNER, "sess-1", sink));

        // then
        verify(usageLogger).recordTotal(AiCallSite.AGENT_TOTAL, 1,
                new TokenUsage(1200, 30, 1230, null));
    }

    @Test
    void run_초안에서수정도구호출_병합된계획이plan이벤트로나간다() throws IOException {
        // given — DRAFT라 update_plan_tasks가 노출된다. patch는 변경된 날짜만.
        givenStoredPlan(7L, PlanStatus.DRAFT, Map.of());
        Map<String, Object> current = planWith("2026-07-28", "기존 할 일", false);
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("update_plan_tasks",
                        "{\"patch\":{\"2026-07-29\":[\"운영체제 정리\"]}}"))
                .thenReturn(reply("29일에 운영체제 정리를 추가했어요."));

        // when
        runner.run(request("29일에 운영체제 추가해줘", 7L, current), OWNER, "sess-1", sink);

        // then — 병합은 ChatPatchMerger가 하므로 patch에 없던 기존 날짜도 살아 있다
        assertThat(firstEvent("tool_result")).containsEntry("ok", true);
        Map<String, Object> planEvent = firstEvent("plan");
        assertThat(planEvent).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> tasks = (Map<String, Object>) planEvent.get("tasks");
        assertThat(tasks).containsKeys("2026-07-28", "2026-07-29");
    }

    @Test
    void run_고정계획에서수정도구호출_실행되지않고사유만돌아간다() throws IOException {
        // given — CONFIRMED라 update_plan_tasks는 애초에 프롬프트에 실리지 않는다.
        // 그럼에도 모델이 환각으로 부르는 경우를 방어하는지 확인한다(레지스트리 2차 판정).
        givenStoredPlan(7L, PlanStatus.CONFIRMED, Map.of());
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("update_plan_tasks", "{\"patch\":{\"2026-07-29\":[\"몰래 추가\"]}}"))
                .thenReturn(reply("이 계획은 고정되어 수정할 수 없어요."));

        // when
        runner.run(request("29일 바꿔줘", 7L, planWith("2026-07-28", "기존 할 일", false)), OWNER, "sess-1", sink);

        // then — 실행 거부 + 계획 변경 이벤트 없음
        Map<String, Object> result = firstEvent("tool_result");
        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("summary"))).contains("고정");
        assertThat(eventTypes()).doesNotContain("plan");
    }

    @Test
    void run_이월도구성공_저장본재조회를요청한다() throws IOException {
        // given — 이월은 서버가 직접 저장하므로 plan(초안 채택)이 아니라 plan_refresh가 나가야 한다.
        // plan을 내려보내면 프론트가 그 값을 다시 PUT하려 들고, 고정 계획에서 409로 튕긴다.
        givenStoredPlan(7L, PlanStatus.CONFIRMED, Map.of());
        when(planService.carryOver(anyLong(), anyString(), anyString()))
                .thenReturn(new com.delaynomore.backend.domain.plan.dto.CarryOverResponse(2, "2026-07-28", null));
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("carry_over_tasks", "{}"))
                .thenReturn(reply("미완료 2건을 내일로 옮겼어요."));

        // when
        runner.run(request("남은 거 내일로 미뤄줘", 7L, Map.of()), OWNER, "sess-1", sink);

        // then
        assertThat(eventTypes()).contains("plan_refresh").doesNotContain("plan");
        assertThat(firstEvent("plan_refresh")).containsEntry("planId", 7L);
    }

    @Test
    void run_모르는도구이름_실행없이사유를모델에게돌려준다() throws IOException {
        // given — 환각으로 없는 도구를 부른 경우. 루프를 끊지 않고 다음 턴으로 이어져야 한다.
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("delete_everything", "{}"))
                .thenReturn(reply("죄송해요, 그건 할 수 없어요."));

        // when
        runner.run(request("전부 지워줘", null, Map.of()), OWNER, "sess-1", sink);

        // then
        assertThat(firstEvent("tool_result")).containsEntry("ok", false);
        assertThat(firstEvent("token")).containsEntry("t", "죄송해요, 그건 할 수 없어요.");
    }

    @Test
    void run_인자가깨진JSON_실행없이사유를돌려준다() throws IOException {
        // given — 모델이 만든 arguments가 JSON이 아닌 경우(계약 위반). 예외로 루프를 끝내지 않는다.
        givenStoredPlan(7L, PlanStatus.DRAFT, Map.of());
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("update_plan_tasks", "{patch: 이건 JSON이 아님"))
                .thenReturn(reply("요청을 다시 정리해 주시겠어요?"));

        // when
        runner.run(request("바꿔줘", 7L, Map.of()), OWNER, "sess-1", sink);

        // then
        assertThat(firstEvent("tool_result")).containsEntry("ok", false);
        assertThat(eventTypes()).doesNotContain("plan");
    }

    @Test
    void run_루프상한도달_도구없이한번더물어마무리한다() throws IOException {
        // given — 모델이 계속 도구만 부르는 폭주. 상한(MAX_TOOL_TURNS)에서 끊고,
        // 도구를 뺀 마지막 호출로 산문 답변을 강제한다.
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("get_today_tasks", "{}"));
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), isNull()))
                .thenReturn(reply("지금까지 확인한 내용으로 답변드릴게요."));

        // when
        runner.run(request("오늘 뭐 해?", null, Map.of()), OWNER, "sess-1", sink);

        // then — step이 상한만큼 나오고, 마지막은 도구 없는 호출로 마감된다
        assertThat(eventTypes()).filteredOn("step"::equals).hasSize(AgentRunner.MAX_TOOL_TURNS);
        assertThat(firstEvent("token")).containsEntry("t", "지금까지 확인한 내용으로 답변드릴게요.");
        verify(openRouterClient).completeWithTools(any(), anyList(), anyInt(), isNull());
    }

    @Test
    void run_상한후에도빈답변_루프초과예외로폴백을유도한다() {
        // given — 강제 마무리마저 빈 답이면 프론트가 기존 자유 대화 경로로 폴백해야 한다.
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("get_today_tasks", "{}"));
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), isNull()))
                .thenReturn(reply("  "));

        // when
        BusinessException thrown = catchThrowableOfType(BusinessException.class,
                () -> runner.run(request("오늘 뭐 해?", null, Map.of()), OWNER, "sess-1", sink));

        // then
        assertThat(thrown).isNotNull();
        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.AI_TOOL_LOOP_EXCEEDED);
    }

    @Test
    void run_남의계획id_보관전초안으로강등되고저장본을만지지않는다() throws IOException {
        // given — 소유자가 다른(또는 삭제된) 계획 id. getPlan이 404를 던지면 planId를 버린다.
        when(planService.getPlan(99L, OWNER)).thenThrow(new BusinessException(ErrorCode.PLAN_NOT_FOUND));
        when(openRouterClient.completeWithTools(any(), anyList(), anyInt(), anyList()))
                .thenReturn(callsTool("carry_over_tasks", "{}"))
                .thenReturn(reply("보관된 계획이 없어 이월할 수 없어요."));

        // when
        runner.run(request("내일로 미뤄줘", 99L, Map.of()), OWNER, "sess-1", sink);

        // then — 도메인 액션이 호출되지 않고 사유만 돌아간다
        verify(planService, never()).carryOver(anyLong(), anyString(), anyString());
        assertThat(firstEvent("tool_result")).containsEntry("ok", false);
    }
}
