package com.delaynomore.backend.domain.points.entity;

/**
 * 원장 한 줄(v0.31.0) — 한 계정에서 일어난 금액 변동 하나.
 *
 * <p>거래 하나는 이런 줄 <b>둘</b>로 이뤄진다(나가는 계정 음수 · 들어오는 계정 양수). 둘은 같은
 * {@code txKey}를 공유하고 합이 0이다.
 */
public record PointLedgerEntry(
        Long id,
        String txKey,
        String account,
        int amount,
        PointTxKind kind,
        String refType,
        String refId,
        String createdAt
) {
}
