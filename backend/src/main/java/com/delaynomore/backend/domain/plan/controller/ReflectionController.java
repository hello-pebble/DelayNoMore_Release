package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.dto.ReflectionResponse;
import com.delaynomore.backend.domain.plan.dto.ReflectionSaveRequest;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.domain.plan.support.OwnerGuestId;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 하루 마무리 회고 API — 계획별·날짜별 1건(업서트). 계획 보관함과 같은 서버 인메모리(휘발성)
 * 데모 체제. 회고는 자체 소유자 없이 계획(X-Guest-Id)의 소유권을 상속한다 — 남의 계획의 회고는
 * 저장·조회 모두 404. 저장은 오늘(Asia/Seoul 기준) 날짜만 허용하고,
 * 완료/전체 개수는 서버가 계획의 오늘 할 일에서 재계산한다.
 */
@Tag(name = "reflection")
@RestController
@RequestMapping("/api/v1/plans/{planId}/reflections")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ReflectionController {

    private final ReflectionService reflectionService;

    @Operation(summary = "오늘 회고 저장(업서트 — 있으면 갱신)")
    @PutMapping("/{date}")
    public ApiResponse<ReflectionResponse> save(@PathVariable long planId,
                                                @PathVariable String date,
                                                @Valid @RequestBody ReflectionSaveRequest request,
                                                @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId,
                                                @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request to save reflection for plan {} on {}", planId, date);
        return ApiResponse.ok(reflectionService.save(planId, date, request,
                OwnerGuestId.resolve(rawGuestId), sessionId));
    }

    @Operation(summary = "특정 날짜의 회고 조회")
    @GetMapping("/{date}")
    public ApiResponse<ReflectionResponse> get(@PathVariable long planId, @PathVariable String date,
                                               @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        return ApiResponse.ok(reflectionService.get(planId, date, OwnerGuestId.resolve(rawGuestId)));
    }

    @Operation(summary = "계획의 회고 목록 조회 (날짜 내림차순)")
    @GetMapping
    public ApiResponse<List<ReflectionResponse>> getAll(@PathVariable long planId,
                                                        @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        return ApiResponse.ok(reflectionService.getAll(planId, OwnerGuestId.resolve(rawGuestId)));
    }
}
