package com.delaynomore.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// openrouter.api.* 설정 바인딩. 비밀값(key)은 환경변수(OPENROUTER_API_KEY)로만 주입한다.
@ConfigurationProperties(prefix = "openrouter.api")
public record OpenRouterProperties(String url, String key, String model) {

    private static final String KEY_PLACEHOLDER = "YOUR_OPENROUTER_API_KEY_HERE";

    // 키가 실제로 주입됐는지 판별한다(미설정·플레이스홀더면 false).
    public boolean isKeyConfigured() {
        return key != null && !key.isBlank() && !KEY_PLACEHOLDER.equals(key);
    }
}
