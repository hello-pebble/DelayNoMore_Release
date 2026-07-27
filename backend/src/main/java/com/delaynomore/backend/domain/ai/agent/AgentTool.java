package com.delaynomore.backend.domain.ai.agent;

import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 에이전트가 호출할 수 있는 도구 하나. 구현체는 <b>기존 서비스에 위임만</b> 한다 —
 * 규칙·검증·이력은 이미 도메인 서비스가 소유하고 있으므로 도구가 비즈니스 로직을 다시 쓰면
 * 소유권이 두 곳으로 갈라진다(v0.8.0부터 이어온 "규칙의 소유권은 서버" 원칙의 연장).
 *
 * 도구는 스프링 빈으로 등록되고 {@link AgentToolRegistry}가 모두 주입받아 상태별로 골라준다.
 */
public interface AgentTool {

    // 모델이 호출할 때 쓰는 이름. OpenAI 함수 호출 관례대로 snake_case.
    String name();

    // 모델이 "언제 이 도구를 쓸지" 판단하는 근거 — 프롬프트의 일부다. 한 줄로 용도를 분명히.
    String description();

    // 인자 JSON Schema(OpenAI function calling 형식). 인자가 없으면 빈 properties 객체.
    Map<String, Object> parametersSchema();

    // 실제 실행. args는 모델이 만든 값이라 신뢰할 수 없다 — 구현체가 직접 검증하고,
    // 잘못된 입력은 예외 대신 ToolResult.fail로 돌려줘 모델이 고쳐 다시 부르게 한다.
    ToolResult execute(JsonNode args, AgentContext context);

    /**
     * 이 상태의 계획에서 이 도구를 모델에게 <b>노출할지</b> 결정한다. 기본은 읽기 전용 도구로
     * 보고 항상 노출한다. 계획을 바꾸는 도구는 이 메서드를 재정의해 {@link PlanStatus}의
     * 능력 플래그(allowsStructuralEdit·allowsCarryOver)를 그대로 참조한다 —
     * 판정 기준을 여기서 새로 만들지 않는 것이 핵심이다(전이표가 단일 소유).
     *
     * 노출하지 않는다는 것은 프롬프트에서 도구 정의 자체가 빠진다는 뜻이다. 모델에게 "하지
     * 마세요"라고 부탁하는 게 아니라 호출할 수단을 주지 않는 것이라, 고정된 계획의 수정은
     * 구조적으로 불가능해진다.
     */
    default boolean isAvailableFor(PlanStatus status) {
        return true;
    }

    // 계획을 바꾸는 도구인지 — 도구 카탈로그 응답에 실어 "무엇이 읽기이고 무엇이 쓰기인지"를
    // 드러낸다. 노출 여부 판정에는 쓰지 않는다(그건 isAvailableFor의 몫).
    default boolean mutating() {
        return false;
    }
}
