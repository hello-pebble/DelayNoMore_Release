package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 계획 보관함 API — 로그인/DB 도입 전까지 서버 인메모리(휘발성)에 보관하는 데모용.
 * 모든 방문자가 같은 목록을 공유하므로, 원격에서 여러 계획을 만들고 전환하며 테스트할 수 있다.
 */
@Tag(name = "plan")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class PlanController {

    private final PlanService planService;

    // X-Session-Id: 변경 이력의 "다른 세션에서 발생한 변경인가?" 귀속용 선택 헤더 —
    // 브라우저가 만든 익명 식별자일 뿐 인증이 아니다. 없으면(구형 클라이언트·curl) null로 기록된다.
    // 읽기(GET)는 이력을 남기지 않으므로 변이 메서드에만 받는다.
    @Operation(summary = "계획 보관")
    @PostMapping
    public ApiResponse<PlanResponse> create(@Valid @RequestBody PlanSaveRequest request,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request to create plan");
        return ApiResponse.ok(planService.create(request, sessionId));
    }

    @Operation(summary = "보관된 계획 목록 조회 (최근 저장순)")
    @GetMapping
    public ApiResponse<List<PlanResponse>> getPlans() {
        return ApiResponse.ok(planService.getPlans());
    }

    @Operation(summary = "보관된 계획 단건 조회")
    @GetMapping("/{id}")
    public ApiResponse<PlanResponse> getPlan(@PathVariable long id) {
        return ApiResponse.ok(planService.getPlan(id));
    }

    @Operation(summary = "보관된 계획 수정")
    @PutMapping("/{id}")
    public ApiResponse<PlanResponse> update(@PathVariable long id,
                                            @Valid @RequestBody PlanSaveRequest request,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return ApiResponse.ok(planService.update(id, request, sessionId));
    }

    @Operation(summary = "보관된 계획 삭제")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id,
                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request to delete plan {}", id);
        planService.delete(id, sessionId);
        return ApiResponse.ok(null);
    }
}
