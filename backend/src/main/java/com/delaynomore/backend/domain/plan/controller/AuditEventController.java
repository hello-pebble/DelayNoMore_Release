package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.service.AuditEventService;
import com.delaynomore.backend.domain.plan.support.OwnerGuestId;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
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

    // 모르는 planId·남의 계획은 404가 아니라 빈 목록이다(존재 여부 은닉, 서비스가 판정).
    // 이벤트에 소유자가 박혀 있어, 삭제된 계획의 이력도 소유자에게는 조회된다(구계약 복원).
    @Operation(summary = "계획 변경 이력 조회 (최신순 · 게스트 ID별)")
    @GetMapping
    public ApiResponse<List<AuditEventResponse>> getAll(@PathVariable long planId,
                                                        @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        return ApiResponse.ok(auditEventService.getEvents(planId, OwnerGuestId.resolve(rawGuestId)));
    }
}
