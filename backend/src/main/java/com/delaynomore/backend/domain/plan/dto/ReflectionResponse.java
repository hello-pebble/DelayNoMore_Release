package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.Reflection;

// 회고 응답. 단건 조회·목록 조회·저장 결과 모두 이 DTO를 그대로 쓴다.
public record ReflectionResponse(
        long planId,
        String date,
        int completedCount,
        int totalCount,
        String difficulty,
        String reason,
        String createdAt,
        String updatedAt
) {

    public static ReflectionResponse from(Reflection reflection) {
        return new ReflectionResponse(reflection.planId(), reflection.date(),
                reflection.completedCount(), reflection.totalCount(),
                reflection.difficulty(), reflection.reason(),
                reflection.createdAt(), reflection.updatedAt());
    }
}
