package com.delaynomore.backend.domain.points.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 목표 예치 요청(v0.32.0). 범위(10~500)는 서비스가 판정한다 — 어노테이션 상수와 서비스 상수가
 * 두 벌로 갈라지는 것을 피하고, 위반 시 코드도 {@code DEPOSIT_AMOUNT_INVALID} 하나로 통일한다.
 */
public record PlanDepositRequest(
        @NotNull(message = "예치 금액(amount)이 필요합니다.")
        Integer amount
) {
}
