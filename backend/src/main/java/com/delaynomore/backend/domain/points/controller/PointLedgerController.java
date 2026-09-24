package com.delaynomore.backend.domain.points.controller;

import com.delaynomore.backend.domain.points.dto.PointLedgerResponse;
import com.delaynomore.backend.domain.points.service.PointLedgerService;
import com.delaynomore.backend.global.auth.Owner;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포인트 거래 내역 API(v0.31.0) — 읽기 전용.
 *
 * <p>소유자 스코프가 있다: 남의 원장은 볼 수 없고, 시스템·예치 계정도 노출하지 않는다
 * (조회 대상은 언제나 {@code @Owner}가 해석한 내 계정 하나다 — 계정을 인자로 받지 않는 이유).
 */
@Tag(name = "points")
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PointLedgerController {

    private final PointLedgerService pointLedgerService;

    @Operation(summary = "내 포인트 거래 내역 (잔액 + 최근 기록)")
    @GetMapping("/ledger")
    public ApiResponse<PointLedgerResponse> ledger(@Owner String owner) {
        return ApiResponse.ok(pointLedgerService.myLedger(owner));
    }
}
