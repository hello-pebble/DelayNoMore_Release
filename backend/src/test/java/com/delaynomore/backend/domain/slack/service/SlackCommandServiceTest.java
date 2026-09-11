package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.ai.client.OpenRouterClient;
import com.delaynomore.backend.domain.ai.client.OpenRouterClient.Completion;
import com.delaynomore.backend.domain.ai.client.OpenRouterClient.ToolCall;
import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.domain.plan.service.TodayDashboardService;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackCommandServiceTest {

    private static final OpenRouterProperties AI_ON =
            new OpenRouterProperties("https://example", "sk-test", "model", true, true);

    private OpenRouterClient openRouterClient;
    private TodayDashboardService dashboardService;
    private PlanService planService;
    private SlackCommandService service;

    @BeforeEach
    void setUp() {
        openRouterClient = mock(OpenRouterClient.class);
        dashboardService = mock(TodayDashboardService.class);
        planService = mock(PlanService.class);
        service = new SlackCommandService(AI_ON, openRouterClient, dashboardService,
                new SlackMessageComposer(), planService, JsonMapper.builder().build());
        when(dashboardService.get("user-1")).thenReturn(dashboard(false));
    }

    @Test
    void complete_task_호출은_번호를_planId와_taskId로_해석해_기존_서비스에_위임한다() {
        modelReturnsTool("complete_task", "{\"number\":\"2\"}");
        when(dashboardService.get("user-1")).thenReturn(dashboard(false), dashboard(true));

        String reply = service.handle("user-1", "오답 정리 끝냈어");

        // 번호 2 = 계획 1의 두 번째 작업(정렬 규칙 재현) — 날짜는 서버가 taskId로 역추적한다.
        verify(planService).updateTaskCompletion(1L, "t2", true, "user-1", "slack");
        assertThat(reply).contains("완료").contains("2/2");
    }

    @Test
    void completed_false는_완료_해제로_위임된다() {
        when(dashboardService.get("user-1")).thenReturn(dashboard(true), dashboard(false));
        modelReturnsTool("complete_task", "{\"number\":\"2\",\"completed\":\"false\"}");

        String reply = service.handle("user-1", "2번 아직 못 했어, 체크 취소해줘");

        verify(planService).updateTaskCompletion(1L, "t2", false, "user-1", "slack");
        assertThat(reply).contains("해제");
    }

    @Test
    void 목록에_없는_번호는_변이_없이_안내한다() {
        modelReturnsTool("complete_task", "{\"number\":\"9\"}");

        String reply = service.handle("user-1", "9번 완료");

        verify(planService, never()).updateTaskCompletion(anyLong(), anyString(), anyBoolean(), anyString(), anyString());
        assertThat(reply).contains("9번");
    }

    @Test
    void 서버_잠금_판정은_그대로_답장으로_전달된다() {
        modelReturnsTool("complete_task", "{\"number\":\"2\"}");
        doThrow(new BusinessException(ErrorCode.PAST_TASK_LOCKED))
                .when(planService).updateTaskCompletion(anyLong(), anyString(), anyBoolean(), anyString(), anyString());

        String reply = service.handle("user-1", "2번 완료");

        assertThat(reply).isEqualTo(ErrorCode.PAST_TASK_LOCKED.getMessage());
    }

    @Test
    void no_action은_변이_없이_reply만_보낸다() {
        modelReturnsTool("no_action", "{\"reply\":\"안녕하세요! 오늘도 화이팅이에요.\"}");

        String reply = service.handle("user-1", "안녕!");

        verify(planService, never()).updateTaskCompletion(anyLong(), anyString(), anyBoolean(), anyString(), anyString());
        assertThat(reply).isEqualTo("안녕하세요! 오늘도 화이팅이에요.");
    }

    @Test
    void 키_미설정이면_LLM을_부르지_않고_안내한다() {
        SlackCommandService off = new SlackCommandService(
                new OpenRouterProperties("https://example", null, "model", true, true),
                openRouterClient, dashboardService, new SlackMessageComposer(), planService,
                JsonMapper.builder().build());

        String reply = off.handle("user-1", "1번 완료");

        verify(openRouterClient, never()).completeWithTools(any(), anyList(), anyInt(), anyList());
        assertThat(reply).contains("웹 화면");
    }

    @Test
    void 멘션_토큰은_해석_전에_제거된다() {
        assertThat(SlackCommandService.stripMentions("<@U0ABC123> 1번 완료했어")).isEqualTo("1번 완료했어");
    }

    @Test
    void 이미_같은_상태면_변이_없이_알려준다() {
        when(dashboardService.get("user-1")).thenReturn(dashboard(true)); // 2번이 이미 완료
        modelReturnsTool("complete_task", "{\"number\":\"2\"}");

        String reply = service.handle("user-1", "2번 완료");

        verify(planService, never()).updateTaskCompletion(anyLong(), anyString(), anyBoolean(), anyString(), anyString());
        assertThat(reply).contains("이미 완료");
    }

    private void modelReturnsTool(String name, String argsJson) {
        when(openRouterClient.completeWithTools(eq(AiCallSite.SLACK_INTENT), anyList(), anyInt(), anyList()))
                .thenReturn(new Completion("", List.of(new ToolCall("c1", name, argsJson))));
    }

    /** 계획 1(작업 t1 완료, t2는 secondDone에 따라) — 번호 1=t1, 2=t2. */
    private static TodayDashboardResponse dashboard(boolean secondDone) {
        PlanResponse plan = new PlanResponse(1L, "정보처리기사", null, null, null, null, "CONFIRMED",
                null, null, null, null, null, 0L, new PlanResponse.Progress(1, 2), false);
        List<Object> tasks = List.of(
                Map.of("id", "t1", "content", "기출 1회 풀기", "completed", true),
                Map.of("id", "t2", "content", "오답 정리", "completed", secondDone));
        int done = secondDone ? 2 : 1;
        TodayDashboardResponse.PlanItem item =
                new TodayDashboardResponse.PlanItem(plan, tasks, done, 2, null, false);
        return new TodayDashboardResponse("2026-09-11", done, 2, List.of(item));
    }
}
