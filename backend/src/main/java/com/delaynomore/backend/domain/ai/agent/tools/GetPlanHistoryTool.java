package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.service.AuditEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계획 변경 이력(Audit, v0.7.0)을 도구로 노출한다. "이 계획 언제 왜 바꿨더라?" 같은 질문에서
 * 모델이 기억을 지어내지 않고 서버가 기록한 사실을 인용하게 하는 것이 목적이다.
 * 남의 계획·모르는 planId가 빈 목록인 것은 서비스의 존재 은닉 규약 그대로다.
 */
@Component
@RequiredArgsConstructor
public class GetPlanHistoryTool implements AgentTool {

    // 모델에게 돌려줄 이벤트 수 상한 — 이력은 계획 수명만큼 자라는데, 오래된 생성·토글 이벤트까지
    // 전부 실으면 입력 토큰만 늘고 답변 근거로는 최근 것이 거의 항상 충분하다.
    static final int MAX_EVENTS = 20;

    private final AuditEventService auditEventService;

    @Override
    public String name() {
        return "get_plan_history";
    }

    @Override
    public String description() {
        return "Read the audit history of the current plan (what happened and when: created, "
                + "confirmed, tasks updated, carry-overs, completion changes). Most recent first, "
                + "up to " + MAX_EVENTS + " events. Use this when the user asks what changed, "
                + "when something happened, or why the plan looks the way it does.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        if (!context.hasPlanId()) {
            return ToolResult.fail("아직 보관되지 않은 초안이라 변경 이력이 없습니다. 계획이 저장된 뒤 다시 시도하세요.");
        }
        List<AuditEventResponse> events = auditEventService.getEvents(context.planId(), context.owner());

        // 저장 순서(오래된 것부터)의 꼬리를 잘라 최신순으로 뒤집는다.
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && recent.size() < MAX_EVENTS; i--) {
            AuditEventResponse event = events.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", event.type());
            if (event.detail() != null) {
                entry.put("detail", event.detail());
            }
            entry.put("at", event.createdAt());
            recent.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalEvents", events.size());
        payload.put("events", recent);
        return ToolResult.ok(payload);
    }
}
