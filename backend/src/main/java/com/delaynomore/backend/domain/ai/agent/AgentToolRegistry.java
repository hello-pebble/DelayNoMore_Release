package com.delaynomore.backend.domain.ai.agent;

import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 에이전트에게 줄 도구를 <b>계획 상태가 결정</b>하게 하는 곳. 이 프로젝트의 권한 모델이 한 줄로
 * 요약되는 지점이다:
 *
 * <pre>
 *   DRAFT      → 읽기 4종 + update_plan_tasks + carry_over_tasks   (자유 수정)
 *   CONFIRMED  → 읽기 4종 +                     carry_over_tasks   (수정 불가, 실행은 가능)
 *   COMPLETED  → 읽기 4종                                          (전면 잠금)
 *   CANCELLED  → 읽기 4종                                          (전면 잠금)
 * </pre>
 *
 * 이 표를 여기서 새로 선언하지 않는다는 점이 중요하다 — 각 도구가 {@link PlanStatus}의 기존
 * 능력 플래그를 참조하고, 레지스트리는 필터링만 한다. 즉 상태 수명주기의 소스오브트루스는
 * 여전히 PlanStatus 하나이고, 에이전트 권한은 그 표의 <b>결과</b>다. 상태 규칙이 바뀌면
 * 에이전트 권한도 자동으로 따라온다.
 *
 * 프롬프트로 "고정된 계획은 수정하지 마세요"라고 부탁하는 대신 도구를 주지 않는 방식이라,
 * 모델이 규칙을 무시하려 해도 호출할 함수가 존재하지 않는다.
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> byName;
    private final List<AgentTool> ordered;

    // 스프링이 AgentTool 구현체를 전부 주입한다. 선언 순서에 의존하지 않도록 이름으로 색인하고,
    // 노출 순서는 주입 순서를 유지한다(LinkedHashMap — 프롬프트가 실행마다 흔들리지 않게).
    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> index = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            index.put(tool.name(), tool);
        }
        this.byName = Map.copyOf(index);
        this.ordered = List.copyOf(index.values());
    }

    // 해당 상태에서 모델에게 노출할 도구들. 프롬프트 조립과 도구 카탈로그 API가 같은 목록을 본다
    // (화면에 보이는 도구 = 모델이 받은 도구 — 두 경로가 어긋나면 증빙이 무의미해진다).
    public List<AgentTool> toolsFor(PlanStatus status) {
        return ordered.stream()
                .filter(tool -> tool.isAvailableFor(status))
                .toList();
    }

    /**
     * 모델이 부른 이름으로 도구를 찾되, <b>해당 상태에서 노출된 것만</b> 돌려준다.
     * 노출 목록과 실행 가능 목록을 같은 기준으로 두 번 판정하는 이유는 방어다 — 모델이 이전
     * 턴의 기억이나 환각으로 노출되지 않은 도구를 부르더라도 실행되지 않는다.
     */
    public Optional<AgentTool> find(String name, PlanStatus status) {
        AgentTool tool = byName.get(name);
        return (tool != null && tool.isAvailableFor(status)) ? Optional.of(tool) : Optional.empty();
    }

    // OpenAI function calling 형식의 도구 정의 목록 — 요청 바디의 tools 필드에 그대로 실린다.
    public List<Map<String, Object>> specsFor(PlanStatus status) {
        return toolsFor(status).stream().map(AgentToolRegistry::toSpec).toList();
    }

    private static Map<String, Object> toSpec(AgentTool tool) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.parametersSchema()));
    }
}
