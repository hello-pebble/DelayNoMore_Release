package com.delaynomore.backend.domain.points.dto;

import com.delaynomore.backend.domain.points.entity.PointLedgerEntry;

import java.util.List;

/**
 * 거래 내역 응답(v0.31.0) — 지금 잔액과, 그 잔액이 어떻게 만들어졌는지의 최근 기록.
 *
 * @param balance    현재 잔액(지갑 캐시 값)
 * @param ledgerSum  원장 합계. 정상이라면 balance와 같다 — <b>일부러 둘 다 내려준다</b>:
 *                   어긋나면 화면에서 바로 드러나야 하고, 그게 이 원장을 둔 이유이기 때문이다.
 * @param entries    최신순. 상한은 서버가 정한다(페이지네이션 없음 — 데모 규모).
 */
public record PointLedgerResponse(int balance, int ledgerSum, List<Entry> entries) {

    /**
     * @param label        사유의 한국어 표기 — 프론트에 사전을 두지 않는다(표기 소유권도 서버).
     * @param balanceAfter 이 거래 직후의 잔액. 원장이 진실이므로 현재 잔액에서 최신 거래부터
     *                     거꾸로 빼며 정확히 복원할 수 있다(추정이 아니다).
     */
    public record Entry(
            long id,
            String kind,
            String label,
            int amount,
            int balanceAfter,
            String refType,
            String refId,
            String createdAt
    ) {
    }

    /**
     * 현재 잔액에서 거꾸로 걸어 거래별 잔액을 복원한다. entries는 최신순이어야 한다 —
     * 가장 최근 거래 직후의 잔액이 곧 현재 잔액이고, 그 앞으로는 각 거래의 금액을 되돌린다.
     */
    public static PointLedgerResponse of(int balance, int ledgerSum, List<PointLedgerEntry> entries) {
        int running = balance;
        List<Entry> rows = new java.util.ArrayList<>(entries.size());
        for (PointLedgerEntry entry : entries) {
            rows.add(new Entry(entry.id(), entry.kind().name(), entry.kind().label(), entry.amount(),
                    running, entry.refType(), entry.refId(), entry.createdAt()));
            running -= entry.amount();
        }
        return new PointLedgerResponse(balance, ledgerSum, List.copyOf(rows));
    }
}
