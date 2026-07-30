package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.agent.AgentProfile;
import com.delaynomore.backend.domain.ai.agent.AgentToolRegistry;
import com.delaynomore.backend.domain.ai.dto.AgentCatalogResponse;
import com.delaynomore.backend.domain.ai.dto.AgentToolResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 도구 카탈로그 조회 — "이 계획 상태에서 에이전트가 무엇을 할 수 있는가"에 답한다.
 * v0.17.0부터는 "누가 응대하는가"(프로필)도 함께 답한다.
 *
 * 상태 판정을 {@link AgentRunner#buildContext}와 같은 방식(서버 저장본에서 읽기)으로 맞추는
 * 것이 중요하다. 카탈로그가 보여주는 목록과 루프가 실제로 모델에게 준 목록이 어긋나면,
 * 이 API로 권한 모델을 증명한다는 목적 자체가 무너진다.
 *
 * <p>프로필 라벨의 goalName만은 저장본에서 읽는다 — 이 API에는 요청 바디가 없어서다. 루프
 * (요청 바디 goalName 사용)와의 이 미세한 비대칭은 라벨 표기에만 영향이 있고 권한과는 무관하다
 * (docs/AGENT.md 프로필 절 참고).
 */
@Service
@RequiredArgsConstructor
public class AgentToolCatalogService {

    private final AgentToolRegistry toolRegistry;
    private final PlanService planService;

    /**
     * planId가 없거나(보관 전 초안) 접근할 수 없는 계획이면 DRAFT 기준으로 답한다 —
     * 없는 계획의 상태를 노출하지 않으면서(404를 내지 않고) "새 계획이면 이만큼 쓸 수 있다"는
     * 정보를 준다. 실제 실행 시에도 같은 강등 규칙이 적용되므로 두 경로가 일치한다.
     */
    public AgentCatalogResponse list(Long planId, String owner) {
        Resolved resolved = resolve(planId, owner);
        List<AgentToolResponse> tools = toolRegistry.toolsFor(resolved.status()).stream()
                .map(AgentToolResponse::from)
                .toList();
        AgentProfile profile = AgentProfile.forStatus(resolved.status());
        return new AgentCatalogResponse(
                AgentCatalogResponse.ProfileInfo.from(profile, resolved.goalName()), tools);
    }

    private Resolved resolve(Long planId, String owner) {
        if (planId == null) {
            return new Resolved(PlanStatus.DRAFT, null);
        }
        try {
            PlanResponse plan = planService.getPlan(planId, owner);
            return new Resolved(PlanStatus.fromStored(plan.status()), plan.goalName());
        } catch (RuntimeException e) {
            return new Resolved(PlanStatus.DRAFT, null);
        }
    }

    private record Resolved(PlanStatus status, String goalName) {
    }
}
