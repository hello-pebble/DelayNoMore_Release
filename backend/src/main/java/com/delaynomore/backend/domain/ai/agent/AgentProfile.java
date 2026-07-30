package com.delaynomore.backend.domain.ai.agent;

import com.delaynomore.backend.domain.plan.entity.PlanStatus;

/**
 * 에이전트 프로필 — 계획 상태가 고르는 <b>페르소나</b>. v0.15.0에서 상태가 도구 집합을 골랐다면
 * (AgentToolRegistry), v0.17.0부터는 시스템 프롬프트까지 함께 고른다: 프로필 = 프롬프트 + 도구 집합.
 *
 * <p>단, <b>도구 권한은 여기서 재선언하지 않는다.</b> 어떤 도구가 노출되는지는 계속
 * {@link PlanStatus}의 능력 플래그({@code allowsStructuralEdit} 등)가 단독 소유하고, 프로필은
 * 프롬프트(말투·역할·잠금 안내)만 고른다. 같은 상태를 두 곳이 해석하면 표가 어긋나는 순간
 * "프롬프트는 수정 가능하다는데 도구가 없다"류의 모순이 생기기 때문이다 — 권한 표를 깨뜨리면
 * 평가 하네스의 permissionBreached 가드가 빌드를 깨뜨린다는 안전망도 그대로 유지된다.
 *
 * <p>상태 → 프로필 매핑:
 * <pre>
 *   DRAFT                 → CHECKLIST_COACH  체크리스트를 함께 완성하는 코치 (기존 페르소나)
 *   CONFIRMED             → DOMAIN_EXPERT    목표 영역 전문 에이전트 — goalName으로 특화
 *   COMPLETED · CANCELLED → RETRO_COMPANION  끝난 계획을 돌아보고 다음 계획을 준비하는 회고 도우미
 * </pre>
 *
 * <p>이름에 관해: Spring의 {@code @Profile}(저장소 구현 선택)과 단어가 겹치지만, 패키지도 용도도
 * 달라 혼동 여지가 없다고 판단해 로드맵 문서가 예고한 이름(AgentProfile)을 그대로 쓴다.
 */
public enum AgentProfile {

    CHECKLIST_COACH("체크리스트 완성 코치"),
    DOMAIN_EXPERT("목표 영역 전문 에이전트"),
    RETRO_COMPANION("회고 도우미");

    private final String label;

    AgentProfile(String label) {
        this.label = label;
    }

    /**
     * 상태 → 프로필 매핑의 단일 지점. switch가 enum 전수를 다루므로 PlanStatus에 상태가
     * 추가되면 여기가 컴파일 오류로 알려준다 — 매핑 누락이 조용히 코치로 흘러가지 않는다.
     */
    public static AgentProfile forStatus(PlanStatus status) {
        return switch (status) {
            case DRAFT -> CHECKLIST_COACH;
            case CONFIRMED -> DOMAIN_EXPERT;
            case COMPLETED, CANCELLED -> RETRO_COMPANION;
        };
    }

    public String label() {
        return label;
    }

    /**
     * 화면·이벤트용 표시 라벨. DOMAIN_EXPERT만 목표명으로 특화한다("정보처리기사 실기 전문
     * 에이전트") — 인계의 핵심이 "그 목표의" 전문가라는 점이기 때문이다. goalName이 비어 있으면
     * 기본 라벨로 폴백한다(보관 전 초안 등 목표명이 없는 경로가 실제로 있다).
     */
    public String displayLabel(String goalName) {
        if (this == DOMAIN_EXPERT && goalName != null && !goalName.isBlank()) {
            return goalName.trim() + " 전문 에이전트";
        }
        return label;
    }
}
