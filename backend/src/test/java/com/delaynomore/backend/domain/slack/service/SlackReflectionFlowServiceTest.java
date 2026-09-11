package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.ReflectionResponse;
import com.delaynomore.backend.domain.plan.dto.ReflectionSaveRequest;
import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import com.delaynomore.backend.domain.plan.entity.ReflectionDifficulty;
import com.delaynomore.backend.domain.plan.entity.ReflectionReason;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.domain.plan.service.TodayDashboardService;
import com.delaynomore.backend.domain.slack.repository.InMemorySlackRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackReflectionFlowServiceTest {

    private static final String OWNER = "user-1";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    private InMemorySlackRepository repository;
    private TodayDashboardService dashboardService;
    private ReflectionService reflectionService;
    private SlackReflectionFlowService service;

    @BeforeEach
    void setUp() {
        repository = new InMemorySlackRepository();
        dashboardService = mock(TodayDashboardService.class);
        reflectionService = mock(ReflectionService.class);
        service = new SlackReflectionFlowService(repository, dashboardService, reflectionService);
        when(dashboardService.get(OWNER)).thenReturn(dashboardWithTwoPlans());
    }

    @Test
    void 시작하면_계획별_세션이_만들어지고_첫_계획만_난이도_질문_상태다() {
        Optional<String> prompt = service.startFlow(OWNER, TODAY);

        assertThat(prompt).isPresent();
        assertThat(prompt.get()).contains("1/3").contains("정보처리기사").contains("벅찼어요");
        assertThat(repository.findAwaitingReflectionSession(OWNER, TODAY).orElseThrow().planId()).isEqualTo(1L);
        assertThat(repository.findPendingReflectionSession(OWNER, TODAY).orElseThrow().planId()).isEqualTo(2L);
    }

    @Test
    void 회고가_이미_있는_계획만이면_시작하지_않는다() {
        when(dashboardService.get(OWNER)).thenReturn(dashboardAllReflected());

        assertThat(service.startFlow(OWNER, TODAY)).isEmpty();
    }

    @Test
    void 난이도_답변은_숫자든_말이든_해석되고_이유_질문으로_넘어간다() {
        service.startFlow(OWNER, TODAY);

        String reply = service.handleAnswer(OWNER, TODAY, "3");

        assertThat(reply).contains("이유");
        var session = repository.findAwaitingReflectionSession(OWNER, TODAY).orElseThrow();
        assertThat(session.state()).isEqualTo(SlackReflectionFlowService.STATE_AWAITING_REASON);
        assertThat(session.difficulty()).isEqualTo("HARD");
    }

    @Test
    void 이유_답변까지_오면_기존_ReflectionService로_저장하고_다음_계획으로_넘어간다() {
        service.startFlow(OWNER, TODAY);
        service.handleAnswer(OWNER, TODAY, "1");           // 난이도: 여유
        String reply = service.handleAnswer(OWNER, TODAY, "계획대로 됐어요"); // 이유: 키워드

        ArgumentCaptor<ReflectionSaveRequest> captor = ArgumentCaptor.forClass(ReflectionSaveRequest.class);
        verify(reflectionService).save(eq(1L), eq(TODAY.toString()), captor.capture(), eq(OWNER), eq("slack"));
        assertThat(captor.getValue().difficulty()).isEqualTo("EASY");
        assertThat(captor.getValue().reason()).isEqualTo("AS_PLANNED");
        // 다음 계획(2번)의 난이도 질문이 이어진다.
        assertThat(reply).contains("저장했어요").contains("영어 회화");
        assertThat(repository.findAwaitingReflectionSession(OWNER, TODAY).orElseThrow().planId()).isEqualTo(2L);
    }

    @Test
    void 마지막_계획까지_끝나면_마무리_인사가_온다() {
        service.startFlow(OWNER, TODAY);
        service.handleAnswer(OWNER, TODAY, "2");
        service.handleAnswer(OWNER, TODAY, "2");
        service.handleAnswer(OWNER, TODAY, "1");
        String reply = service.handleAnswer(OWNER, TODAY, "5");

        assertThat(reply).contains("모두 마쳤어요");
        assertThat(repository.findAwaitingReflectionSession(OWNER, TODAY)).isEmpty();
        assertThat(repository.findPendingReflectionSession(OWNER, TODAY)).isEmpty();
    }

    @Test
    void 못_알아들으면_변이_없이_재질문한다() {
        service.startFlow(OWNER, TODAY);

        String reply = service.handleAnswer(OWNER, TODAY, "글쎄요...");

        assertThat(reply).contains("1. 여유로웠어요");
        assertThat(repository.findAwaitingReflectionSession(OWNER, TODAY).orElseThrow().state())
                .isEqualTo(SlackReflectionFlowService.STATE_AWAITING_DIFFICULTY);
        verify(reflectionService, never()).save(anyLong(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void 자정이_지나면_서버_가드를_우회하지_않고_세션을_닫는다() {
        service.startFlow(OWNER, TODAY);
        service.handleAnswer(OWNER, TODAY, "1");
        doThrow(new BusinessException(ErrorCode.REFLECTION_DATE_NOT_TODAY))
                .when(reflectionService).save(anyLong(), anyString(), any(), anyString(), anyString());

        String reply = service.handleAnswer(OWNER, TODAY, "1");

        assertThat(reply).contains("자정");
        assertThat(repository.findAwaitingReflectionSession(OWNER, TODAY)).isEmpty();
        assertThat(repository.findPendingReflectionSession(OWNER, TODAY)).isEmpty(); // 남은 계획도 EXPIRED
    }

    @Test
    void 답변_해석_규칙() {
        assertThat(SlackReflectionFlowService.parseDifficulty("2")).isEqualTo(ReflectionDifficulty.NORMAL);
        assertThat(SlackReflectionFlowService.parseDifficulty("좀 벅찼어")).isEqualTo(ReflectionDifficulty.HARD);
        assertThat(SlackReflectionFlowService.parseDifficulty("여유로웠지")).isEqualTo(ReflectionDifficulty.EASY);
        assertThat(SlackReflectionFlowService.parseDifficulty("모르겠다")).isNull();
        assertThat(SlackReflectionFlowService.parseReason("4")).isEqualTo(ReflectionReason.HARD_TO_FOCUS);
        assertThat(SlackReflectionFlowService.parseReason("시간이 모자랐어")).isEqualTo(ReflectionReason.NOT_ENOUGH_TIME);
        assertThat(SlackReflectionFlowService.parseReason("6")).isNull();
    }

    /** 계획 1(정보처리기사, 오늘 1/2)·계획 2(영어 회화, 오늘 0/1) — 둘 다 회고 없음. */
    private static TodayDashboardResponse dashboardWithTwoPlans() {
        return new TodayDashboardResponse("2026-09-11", 1, 3, List.of(
                planItem(1L, "정보처리기사", 1, 2, null),
                planItem(2L, "영어 회화", 0, 1, null)));
    }

    private static TodayDashboardResponse dashboardAllReflected() {
        ReflectionResponse reflection = new ReflectionResponse(1L, "2026-09-11", 1, 2,
                "NORMAL", "AS_PLANNED", null, null);
        return new TodayDashboardResponse("2026-09-11", 1, 2, List.of(
                planItem(1L, "정보처리기사", 1, 2, reflection)));
    }

    private static TodayDashboardResponse.PlanItem planItem(long id, String goalName, int done, int total,
                                                            ReflectionResponse reflection) {
        PlanResponse plan = new PlanResponse(id, goalName, null, null, null, null, "CONFIRMED",
                null, null, null, null, null, 0L, new PlanResponse.Progress(done, total), false);
        List<Object> tasks = List.of(Map.of("id", "t" + id, "content", "할 일", "completed", false));
        return new TodayDashboardResponse.PlanItem(plan, tasks, done, total, reflection, false);
    }
}
