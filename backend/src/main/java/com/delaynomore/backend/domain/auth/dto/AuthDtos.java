package com.delaynomore.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 인증 API의 요청·응답 묶음 — 셋 다 몇 필드짜리 record라 파일을 쪼개지 않는다.
public final class AuthDtos {

    private AuthDtos() {
    }

    // GIS 콜백이 준 credential(Google ID 토큰)과, 최초 가입 시 서버에 옮겨 심을 로컬 닉네임(선택).
    public record LoginRequest(@NotBlank String credential, String nickname) {
    }

    // token은 이 응답에 딱 한 번 실려 내려가고 서버에는 해시만 남는다.
    public record LoginResponse(String token, String nickname, String email) {
    }

    // 프론트 로그인 버튼 게이팅용 — enabled=false면 clientId도 내리지 않는다.
    public record AuthConfigResponse(boolean enabled, String clientId) {
    }
}
