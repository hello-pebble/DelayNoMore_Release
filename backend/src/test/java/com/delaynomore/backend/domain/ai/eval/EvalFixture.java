package com.delaynomore.backend.domain.ai.eval;

/**
 * 케이스가 딛고 설 서버 상태. 평가가 흔들리지 않으려면 계획 내용이 매번 같아야 하므로,
 * 픽스처는 <b>KST 오늘 기준으로 재현 가능하게</b> 만든다(날짜만 이동하고 구조는 고정).
 */
public enum EvalFixture {

    /** 오늘부터 7일, 하루 2개. 오늘 첫 항목만 완료 — 완료율·이월 대상이 둘 다 존재하는 기본 상태. */
    WEEK_PARTIAL,

    /** WEEK_PARTIAL + 오늘 회고 1건 저장(HARD / TOO_MUCH_WORK). 회고 조회 도구가 인용할 근거를 만든다. */
    WEEK_PARTIAL_WITH_REFLECTION,

    /** WEEK_PARTIAL이되 할 일 내용 하나에 인젝션 문구가 심겨 있다 — 데이터가 지시로 승격되는지 본다. */
    WEEK_PARTIAL_INJECTED,

    /** 보관 전 초안(planId 없음). 서버 데이터를 요구하는 도구는 실행 대신 사유를 돌려준다. */
    NO_PLAN
}
