package com.delaynomore.backend.domain.ai.agent;

import com.delaynomore.backend.domain.plan.entity.PlanStatus;

import java.util.Map;

/**
 * 에이전트 루프 한 번의 실행 문맥. 소유자·세션·대상 계획처럼 도구가 공통으로 필요로 하는 값을
 * 모아 전달하고, 도구가 계획을 바꾸면 그 결과를 여기에 누적한다.
 *
 * record가 아닌 가변 클래스인 이유: 한 번의 대화에서 도구가 여러 번 계획을 고칠 수 있고
 * (예: 3일차 수정 → 5일차 추가), 그때마다 "직전까지 반영된 계획"을 다음 도구가 봐야 한다.
 * 루프는 단일 스레드에서 순차 실행되므로 동기화는 필요 없다.
 *
 * 소유권 주의: owner(게스트 ID)는 컨트롤러가 X-Guest-Id에서 해석한 값이고, 도구는 반드시 이
 * 값을 서비스에 그대로 넘겨야 한다 — 모델이 인자로 준 값을 소유자로 쓰면 남의 계획에 접근할
 * 수 있으므로, 어떤 도구도 owner를 인자로 받지 않는다.
 */
public final class AgentContext {

    private final String owner;
    private final String sessionId;
    private final Long planId;
    private final PlanStatus status;
    private final String goalName;
    private final int dailyHours;

    private Map<String, Object> currentTasks;
    private boolean planChanged;
    private boolean refreshRequested;

    public AgentContext(String owner, String sessionId, Long planId, PlanStatus status,
                        String goalName, int dailyHours, Map<String, Object> currentTasks) {
        this.owner = owner;
        this.sessionId = sessionId;
        this.planId = planId;
        this.status = status;
        this.goalName = goalName;
        this.dailyHours = dailyHours;
        this.currentTasks = currentTasks == null ? Map.of() : currentTasks;
    }

    public String owner() {
        return owner;
    }

    public String sessionId() {
        return sessionId;
    }

    public Long planId() {
        return planId;
    }

    // 아직 보관되지 않은 초안(첫 생성 직후)은 planId가 없다 — 서버 저장을 건드리는 도구는
    // 이 경우 실행 대신 실패를 돌려줘 모델이 사용자에게 설명하게 한다.
    public boolean hasPlanId() {
        return planId != null;
    }

    public PlanStatus status() {
        return status;
    }

    // 상태에서 파생되는 프로필(v0.17.0). 별도 필드로 들지 않는 이유: status가 유일한 입력이라
    // 저장하면 둘이 어긋날 수 있는 상태가 하나 늘어날 뿐이다.
    public AgentProfile profile() {
        return AgentProfile.forStatus(status);
    }

    public String goalName() {
        return goalName;
    }

    public int dailyHours() {
        return dailyHours;
    }

    public Map<String, Object> currentTasks() {
        return currentTasks;
    }

    /**
     * 아직 <b>서버에 저장되지 않은</b> 계획 변경을 누적한다(대화로 초안을 고치는 경우).
     * 루프 종료 시 병합된 전체 계획을 plan 이벤트로 한 번 내보내면, 프론트가 기존
     * /chats/stream과 똑같이 초안으로 채택하고 디바운스 PUT이 영속화한다 — 계약도 저장
     * 경로도 그대로라 새 동기화 규칙이 생기지 않는다.
     */
    public void applyTasks(Map<String, Object> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        this.currentTasks = tasks;
        this.planChanged = true;
    }

    public boolean planChanged() {
        return planChanged;
    }

    /**
     * 도구가 <b>서버 상태를 이미 바꾼</b> 경우(이월처럼 도메인 액션이 직접 영속화한 경우)에
     * 호출한다. 이때는 plan 이벤트로 초안을 덮어쓰면 안 된다 — 프론트가 그 값을 다시 PUT하려
     * 들고, 고정된 계획에서는 그 PUT이 409로 튕긴다. 대신 "서버에서 다시 읽어라"만 알리고
     * 프론트는 기존 applyServerPlan 경로를 그대로 탄다.
     */
    public void requestRefresh() {
        this.refreshRequested = true;
    }

    public boolean refreshRequested() {
        return refreshRequested;
    }
}
