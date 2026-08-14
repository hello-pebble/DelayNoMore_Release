package com.delaynomore.backend.domain.plan.dto;

import jakarta.validation.constraints.NotNull;

/** A single task-state command; clients never need to replace the full plan for a completion change. */
public record TaskCompletionRequest(
        @NotNull(message = "completed 값이 필요합니다.") Boolean completed
) {
}
