package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.time.KstDates;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * get_progress payload 계약 — 모델이 이 수치를 그대로 사용자에게 말하므로, 파생값
 * (remainingDays·ratePercent)이 틀리면 그 말이 틀린다.
 */
class GetProgressToolTest {

    private final PlanService planService = mock(PlanService.class);
    private final GetProgressTool tool = new GetProgressTool(planService);

    private PlanResponse planWith(String endDate, int done, int total) {
        return new PlanResponse(12L, "토익 900점", 7, 2, "초급", Map.of(), "CONFIRMED",
                null, null, "2026-08-25", endDate, "2026-08-25T00:00:00Z", 1L,
                new PlanResponse.Progress(done, total), false);
    }

    private AgentContext context() {
        return new AgentContext("guest-1", "session-1", 12L, PlanStatus.CONFIRMED, "토익 900점", 2, Map.of());
    }

    @Test
    void 남은일수와_완료율을_서버값에서_파생해_돌려준다() {
        when(planService.getPlan(12L, "guest-1"))
                .thenReturn(planWith(KstDates.today().plusDays(5).toString(), 3, 10));

        ToolResult result = tool.execute(null, context());

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.payload();
        assertThat(payload.get("remainingDays")).isEqualTo(5L);
        assertThat(payload.get("ratePercent")).isEqualTo(30);
        assertThat(payload.get("done")).isEqualTo(3);
        assertThat(payload.get("total")).isEqualTo(10);
    }

    @Test
    void 종료일이_지났으면_남은일수는_음수가_아니라_0이다() {
        when(planService.getPlan(12L, "guest-1"))
                .thenReturn(planWith(KstDates.today().minusDays(3).toString(), 0, 0));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) tool.execute(null, context()).payload();
        assertThat(payload.get("remainingDays")).isEqualTo(0L);
        // 빈 계획의 완료율은 0으로 나누지 않는다
        assertThat(payload.get("ratePercent")).isEqualTo(0);
    }

    @Test
    void 보관전_초안은_실패사유를_돌려준다() {
        AgentContext noPlan = new AgentContext("guest-1", "session-1", null, PlanStatus.DRAFT, "토익", 2, Map.of());

        assertThat(tool.execute(null, noPlan).ok()).isFalse();
    }
}
