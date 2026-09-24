package com.delaynomore.backend.domain.points.controller;

import com.delaynomore.backend.domain.points.dto.PlanDepositRequest;
import com.delaynomore.backend.domain.points.dto.PlanDepositResponse;
import com.delaynomore.backend.domain.points.service.PlanDepositService;
import com.delaynomore.backend.global.auth.Owner;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 목표 예치 API(v0.32.0) — 계획 경로 아래 있지만 규칙은 포인트 도메인이 소유한다
 * (참고 자료 API가 {@code /plans/{id}/knowledge}에 사는 것과 같은 선례).
 *
 * <p>해제(회수) 엔드포인트는 <b>없다</b>. 걸었다가 무르는 길이 있으면 약속이 아니기 때문이다 —
 * 돌려받는 유일한 길은 계획을 종결시키는 것이고, 그때 달성률만큼 돌아온다. 중단(cancel)도
 * 종결이므로 포인트가 잠기는 일은 없다.
 */
@Tag(name = "points")
@RestController
@RequestMapping("/api/v1/plans/{planId}/deposit")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlanDepositController {

    private final PlanDepositService planDepositService;

    @Operation(summary = "목표 예치 걸기 (고정된 계획, 계획당 1건)")
    @PostMapping
    public ApiResponse<PlanDepositResponse> deposit(@PathVariable long planId,
                                                    @Valid @RequestBody PlanDepositRequest request,
                                                    @Owner String owner) {
        return ApiResponse.ok(planDepositService.deposit(planId, owner, request.amount()));
    }

    @Operation(summary = "내 목표 예치 조회 (현재 달성률 · 지금 종결 시 환급액)")
    @GetMapping
    public ApiResponse<PlanDepositResponse> get(@PathVariable long planId, @Owner String owner) {
        return ApiResponse.ok(planDepositService.get(planId, owner));
    }
}
