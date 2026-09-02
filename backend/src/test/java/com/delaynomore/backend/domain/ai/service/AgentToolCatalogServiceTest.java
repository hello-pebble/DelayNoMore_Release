package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.agent.AgentToolRegistry;
import com.delaynomore.backend.domain.ai.agent.tools.CarryOverTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetReflectionHistoryTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetTodayTasksTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetWeeklySummaryTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetWorkloadRecommendationTool;
import com.delaynomore.backend.domain.ai.agent.tools.UpdatePlanTasksTool;
import com.delaynomore.backend.domain.ai.dto.AgentCatalogResponse;
import com.delaynomore.backend.domain.ai.dto.AgentToolResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.domain.plan.service.WorkloadRecommendationService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 카탈로그 응답 검증 — "누가 응대하고(profile) 무엇을 할 수 있는가(tools)"가 상태 하나에서
 * 함께 파생되는지. 도구 노출 자체는 AgentToolRegistryTest가 상세히 다루므로, 여기서는
 * 프로필과 도구 목록이 같은 상태를 보고 있는지(어긋난 조합이 안 나오는지)에 집중한다.
 */
class AgentToolCatalogServiceTest {

    private static final String OWNER = "guest-1234abcd";

    private final PlanService planService = mock(PlanService.class);
    private final ReflectionService reflectionService = mock(ReflectionService.class);
    private final WorkloadRecommendationService recommendationService = mock(WorkloadRecommendationService.class);

    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(
            new GetTodayTasksTool(planService),
            new GetWeeklySummaryTool(planService),
            new GetReflectionHistoryTool(reflectionService),
            new GetWorkloadRecommendationTool(recommendationService),
            new UpdatePlanTasksTool(),
            new CarryOverTool(planService)));

    private final AgentToolCatalogService service = new AgentToolCatalogService(registry, planService);

    private void givenStoredPlan(long id, PlanStatus status) {
        Plan plan = new Plan(id, OWNER, "정보처리기사 실기", 3, 2, "초급", Map.of(), status.name(),
                null, null, "2026-07-27", "2026-07-29", "2026-07-27T00:00:00Z", 1L, null);
        when(planService.getPlan(id, OWNER)).thenReturn(PlanResponse.from(plan));
    }

    private static List<String> toolNames(AgentCatalogResponse response) {
        return response.tools().stream().map(AgentToolResponse::name).toList();
    }

    @Test
    @DisplayName("planId 없음(보관 전 초안) → 코치 프로필 + 전체 6종 도구")
    void 초안은_코치와_전체_도구() {
        AgentCatalogResponse response = service.list(null, OWNER);

        assertThat(response.profile().name()).isEqualTo("CHECKLIST_COACH");
        assertThat(response.profile().label()).isEqualTo("체크리스트 완성 코치");
        assertThat(toolNames(response)).hasSize(6).contains("update_plan_tasks", "carry_over_tasks");
    }

    @Test
    @DisplayName("고정 계획 → 전문 에이전트(저장본 goalName 특화) + 수정 도구 제외 5종")
    void 고정은_전문가와_수정_제외_도구() {
        givenStoredPlan(7L, PlanStatus.CONFIRMED);

        AgentCatalogResponse response = service.list(7L, OWNER);

        assertThat(response.profile().name()).isEqualTo("DOMAIN_EXPERT");
        assertThat(response.profile().label()).isEqualTo("정보처리기사 실기 전문 에이전트");
        assertThat(toolNames(response)).hasSize(5)
                .contains("carry_over_tasks")
                .doesNotContain("update_plan_tasks");
    }

    @Test
    @DisplayName("종결 계획 → 회고 도우미 + 읽기 도구 4종")
    void 종결은_회고_도우미와_읽기_도구() {
        givenStoredPlan(7L, PlanStatus.COMPLETED);

        AgentCatalogResponse response = service.list(7L, OWNER);

        assertThat(response.profile().name()).isEqualTo("RETRO_COMPANION");
        assertThat(response.profile().label()).isEqualTo("회고 도우미");
        assertThat(toolNames(response)).hasSize(4)
                .doesNotContain("update_plan_tasks", "carry_over_tasks");
    }

    @Test
    @DisplayName("접근할 수 없는 계획은 초안으로 강등 — 프로필과 도구가 함께 강등된다")
    void 접근불가_계획은_초안으로_강등() {
        when(planService.getPlan(99L, OWNER)).thenThrow(new BusinessException(ErrorCode.PLAN_NOT_FOUND));

        AgentCatalogResponse response = service.list(99L, OWNER);

        // 프로필만 코치로 남고 도구는 고정 기준이라면(또는 반대) 권한 증명이 무너진다 — 함께 움직여야 한다
        assertThat(response.profile().name()).isEqualTo("CHECKLIST_COACH");
        assertThat(toolNames(response)).hasSize(6);
    }
}
