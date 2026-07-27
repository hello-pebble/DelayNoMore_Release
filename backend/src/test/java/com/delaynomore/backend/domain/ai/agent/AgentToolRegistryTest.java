package com.delaynomore.backend.domain.ai.agent;

import com.delaynomore.backend.domain.ai.agent.tools.CarryOverTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetReflectionHistoryTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetTodayTasksTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetWeeklySummaryTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetWorkloadRecommendationTool;
import com.delaynomore.backend.domain.ai.agent.tools.UpdatePlanTasksTool;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.domain.plan.service.WorkloadRecommendationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 에이전트 권한 모델의 회귀 방지 — "고정하면 수정 도구가 사라지고, 종결하면 이월 도구까지
 * 사라진다"가 코드로 고정돼 있는지 검증한다.
 *
 * 이 테스트가 지키는 것은 도구 목록 자체가 아니라 <b>상태 전이표와의 연동</b>이다.
 * PlanStatus의 능력 플래그가 바뀌면 여기서 먼저 깨져야 한다 — 도구가 자체 판정 로직을 갖게
 * 되는(= 소유권이 갈라지는) 회귀를 막는 것이 목적이다.
 */
class AgentToolRegistryTest {

    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(
            new GetTodayTasksTool(mock(PlanService.class)),
            new GetWeeklySummaryTool(mock(PlanService.class)),
            new GetReflectionHistoryTool(mock(ReflectionService.class)),
            new GetWorkloadRecommendationTool(mock(WorkloadRecommendationService.class)),
            new UpdatePlanTasksTool(),
            new CarryOverTool(mock(PlanService.class))));

    private List<String> namesFor(PlanStatus status) {
        return registry.toolsFor(status).stream().map(AgentTool::name).toList();
    }

    @Test
    void toolsFor_초안_수정과이월을포함한전체노출() {
        assertThat(namesFor(PlanStatus.DRAFT)).containsExactlyInAnyOrder(
                "get_today_tasks", "get_weekly_summary", "get_reflection_history",
                "get_workload_recommendation", "update_plan_tasks", "carry_over_tasks");
    }

    @Test
    void toolsFor_고정_수정도구만사라지고이월은남는다() {
        // 고정(CONFIRMED)은 "내용 재협상 금지, 실행은 계속" — allowsStructuralEdit=false지만
        // allowsCarryOver=true인 상태이므로, 이월은 실행 단계 액션으로 남아야 한다(v0.14.1 판단).
        List<String> names = namesFor(PlanStatus.CONFIRMED);

        assertThat(names).doesNotContain("update_plan_tasks");
        assertThat(names).contains("carry_over_tasks", "get_today_tasks", "get_weekly_summary");
    }

    @Test
    void toolsFor_종결_읽기전용도구만남는다() {
        // 종결(COMPLETED·CANCELLED)은 전면 잠금 — 변이 도구가 하나도 없어야 한다.
        for (PlanStatus terminal : List.of(PlanStatus.COMPLETED, PlanStatus.CANCELLED)) {
            assertThat(registry.toolsFor(terminal))
                    .as("%s 상태", terminal)
                    .noneMatch(AgentTool::mutating);
            assertThat(namesFor(terminal)).containsExactlyInAnyOrder(
                    "get_today_tasks", "get_weekly_summary",
                    "get_reflection_history", "get_workload_recommendation");
        }
    }

    @Test
    void find_노출되지않은도구는이름이맞아도찾지못한다() {
        // 모델이 이전 턴의 기억이나 환각으로 노출되지 않은 도구를 불러도 실행되지 않아야 한다.
        assertThat(registry.find("update_plan_tasks", PlanStatus.DRAFT)).isPresent();
        assertThat(registry.find("update_plan_tasks", PlanStatus.CONFIRMED)).isEmpty();
        assertThat(registry.find("carry_over_tasks", PlanStatus.COMPLETED)).isEmpty();
        assertThat(registry.find("존재하지_않는_도구", PlanStatus.DRAFT)).isEmpty();
    }

    @Test
    void specsFor_노출도구와같은목록을OpenAI형식으로내린다() {
        // 프롬프트에 실리는 tools 배열과 카탈로그 API가 같은 소스를 보는지 — 어긋나면
        // "화면에 보이는 도구 ≠ 모델이 받은 도구"가 되어 증빙으로서 의미가 없어진다.
        List<Map<String, Object>> specs = registry.specsFor(PlanStatus.CONFIRMED);

        assertThat(specs).hasSameSizeAs(registry.toolsFor(PlanStatus.CONFIRMED));
        assertThat(specs).allSatisfy(spec -> {
            assertThat(spec.get("type")).isEqualTo("function");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) spec.get("function");
            assertThat(function).containsKeys("name", "description", "parameters");
        });
        assertThat(specs).noneSatisfy(spec -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) spec.get("function");
            assertThat(function.get("name")).isEqualTo("update_plan_tasks");
        });
    }
}
