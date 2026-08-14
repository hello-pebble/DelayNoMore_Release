package com.delaynomore.backend.domain.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record PlanDraftSessionMessageRequest(
        @NotBlank(message = "메시지를 입력해 주세요.") String message
) {
}
