package com.delaynomore.backend.domain.points.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.points.entity.PlanDeposit;

/**
 * 목표 예치 응답(v0.32.0).
 *
 * <p>미정산이면 <b>지금 종결하면 얼마를 돌려받는지</b>를 함께 내려준다({@code projectedRefund}) —
 * 예치의 요점은 "지금 상태로는 얼마를 잃는가"가 보이는 것이고, 그 계산 규칙의 소유권도 서버다.
 * 정산이 끝났으면 실제 환급액({@code refunded})이 채워지고 projected는 그 값과 같다.
 *
 * @param balance 응답 시점의 내 잔액 — 예치 직후 화면이 다시 조회하지 않아도 되게 함께 준다.
 */
public record PlanDepositResponse(
        long planId,
        int amount,
        int donePercent,
        int projectedRefund,
        Integer refunded,
        String settledAt,
        String createdAt,
        int balance
) {

    public static PlanDepositResponse of(PlanDeposit deposit, Plan plan, int balance) {
        Plan.TaskCounts counts = plan.countAllTasks();
        int percent = counts.total() == 0 ? 100
                : Math.round(counts.completed() * 100f / counts.total());
        int projected = deposit.settled() && deposit.refunded() != null
                ? deposit.refunded()
                : PlanDeposit.refundFor(deposit.amount(), counts.completed(), counts.total());
        return new PlanDepositResponse(deposit.planId(), deposit.amount(), percent, projected,
                deposit.refunded(), deposit.settledAt(), deposit.createdAt(), balance);
    }
}
