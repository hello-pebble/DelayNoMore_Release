package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.WeeklySummaryResponse;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주간 완료율 요약(v0.10.0의 읽기 전용 API)을 그대로 도구로 노출한다.
 * "이번 주 얼마나 했어?" 같은 질문에서 모델이 완료율을 세지 않고(세면 틀린다) 서버가 계산한
 * 값을 인용하게 하는 것이 목적이다 — 완료율 계산의 소유권은 계속 서버(Plan.countTasksBetween).
 */
@Component
@RequiredArgsConstructor
public class GetWeeklySummaryTool implements AgentTool {

    private final PlanService planService;

    @Override
    public String name() {
        return "get_weekly_summary";
    }

    @Override
    public String description() {
        return "Read the server-computed weekly completion summary of the current plan "
                + "(7-day buckets from the plan start date, with done/total/rate per week). "
                + "Always use this instead of counting tasks yourself when the user asks about progress.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        if (!context.hasPlanId()) {
            return ToolResult.fail("아직 보관되지 않은 초안이라 주간 요약을 만들 수 없습니다. 계획이 저장된 뒤 다시 시도하세요.");
        }
        WeeklySummaryResponse summary;
        try {
            summary = planService.getWeeklySummary(context.planId(), context.owner());
        } catch (BusinessException e) {
            return ToolResult.fail("계획을 찾을 수 없습니다.");
        }

        List<Map<String, Object>> weeks = new ArrayList<>();
        for (WeeklySummaryResponse.Week week : summary.weeks()) {
            weeks.add(Map.of(
                    "week", week.index(),
                    "from", week.startDate(),
                    "to", week.endDate(),
                    "done", week.done(),
                    "total", week.total(),
                    "ratePercent", week.rate()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("startDate", summary.startDate());
        payload.put("endDate", summary.endDate());
        payload.put("totalDone", summary.totalDone());
        payload.put("totalCount", summary.totalTotal());
        payload.put("weeks", weeks);
        return ToolResult.ok(payload);
    }
}
