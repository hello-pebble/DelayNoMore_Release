package com.delaynomore.backend.domain.points.entity;

/**
 * 기표 요청(v0.31.0) — "누구에게서 누구에게로 얼마가, 왜, 무엇 때문에" 한 덩어리.
 *
 * <p>원장에 쓰는 유일한 입력 형태다. 한 줄씩 넣는 API를 두지 않는 이유는 단순하다 — 한 줄만
 * 넣을 수 있으면 합이 0이 아닌 상태를 만들 수 있고, 그 순간 원장은 원장이 아니게 된다.
 *
 * @param txKey  멱등 키. 도메인에서 파생한다(예: {@code join:7:guest-1}) — 재시도가 같은 키를
 *               만들어내므로 "이미 기록했나"를 묻지 않아도 중복 기표가 막힌다(UNIQUE 인덱스).
 * @param amount 반드시 양수. 방향은 from/to가 말한다.
 */
public record PointTransfer(
        String txKey,
        String from,
        String to,
        int amount,
        PointTxKind kind,
        String refType,
        String refId,
        String createdAt
) {

    public PointTransfer {
        if (amount <= 0) {
            // 0원 이동은 기표하지 않는다(정산의 미완주자 payout=0 등) — 호출부가 거르며,
            // 여기서는 계약 위반으로 본다. 음수는 방향을 뒤집는 우회로가 되므로 금지.
            throw new IllegalArgumentException("이동 금액은 양수여야 합니다: " + amount);
        }
        if (from == null || to == null || from.equals(to)) {
            throw new IllegalArgumentException("출발·도착 계정이 서로 달라야 합니다: " + from + " → " + to);
        }
    }
}
