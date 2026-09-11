package com.delaynomore.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// slack.* 설정 바인딩. 봇 토큰·서명 시크릿은 서버 전용 비밀값이다(OPENROUTER_API_KEY와 동급 —
// 코드·문서·커밋 금지). 미설정이면 슬랙 기능이 통째로 꺼진 것으로 동작한다 — 발송 루프는
// 돌지 않고, 수신 엔드포인트는 503을 낸다.
@ConfigurationProperties(prefix = "slack")
public record SlackProperties(String botToken, String signingSecret, Boolean enabled) {

    private static final String BOT_TOKEN_PLACEHOLDER = "YOUR_SLACK_BOT_TOKEN_HERE";
    private static final String SIGNING_SECRET_PLACEHOLDER = "YOUR_SLACK_SIGNING_SECRET_HERE";

    public boolean isBotTokenConfigured() {
        return botToken != null && !botToken.isBlank() && !BOT_TOKEN_PLACEHOLDER.equals(botToken);
    }

    public boolean isSigningSecretConfigured() {
        return signingSecret != null && !signingSecret.isBlank()
                && !SIGNING_SECRET_PLACEHOLDER.equals(signingSecret);
    }

    // 기능 스위치 — 값이 없으면 켜진 것으로 보되, 서명 시크릿이 없으면 수신을 검증할 수 없으므로
    // 어차피 꺼진다. 봇 토큰은 별도 판별(isBotTokenConfigured)로 두는 이유: 토큰 없이도 서명
    // 시크릿만 있으면 수신 경로를 로컬에서 검증할 수 있다(발송은 드라이런 로그).
    public boolean isEnabled() {
        return (enabled == null || enabled) && isSigningSecretConfigured();
    }
}
