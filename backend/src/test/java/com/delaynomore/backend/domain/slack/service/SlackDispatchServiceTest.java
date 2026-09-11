package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import com.delaynomore.backend.domain.plan.service.TodayDashboardService;
import com.delaynomore.backend.domain.slack.client.SlackApiClient;
import com.delaynomore.backend.domain.slack.repository.InMemorySlackRepository;
import com.delaynomore.backend.domain.slack.repository.SlackRepository.SlackLink;
import com.delaynomore.backend.global.config.SlackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackDispatchServiceTest {

    private static final SlackProperties ENABLED = new SlackProperties(null, "test-secret", true);

    private InMemorySlackRepository repository;
    private TodayDashboardService dashboardService;
    private SlackReflectionFlowService reflectionFlow;
    private SlackApiClient apiClient;
    private SlackDispatchService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySlackRepository();
        dashboardService = mock(TodayDashboardService.class);
        reflectionFlow = mock(SlackReflectionFlowService.class);
        when(reflectionFlow.startFlow(anyString(), any())).thenReturn(java.util.Optional.empty());
        apiClient = mock(SlackApiClient.class);
        service = new SlackDispatchService(ENABLED, repository, dashboardService,
                new SlackMessageComposer(), reflectionFlow, apiClient);
    }

    @Test
    void 활동시작이_지난_링크에_하루_한_번만_보낸다() {
        repository.upsertLink(alwaysActiveLink("user-1"));
        when(dashboardService.get("user-1")).thenReturn(dashboardWithOneTask());
        when(apiClient.postMessage(anyString(), anyString())).thenReturn(true);

        service.dispatch();
        service.dispatch(); // 루프가 다시 돌아도(재기동·중복 실행) 클레임이 중복 발송을 막는다

        verify(apiClient, times(1)).postMessage(anyString(), anyString());
    }

    @Test
    void 활동시작_전에는_보내지_않는다() {
        // start=1440(24:00)은 어떤 현재 시각에도 도달하지 않는다 — 시계 주입 없이 결정적으로 검증.
        repository.upsertLink(new SlackLink("user-1", "T1", "U1", "D1", 1440, 1440));

        service.dispatch();

        verify(apiClient, never()).postMessage(anyString(), anyString());
    }

    @Test
    void 오늘_작업이_없으면_전송을_생략하고_클레임은_닫는다() {
        repository.upsertLink(alwaysActiveLink("user-1"));
        when(dashboardService.get("user-1"))
                .thenReturn(new TodayDashboardResponse("2026-09-11", 0, 0, List.of()));

        service.dispatch();
        service.dispatch();

        verify(apiClient, never()).postMessage(anyString(), anyString());
        // 클레임이 sent로 닫혔으므로 재클레임 대상이 아니다.
        assertThat(repository.reclaimUnsent("user-1",
                java.time.LocalDate.now(com.delaynomore.backend.global.time.KstDates.KST),
                SlackDispatchService.KIND_CHECKLIST, 0, 3)).isFalse();
    }

    @Test
    void 전송_실패는_즉시_재시도하지_않는다_재시도권은_5분_창_뒤에_열린다() {
        repository.upsertLink(alwaysActiveLink("user-1"));
        when(dashboardService.get("user-1")).thenReturn(dashboardWithOneTask());
        when(apiClient.postMessage(anyString(), anyString())).thenReturn(false);

        service.dispatch();
        service.dispatch(); // 직후 재실행 — 5분 창이 안 지나 재클레임이 거부된다

        verify(apiClient, times(1)).postMessage(anyString(), anyString());
        // 5분 창이 지난 것으로 치면(retryAfter=0) 재시도권이 열린다 — sent_at이 비어 있다는 증거.
        assertThat(repository.reclaimUnsent("user-1",
                java.time.LocalDate.now(com.delaynomore.backend.global.time.KstDates.KST),
                SlackDispatchService.KIND_CHECKLIST, 0, 3)).isTrue();
    }

    @Test
    void 기능이_꺼져_있으면_아무것도_하지_않는다() {
        repository.upsertLink(alwaysActiveLink("user-1"));
        SlackDispatchService off = new SlackDispatchService(
                new SlackProperties(null, "test-secret", false), repository, dashboardService,
                new SlackMessageComposer(), reflectionFlow, apiClient);

        off.dispatch();

        verify(apiClient, never()).postMessage(anyString(), anyString());
    }

    @Test
    void 활동_종료가_지나면_회고_프롬프트를_하루_한_번만_보낸다() {
        repository.upsertLink(new SlackLink("user-1", "T1", "U1", "D1", 0, 0)); // end=0 → 항상 종료 이후
        when(dashboardService.get("user-1")).thenReturn(dashboardWithOneTask());
        when(apiClient.postMessage(anyString(), anyString())).thenReturn(true);
        when(reflectionFlow.startFlow(anyString(), any()))
                .thenReturn(java.util.Optional.of("🌙 회고 질문"));

        service.dispatch();
        service.dispatch();

        // 체크리스트 1회 + 회고 프롬프트 1회 — kind가 달라 클레임이 따로 잡히고 각각 멱등이다.
        verify(apiClient, times(2)).postMessage(anyString(), anyString());
        verify(reflectionFlow, times(1)).startFlow(anyString(), any());
    }

    @Test
    void 회고할_계획이_없으면_프롬프트를_보내지_않고_클레임만_닫는다() {
        repository.upsertLink(new SlackLink("user-1", "T1", "U1", "D1", 1440, 0)); // 체크리스트 미발송·회고만
        when(reflectionFlow.startFlow(anyString(), any())).thenReturn(java.util.Optional.empty());

        service.dispatch();
        service.dispatch();

        verify(apiClient, never()).postMessage(anyString(), anyString());
        verify(reflectionFlow, times(1)).startFlow(anyString(), any()); // 두 번째 턴은 클레임에 막힘
    }

    private static SlackLink alwaysActiveLink(String owner) {
        return new SlackLink(owner, "T1", "U1", "D1", 0, 1440); // start=0 → 항상 활동 시작 이후
    }

    private static TodayDashboardResponse dashboardWithOneTask() {
        PlanResponse plan = new PlanResponse(1L, "목표", null, null, null, null, "CONFIRMED",
                null, null, null, null, null, 0L, new PlanResponse.Progress(0, 1), false);
        TodayDashboardResponse.PlanItem item = new TodayDashboardResponse.PlanItem(plan,
                List.of(Map.of("id", "t1", "content", "할 일", "completed", false)), 0, 1, null, false);
        return new TodayDashboardResponse("2026-09-11", 0, 1, List.of(item));
    }
}
