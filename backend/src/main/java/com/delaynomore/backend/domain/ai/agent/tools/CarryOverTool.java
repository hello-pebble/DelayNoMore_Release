package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.CarryOverResponse;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 오늘(KST) 미완료 항목을 내일로 미루는 도구 — 기존 도메인 액션
 * {@code POST /plans/{id}/carry-over}를 그대로 호출한다. 이월 규칙("오늘 → 내일"만, 필요 시
 * 종료일 하루 연장)은 PlanService·PlanCarryOver가 소유하고, 도구는 날짜를 지정하지 않는다 —
 * 모델이 "3일 뒤로 미뤄줘" 같은 요청을 받아도 서버 규칙을 우회할 수단이 없다.
 *
 * 노출 규칙: allowsCarryOver()를 참조하므로 고정(CONFIRMED) 계획에서도 쓸 수 있다.
 * 이월은 내용 재협상이 아니라 실행 단계 액션이라는 v0.14.1의 판단을 그대로 물려받는다 —
 * 즉 수정 도구는 사라져도 이월 도구는 남는다. 종결(COMPLETED·CANCELLED)에서만 사라진다.
 *
 * 이 도구는 다른 변이 도구와 달리 <b>서버 상태를 직접 바꾼다</b>(이력도 남는다). 그래서 병합된
 * 계획을 내려보내지 않고 refresh만 요청한다 — 자세한 이유는 AgentContext.requestRefresh 참고.
 */
@Component
@RequiredArgsConstructor
public class CarryOverTool implements AgentTool {

    private final PlanService planService;

    @Override
    public String name() {
        return "carry_over_tasks";
    }

    @Override
    public String description() {
        return "Move today's unfinished tasks to tomorrow (KST), extending the end date by one day if "
                + "needed. Takes no arguments — the server owns the dates. Use this when the user says "
                + "they could not finish today and want to push the rest to tomorrow. Returns how many "
                + "items moved; 0 means there was nothing left to move.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    // 이월은 종결 전까지 허용 — 고정 후에도 실행을 이어가야 하므로. 기준은 PlanStatus가 소유한다.
    @Override
    public boolean isAvailableFor(PlanStatus status) {
        return status.allowsCarryOver();
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        if (!context.hasPlanId()) {
            return ToolResult.fail("아직 보관되지 않은 초안이라 이월할 수 없습니다. 계획이 저장된 뒤 다시 시도하세요.");
        }
        CarryOverResponse result;
        try {
            result = planService.carryOver(context.planId(), context.owner(), context.sessionId());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PLAN_LOCKED) {
                return ToolResult.fail("종결된 계획이라 이월할 수 없습니다.");
            }
            return ToolResult.fail("계획을 찾을 수 없습니다.");
        }

        if (result.movedCount() > 0) {
            context.requestRefresh(); // 서버가 이미 저장했다 — 프론트는 다시 읽기만 하면 된다
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("movedCount", result.movedCount());
        payload.put("targetDate", result.targetDate());
        payload.put("note", result.movedCount() == 0
                ? "서버 기준 오늘 날짜에 미완료 항목이 없어 아무것도 옮기지 않았습니다."
                : "이월이 완료되어 서버에 저장됐습니다.");
        return ToolResult.ok(payload);
    }
}
