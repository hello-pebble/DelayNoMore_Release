package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계획 전체의 진행 스냅샷 — 상태·기간·남은 일수·전체 완료율. "지금 어디까지 왔어?" 같은
 * 전체 조망 질문의 근거다. 주 단위 흐름은 get_weekly_summary의 몫이고, 여기는 합산 한 장이다.
 * 완료 개수·날짜의 계산 소유권은 계속 서버(PlanResponse.progress·startDate/endDate).
 */
@Component
@RequiredArgsConstructor
public class GetProgressTool implements AgentTool {

    private final PlanService planService;

    @Override
    public String name() {
        return "get_progress";
    }

    @Override
    public String description() {
        return "Read the overall progress snapshot of the current plan: status, start/end dates, "
                + "days remaining, and server-computed done/total across all dates. Use this for "
                + "overall questions like 'how far along am I?'. For a week-by-week breakdown, "
                + "use get_weekly_summary instead.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        if (!context.hasPlanId()) {
            return ToolResult.fail("아직 보관되지 않은 초안이라 진행 스냅샷이 없습니다. 계획이 저장된 뒤 다시 시도하세요.");
        }
        PlanResponse plan;
        try {
            plan = planService.getPlan(context.planId(), context.owner());
        } catch (BusinessException e) {
            return ToolResult.fail("계획을 찾을 수 없습니다.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("goalName", plan.goalName());
        payload.put("status", plan.status());
        payload.put("startDate", plan.startDate());
        payload.put("endDate", plan.endDate());
        if (plan.endDate() != null) {
            // 지났으면 0 — 모델이 음수를 "남은 일수"로 인용하는 사고를 막는다.
            long remaining = ChronoUnit.DAYS.between(KstDates.today(), LocalDate.parse(plan.endDate()));
            payload.put("remainingDays", Math.max(0, remaining));
        }
        payload.put("done", plan.progress().done());
        payload.put("total", plan.progress().total());
        int total = plan.progress().total();
        payload.put("ratePercent", total == 0 ? 0 : Math.round(plan.progress().done() * 100f / total));
        return ToolResult.ok(payload);
    }
}
