package com.delaynomore.backend.domain.points.entity;

/**
 * 포인트가 움직인 사유(v0.31.0) — 원장 한 줄의 의미를 결정한다.
 *
 * <p>여기 없는 사유로는 포인트가 움직일 수 없다는 것이 이 enum의 요점이다. 새 적립 규칙을
 * 붙일 때(로드맵 2단계) 값을 하나 더하고 그 값을 쓰는 이동을 기표하면, 거래 내역 화면과
 * 원장 검증은 코드 변경 없이 그대로 따라온다.
 */
public enum PointTxKind {

    /** V12 마이그레이션 시점의 기초잔액 — 원장 이전의 잔액을 한 줄로 옮겨 적은 것. */
    OPENING_BALANCE("기초잔액"),
    /** 신규 지갑 최초 생성 시의 지급 — 출처는 발행 계정이다. */
    SIGNUP_BONUS("신규 지급"),
    /** 챌린지 참가비 — 소유자에서 그 챌린지의 예치 계정으로. */
    CHALLENGE_ENTRY("챌린지 참가비"),
    /** 완주 정산 배당 — 예치 계정에서 완주자에게. */
    CHALLENGE_PAYOUT("챌린지 배당"),
    /** 환불 — 완주자가 없거나 모집이 미달로 마감된 경우 예치 계정에서 되돌려준다. */
    CHALLENGE_REFUND("챌린지 환불");

    // [게스트 흡수에 사유가 없는 이유] 로그인 시 게스트 데이터가 계정으로 흡수될 때 원장은
    // 이동을 기표하지 않고 **계정 이름 자체를 갈아끼운다**(reassignAccount). 계획·회고·참가
    // 레코드가 이미 그렇게 옮겨지기 때문이고(AuthRepository.absorbGuest), 그래야 사용자가
    // 게스트 시절의 거래 내역까지 이어서 본다. 이동이 아니므로 사유도 없다.

    private final String label;

    PointTxKind(String label) {
        this.label = label;
    }

    /** 거래 내역 화면에 그대로 쓰이는 한국어 라벨 — 표기의 소유권도 서버에 둔다(프론트 사전 금지). */
    public String label() {
        return label;
    }
}
