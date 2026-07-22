package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.dto.CarryOverResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.dto.RecommendationConfirmRequest;
import com.delaynomore.backend.domain.plan.dto.RecommendationDraftRequest;
import com.delaynomore.backend.domain.plan.dto.RecommendationDraftResponse;
import com.delaynomore.backend.domain.plan.dto.RecommendationResponse;
import com.delaynomore.backend.domain.plan.dto.WeeklySummaryResponse;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.domain.plan.service.WorkloadRecommendationService;
import com.delaynomore.backend.domain.plan.support.OwnerGuestId;
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
 * 보관함은 브라우저 게스트 ID(X-Guest-Id 헤더)별로 격리된다 — 닉네임은 화면 표시용 라벨일 뿐
 * 서버로 오지 않으므로, 다른 브라우저에서 같은 닉네임을 써도 별도의 보관함이 된다.
 *
 * X-Guest-Id: 소유자 스코프 필수 헤더 — X-Session-Id(변이 전용·선택)와 달리 읽기(GET)에도
 * 필요하다(목록·단건 조회가 소유자별로 갈리므로). required=false + 수동 해석(OwnerGuestId.resolve)
 * 인 이유: 누락 시 Spring 기본 400 대신 우리 ApiResponse 형식의 한국어 오류를 돌려주기 위해서다.
 */
@Tag(name = "plan")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class PlanController {

    private final PlanService planService;
    private final WorkloadRecommendationService recommendationService;

    // X-Session-Id: 변경 이력의 "다른 세션에서 발생한 변경인가?" 귀속용 선택 헤더 —
    // 브라우저가 만든 익명 식별자일 뿐 인증이 아니다. 없으면(구형 클라이언트·curl) null로 기록된다.
    // 읽기(GET)는 이력을 남기지 않으므로 변이 메서드에만 받는다.
    @Operation(summary = "계획 보관")
    @PostMapping
    public ApiResponse<PlanResponse> create(@Valid @RequestBody PlanSaveRequest request,
                                            @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request to create plan");
        return ApiResponse.ok(planService.create(request, OwnerGuestId.resolve(rawGuestId), sessionId));
    }

    @Operation(summary = "보관된 계획 목록 조회 (최근 저장순 · 게스트 ID별)")
    @GetMapping
    public ApiResponse<List<PlanResponse>> getPlans(
            @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        return ApiResponse.ok(planService.getPlans(OwnerGuestId.resolve(rawGuestId)));
    }

    @Operation(summary = "보관된 계획 단건 조회")
    @GetMapping("/{id}")
    public ApiResponse<PlanResponse> getPlan(@PathVariable long id,
                                             @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        return ApiResponse.ok(planService.getPlan(id, OwnerGuestId.resolve(rawGuestId)));
    }

    // 주간 완료율 요약 — 계획을 startDate 기준 7일 버킷("N주차")으로 묶은 주별 완료율. 읽기라
    // 이력을 남기지 않으므로 X-Session-Id를 받지 않는다.
    @Operation(summary = "주간 완료율 요약")
    @GetMapping("/{id}/summary/weekly")
    public ApiResponse<WeeklySummaryResponse> getWeeklySummary(@PathVariable long id,
                                                               @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        return ApiResponse.ok(planService.getWeeklySummary(id, OwnerGuestId.resolve(rawGuestId)));
    }

    @Operation(summary = "보관된 계획 수정")
    @PutMapping("/{id}")
    public ApiResponse<PlanResponse> update(@PathVariable long id,
                                            @Valid @RequestBody PlanSaveRequest request,
                                            @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return ApiResponse.ok(planService.update(id, request, OwnerGuestId.resolve(rawGuestId), sessionId));
    }

    // 본문 없는 도메인 액션 — 이월 규칙(오늘(KST) 미완료 → 내일, 필요 시 기간 하루 연장)은
    // 서버가 소유하므로 클라이언트는 날짜를 지정하지 않는다.
    @Operation(summary = "미완료 이월 — 오늘(KST) 미완료 항목을 내일로 이동")
    @PostMapping("/{id}/carry-over")
    public ApiResponse<CarryOverResponse> carryOver(@PathVariable long id,
                                                    @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId,
                                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request to carry over plan {}", id);
        return ApiResponse.ok(planService.carryOver(id, OwnerGuestId.resolve(rawGuestId), sessionId));
    }

    // === 다음 계획 분량 추천(로드맵 4·5번) ===
    // 계산(수행 기록)+AI 이유+VIEWED 기록을 유발하므로 GET이 아니라 POST. 분량 숫자는 서버 규칙이
    // 결정하고 AI는 이유 설명만 한다.
    @Operation(summary = "다음 계획 분량 추천 — 수행 기록 계산 + 규칙 분량 + 이유")
    @PostMapping("/{id}/recommendation")
    public ApiResponse<RecommendationResponse> recommend(@PathVariable long id,
                                                         @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId,
                                                         @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request to recommend workload for plan {}", id);
        return ApiResponse.ok(recommendationService.recommend(id, OwnerGuestId.resolve(rawGuestId), sessionId));
    }

    // 선택한 분량으로 초안을 생성해 미리보기로 돌려준다 — 저장하지 않는다(승인 전 미저장).
    @Operation(summary = "추천 초안 생성(미리보기 · 미저장)")
    @PostMapping("/{id}/recommendation/draft")
    public ApiResponse<RecommendationDraftResponse> recommendationDraft(@PathVariable long id,
                                                                        @Valid @RequestBody RecommendationDraftRequest request,
                                                                        @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        return ApiResponse.ok(recommendationService.draft(id, request.selectedTasksPerDay(),
                OwnerGuestId.resolve(rawGuestId)));
    }

    // 승인된 초안을 새 계획으로 저장한다(원본은 불변). 결정·생성 이력이 함께 남는다.
    @Operation(summary = "추천 초안 승인 — 새 계획으로 저장")
    @PostMapping("/{id}/recommendation/confirm")
    public ApiResponse<PlanResponse> recommendationConfirm(@PathVariable long id,
                                                           @Valid @RequestBody RecommendationConfirmRequest request,
                                                           @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId,
                                                           @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request to confirm recommendation from plan {}", id);
        return ApiResponse.ok(recommendationService.confirm(id, request, OwnerGuestId.resolve(rawGuestId), sessionId));
    }

    @Operation(summary = "보관된 계획 삭제")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id,
                                    @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId,
                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received request to delete plan {}", id);
        planService.delete(id, OwnerGuestId.resolve(rawGuestId), sessionId);
        return ApiResponse.ok(null);
    }
}
