package com.delaynomore.backend.domain.ai.dto;

import com.delaynomore.backend.domain.ai.agent.AgentTool;

import java.util.Map;

/**
 * 도구 카탈로그 한 줄. {@code GET /api/v1/ai/agent/tools}가 <b>현재 계획 상태에서 실제로
 * 모델에게 노출되는</b> 도구만 내려주므로, 이 응답을 상태별로 비교하면 권한 모델이 눈에 보인다
 * (고정하면 update_plan_tasks가 사라지고, 종결하면 carry_over_tasks까지 사라진다).
 *
 * 프롬프트에 실리는 목록과 같은 소스(AgentToolRegistry)를 쓴다 — 두 경로가 갈라지면
 * "화면에 보이는 도구"와 "모델이 받은 도구"가 어긋나 증빙으로서 의미가 없어진다.
 */
public record AgentToolResponse(
        String name,
        String description,
        boolean mutating,
        Map<String, Object> parameters
) {

    public static AgentToolResponse from(AgentTool tool) {
        return new AgentToolResponse(tool.name(), tool.description(), tool.mutating(),
                tool.parametersSchema());
    }
}
