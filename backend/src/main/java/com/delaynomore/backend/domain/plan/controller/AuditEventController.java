package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.service.AuditEventService;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 계획 변경 이력 조회 API — 읽기 전용. 이벤트는 서버가 변경 서비스 안에서 직접 발행하므로
 * 쓰기 엔드포인트가 없다(클라이언트가 이력을 조작할 수 없게).
 */
@Tag(name = "audit")
@RestController
@RequestMapping("/api/v1/plans/{planId}/audit-events")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuditEventController {

    private final AuditEventService auditEventService;

    // 의도적으로 계획 존재를 검증하지 않는다 — 삭제된 계획의 PLAN_DELETED 이력도 조회할 수
    // 있어야 하므로("언제 삭제됐는가"). 모르는 planId는 404가 아니라 빈 목록이다.
    @Operation(summary = "계획 변경 이력 조회 (최신순 · 삭제된 계획의 이력도 조회 가능)")
    @GetMapping
    public ApiResponse<List<AuditEventResponse>> getAll(@PathVariable long planId) {
        return ApiResponse.ok(auditEventService.getEvents(planId));
    }
}
