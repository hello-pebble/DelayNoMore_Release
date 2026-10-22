package com.delaynomore.backend.domain.points.entity;

/**
 * 원장 계정 이름 규칙(v0.31.0). 소유자 계정과 시스템 계정이 같은 컬럼에 살기 때문에,
 * 이름을 만드는 곳은 여기 하나여야 한다 — 흩어지면 오타 하나가 조용히 새 계정을 만든다.
 *
 * <p>소유자 키(게스트 ID·사용자 UUID)와 충돌하지 않도록 시스템 계정에는 콜론이 들어간다.
 * 게스트 ID 형식은 영문·숫자·하이픈 8~64자라(GUEST_ID_INVALID) 콜론을 포함할 수 없고,
 * 사용자 UUID도 마찬가지다.
 */
public final class PointAccounts {

    /** 발행 계정 — 신규 지급이 여기서 나간다. 잔액의 절댓값 = 지금까지 발행한 총량. */
    public static final String ISSUANCE = "system:issuance";
    /** 기초잔액의 상대 계정 — V12 백필 전용(원장 이전 세계의 몫). */
    public static final String OPENING = "system:opening";
    /**
     * 소각 계정 — 챌린지 정산에서 아무에게도 가지 않은 몫(완주자가 없을 때의 풀 전액, 균등 분배의
     * 나머지)이 여기로 온다(v0.34.0). 소멸도 사라지는 게 아니라 계정이 바뀌는 이동이라 원장 합계 0이
     * 유지되고, 정산이 끝난 챌린지 예치 계정은 0으로 닫힌다. {@link #ISSUANCE}로 되돌리지 않는 이유:
     * 그 잔액은 "지금까지 발행한 총량"이라는 한 가지 뜻이어야 한다.
     */
    public static final String BURN = "system:burn";

    // [과거 계정] escrow:plan:<id>는 v0.32.0 목표 예치의 계정이었다(v0.33.0에서 제거, 미정산분은 V14가
    // 환급). system:burn은 그때 만들어졌다가 v0.34.0부터 챌린지 소멸에 쓰인다.

    private static final String ESCROW_CHALLENGE_PREFIX = "escrow:challenge:";

    private PointAccounts() {
    }

    /**
     * 챌린지별 예치 계정. 챌린지마다 계정을 나누는 이유는 정산 후에 남는 잔액이 곧 그 챌린지의
     * 소멸분(정수 나눗셈 나머지)이 되어, 미정산 예치금과 섞이지 않기 때문이다.
     */
    public static String escrowOfChallenge(long challengeId) {
        return ESCROW_CHALLENGE_PREFIX + challengeId;
    }
}
