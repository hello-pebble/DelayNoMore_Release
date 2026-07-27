package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.RecommendationResponse;
import com.delaynomore.backend.domain.plan.service.WorkloadRecommendationService;
import com.delaynomore.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다음 계획의 하루 분량 추천(v0.13.0)을 도구로 노출한다. 이 도구가 이 프로젝트의 AI 관점을
 * 가장 잘 보여준다 — <b>숫자는 서버 규칙이 정하고 모델은 그 숫자를 받아 설명만 한다.</b>
 * 완료율 50% 미만이면 −1, 85%+ 이면서 여유 회고가 많으면 +1 같은 규칙은
 * WorkloadRecommendation이 소유하고, 도구는 계산된 결과를 전달만 한다.
 *
 * 그래서 응답에 rulesOwnTheNumber 플래그를 실어 보낸다 — 모델이 "제가 4개를 추천드려요"처럼
 * 자기가 정한 것처럼 말하거나 숫자를 바꿔 말하지 않도록 데이터 자체가 못을 박는다.
 *
 * 비용 주의: recommend()는 내부에서 이유 문장 생성을 위해 짧은 LLM 호출을 한 번 더 한다
 * (RecommendationReasonWriter, 200 토큰 상한, 실패 시 서버 템플릿 폴백). 에이전트 루프 안의
 * 중첩 호출이라 이 도구가 있는 턴은 느려질 수 있지만, 폴백이 있어 실패로 이어지지는 않는다.
 */
@Component
@RequiredArgsConstructor
public class GetWorkloadRecommendationTool implements AgentTool {

    private final WorkloadRecommendationService recommendationService;

    @Override
    public String name() {
        return "get_workload_recommendation";
    }

    @Override
    public String description() {
        return "Ask the server's rules engine how many tasks per day the user's NEXT plan should have, "
                + "based on their completion history and retrospectives. The number is decided by fixed "
                + "server rules — you must report it as-is and must never change it. Use this when the "
                + "user asks whether the plan is too much/too little, or what to do next time.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        if (!context.hasPlanId()) {
            return ToolResult.fail("아직 보관되지 않은 초안이라 수행 기록이 없습니다. 며칠 실행한 뒤에 다시 물어보세요.");
        }
        RecommendationResponse rec;
        try {
            rec = recommendationService.recommend(context.planId(), context.owner(), context.sessionId());
        } catch (BusinessException e) {
            return ToolResult.fail("계획을 찾을 수 없습니다.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentTasksPerDay", rec.currentTasksPerDay());
        payload.put("recommendedTasksPerDay", rec.recommendedTasksPerDay());
        payload.put("rulesOwnTheNumber", true); // 모델이 숫자를 바꾸지 못하게 데이터로 못박는다
        payload.put("observedDays", rec.observedDays());
        payload.put("observedPlanCount", rec.observedPlanCount());
        payload.put("completedCount", rec.completedCount());
        payload.put("totalCount", rec.totalCount());
        payload.put("completionRatePercent", rec.completionRate());
        payload.put("hardDayCount", rec.hardCount());
        payload.put("topReasonLabel", rec.topReason() == null ? null : rec.topReason().label());
        payload.put("insufficientHistory", rec.insufficientHistory());
        payload.put("serverReason", rec.reason());
        return ToolResult.ok(payload);
    }
}
