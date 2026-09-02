package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.AuditEventService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * get_plan_history payload 계약 — 잘라내기(최신 20건)와 순서(최신 먼저)가 검증 대상이다.
 * 순서가 뒤집히면 모델이 "최근에 이랬다"며 계획 생성 시점 이야기를 한다.
 */
class GetPlanHistoryToolTest {

    private final AuditEventService auditEventService = mock(AuditEventService.class);
    private final GetPlanHistoryTool tool = new GetPlanHistoryTool(auditEventService);

    private AgentContext context() {
        return new AgentContext("guest-1", "session-1", 12L, PlanStatus.CONFIRMED, "토익 900점", 2, Map.of());
    }

    @Test
    void 최신순으로_상한까지만_돌려주고_전체_개수를_따로_알린다() {
        // 저장 순서(오래된 것부터)로 25건 — 상한(20)을 넘긴다.
        List<AuditEventResponse> stored = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> new AuditEventResponse(i, 12L, "TASKS_UPDATED", "변경 " + i, null,
                        "2026-08-" + String.format("%02d", Math.min(i, 28)) + "T00:00:00Z"))
                .toList();
        when(auditEventService.getEvents(12L, "guest-1")).thenReturn(stored);

        ToolResult result = tool.execute(null, context());

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.payload();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) payload.get("events");
        assertThat(payload.get("totalEvents")).isEqualTo(25);
        assertThat(events).hasSize(GetPlanHistoryTool.MAX_EVENTS);
        assertThat(events.get(0).get("detail")).isEqualTo("변경 25"); // 최신이 맨 앞
        assertThat(events.get(19).get("detail")).isEqualTo("변경 6"); // 오래된 5건은 잘렸다
    }

    @Test
    void 보관전_초안은_실패사유를_돌려준다() {
        AgentContext noPlan = new AgentContext("guest-1", "session-1", null, PlanStatus.DRAFT, "토익", 2, Map.of());

        assertThat(tool.execute(null, noPlan).ok()).isFalse();
    }
}
