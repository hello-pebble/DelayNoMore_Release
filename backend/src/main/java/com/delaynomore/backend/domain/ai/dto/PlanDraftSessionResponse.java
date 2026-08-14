package com.delaynomore.backend.domain.ai.dto;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;

import java.util.Map;

/** 서버가 소유하는 계획 작성 대화의 현재 화면 모델이다. */
public record PlanDraftSessionResponse(
        String sessionId,
        String reply,
        Map<String, Object> slots,
        String nextInput,
        PlanResponse plan
) {
}
