package com.delaynomore.backend.domain.points.entity;

/**
 * 목표 예치(v0.32.0) — 고정한 계획에 자기 포인트를 걸어 둔 기록.
 *
 * @param refunded 정산 시 돌려받은 금액. {@code settledAt}이 null이면 이것도 null(미정산).
 */
public record PlanDeposit(
        long planId,
        String owner,
        int amount,
        String createdAt,
        String settledAt,
        Integer refunded
) {

    public boolean settled() {
        return settledAt != null;
    }

    /**
     * 달성률에 따른 환급액 — 규칙의 소유자는 서버다.
     *
     * <p>내림(정수 나눗셈)이라 <b>환급액은 절대 예치금을 넘지 않고</b>, 남는 몫은 소각된다.
     * 100%면 {@code completed == total}이라 정확히 전액이 돌아온다(내림이 손해를 만들지 않는다).
     * 할 일이 하나도 없는 계획은 달성률을 정의할 수 없어 전액 환급으로 본다 — 약속의 대상이
     * 없었던 셈이라 벌할 근거가 없다.
     */
    public static int refundFor(int amount, int completed, int total) {
        if (total <= 0) {
            return amount;
        }
        int done = Math.min(Math.max(completed, 0), total);
        return (int) ((long) amount * done / total);
    }
}
