package com.delaynomore.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// google.oauth.* 설정 바인딩. clientId는 비밀값이 아니지만(프론트에도 내려간다) 배포 환경마다
// 달라지므로 환경변수(GOOGLE_CLIENT_ID)로 주입한다. 미설정이면 로그인 기능 전체가 꺼진 것으로
// 동작한다 — /auth/config가 enabled=false를 내리고 프론트는 로그인 버튼을 그리지 않는다.
@ConfigurationProperties(prefix = "google.oauth")
public record GoogleOauthProperties(String clientId, Boolean enabled) {

    private static final String CLIENT_ID_PLACEHOLDER = "YOUR_GOOGLE_CLIENT_ID_HERE";

    // 클라이언트 ID가 실제로 주입됐는지 판별한다(미설정·플레이스홀더면 false).
    public boolean isClientIdConfigured() {
        return clientId != null && !clientId.isBlank() && !CLIENT_ID_PLACEHOLDER.equals(clientId);
    }

    // 로그인 기능 스위치 — 값이 없으면 켜진 것으로 보되, 클라이언트 ID가 없으면 어차피 꺼진다.
    public boolean isLoginEnabled() {
        return (enabled == null || enabled) && isClientIdConfigured();
    }
}
