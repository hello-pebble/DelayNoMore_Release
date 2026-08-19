package com.delaynomore.backend.domain.auth.controller;

import com.delaynomore.backend.domain.auth.dto.AuthDtos.AuthConfigResponse;
import com.delaynomore.backend.domain.auth.dto.AuthDtos.LoginRequest;
import com.delaynomore.backend.domain.auth.dto.AuthDtos.LoginResponse;
import com.delaynomore.backend.domain.auth.service.AuthService;
import com.delaynomore.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API — Google 로그인(GIS credential 검증 → 세션 발급), 로그아웃, 프론트 설정 조회.
 *
 * 로그인 요청의 X-Guest-Id는 "지금 이 브라우저의 게스트 보관함을 이 계정으로 흡수해 달라"는
 * 재료다 — 인증과 무관하고, 없어도 로그인은 성공한다.
 */
@Tag(name = "auth")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Google 로그인 — ID 토큰 검증 후 세션 토큰 발급, 게스트 데이터 흡수")
    @PostMapping("/google")
    public ApiResponse<LoginResponse> google(@Valid @RequestBody LoginRequest request,
                                             @RequestHeader(value = "X-Guest-Id", required = false) String rawGuestId) {
        log.info("Received google login request");
        return ApiResponse.ok(authService.login(request.credential(), request.nickname(), rawGuestId));
    }

    @Operation(summary = "로그아웃 — 서버 세션 삭제")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(authorization);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "로그인 설정 — 기능 활성 여부와 Google 클라이언트 ID")
    @GetMapping("/config")
    public ApiResponse<AuthConfigResponse> config() {
        return ApiResponse.ok(authService.config());
    }
}
