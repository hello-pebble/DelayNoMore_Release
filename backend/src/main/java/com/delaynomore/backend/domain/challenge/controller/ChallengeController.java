package com.delaynomore.backend.domain.challenge.controller;

import com.delaynomore.backend.domain.challenge.dto.ChallengeListResponse;
import com.delaynomore.backend.domain.challenge.dto.JoinResponse;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.global.auth.Owner;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Goal Challenge API — 정원이 한정된 목표 챌린지의 조회·참가.
 *
 * 개설 엔드포인트는 없다(v0.23.0). 챌린지는 비슷한 조건(기간 + 목적)의 계획이 모이면 서버가
 * 자동으로 연다 — 생성 시점은 계획 고정(POST /plans/{id}/confirm)이고, 규칙은 ChallengeService가
 * 소유한다. 사용자가 하는 일은 참가뿐이다.
 *
 * 계획 보관함과 달리 목록은 소유자 스코프가 없다(공개 모집 게시판) — 참가하려면 남이 만든
 * 챌린지가 보여야 한다. X-Guest-Id는 여전히 필수다: "누가 참가하는가"와 "내 포인트 잔액"이
 * 게스트별로 갈리기 때문이다. 해석은 계획 쪽과 같은 OwnerGuestId.resolve를 재사용한다.
 *
 * 참가는 본문 없는 도메인 액션(POST /{id}/join)이다 — 클라이언트가 정원이나 잔액을 실어 보내지
 * 않는다. 판정 규칙은 전부 서버(저장소의 원자 구간)가 소유한다.
 */
@Tag(name = "challenge")
@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ChallengeController {

    private final ChallengeService challengeService;

    @Operation(summary = "챌린지 목록 + 내 포인트 잔액")
    @GetMapping
    public ApiResponse<ChallengeListResponse> list(
            @Owner String owner) {
        return ApiResponse.ok(challengeService.list(owner));
    }

    // 동시 참가 요청에서 정원을 초과시키지 않는 것이 이 엔드포인트의 전부다.
    // 실패는 409 CHALLENGE_FULL / 409 CHALLENGE_ALREADY_JOINED / 400 POINTS_INSUFFICIENT.
    @Operation(summary = "챌린지 참가 — 정원 한정, 참가비 차감")
    @PostMapping("/{id}/join")
    public ApiResponse<JoinResponse> join(@PathVariable long id,
                                          @Owner String owner) {
        log.info("Received request to join challenge {}", id);
        return ApiResponse.ok(challengeService.join(id, owner));
    }
}
