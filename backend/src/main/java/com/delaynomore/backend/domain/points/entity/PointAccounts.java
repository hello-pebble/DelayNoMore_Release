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

    /** 시스템·예치 계정 여부 — 거래 내역은 소유자 계정만 보여준다. */
    public static boolean isSystem(String account) {
        return account != null && account.contains(":");
    }
}
