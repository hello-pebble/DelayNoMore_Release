package com.delaynomore.backend.domain.ai.eval;

import com.delaynomore.backend.domain.plan.entity.PlanStatus;

import java.util.List;

/**
 * 평가 케이스 하나 — "이 상태에서 이 말을 들었을 때 어떤 도구를 골라야 하는가".
 *
 * <p>답변 문장의 품질은 채점하지 않는다. 문장 채점은 사람 판단이나 심판 모델이 필요해 비싸고
 * 흔들리는 반면, <b>도구 선택은 정답이 이산적</b>이라 회귀 신호로 훨씬 날카롭다. 게다가 이
 * 프로젝트가 주장하는 것("상태가 도구 권한을 결정한다")이 정확히 이 축에 놓여 있다.
 *
 * @param expectTools  실제로 <b>실행</b>돼야 하는 도구들(부분집합 검사 — 다른 읽기 도구를 더 불러도 통과)
 * @param forbidTools  <b>그 상태에서 노출되지 않아야</b> 하는 도구들. 실행됐다면 권한 표가 깨진
 *                     것이므로 빌드를 깨뜨린다. 모델이 부르려 <i>시도</i>만 하고 서버가 막은 것은
 *                     실패가 아니라 경고다 — 권한 모델은 시도를 막는 게 아니라 실행을 막는 설계다.
 * @param avoidTools   <b>노출돼 있지만 이 요청에는 부적절한</b> 도구들. "막혀 있어서 안 하는 것"과
 *                     "할 수 있는데 하지 말아야 하는 것"은 다른 채점이다 — 후자는 통과율에 반영되는
 *                     실패이지 설계 결함이 아니므로 빌드를 깨뜨리지 않는다.
 *                     <p>실측에서 나온 필요: 고정 계획에 "항목을 추가해줘"라고 했을 때, 수정 도구가
 *                     없자 모델이 <b>이월 도구로 계획을 실제로 바꿨다</b>. 이월은 CONFIRMED에서 정상
 *                     노출되는 도구라 {@code forbidTools}로는 표현할 수 없고(넣으면 빌드가 깨진다),
 *                     그대로 두면 "요청하지 않은 변경"이 채점되지 않는다.
 * @param expectNoTools 도구를 아예 부르지 않아야 하는 케이스(인사·감사 등). 여기서는 시도 자체가 실패다.
 */
public record EvalCase(
        String id,
        String description,
        EvalFixture fixture,
        PlanStatus status,
        String message,
        List<String> expectTools,
        List<String> forbidTools,
        List<String> avoidTools,
        boolean expectNoTools) {

    /** {@code avoidTools}가 없는 케이스를 짧게 쓰기 위한 생성자 — 대부분의 케이스가 여기 해당한다. */
    public EvalCase(String id, String description, EvalFixture fixture, PlanStatus status, String message,
                    List<String> expectTools, List<String> forbidTools, boolean expectNoTools) {
        this(id, description, fixture, status, message, expectTools, forbidTools, List.of(), expectNoTools);
    }

    // JSON에서 생략된 목록을 null로 두면 채점기가 매번 null을 방어해야 한다 — 읽는 시점에 한 번만 정규화한다.
    public EvalCase {
        expectTools = expectTools == null ? List.of() : List.copyOf(expectTools);
        forbidTools = forbidTools == null ? List.of() : List.copyOf(forbidTools);
        avoidTools = avoidTools == null ? List.of() : List.copyOf(avoidTools);
    }
}
