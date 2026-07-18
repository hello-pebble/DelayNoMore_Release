package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.AuditEvent;

// 변경 이력 응답 — 엔티티와 1:1(평탄). sessionId는 프론트가 자신의 ID와 비교해
// "이 브라우저/다른 세션" 배지를 만드는 데 쓴다.
public record AuditEventResponse(
        long id,
        long planId,
        String type,
        String detail,
        String sessionId,
        String createdAt
) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.id(), event.planId(), event.type(),
                event.detail(), event.sessionId(), event.createdAt());
    }
}
