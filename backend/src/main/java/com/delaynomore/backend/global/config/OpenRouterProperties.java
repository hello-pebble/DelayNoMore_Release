package com.delaynomore.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// openrouter.api.* 설정 바인딩. 비밀값(key)은 환경변수(OPENROUTER_API_KEY)로만 주입한다.
@ConfigurationProperties(prefix = "openrouter.api")
public record OpenRouterProperties(String url, String key, String model, Boolean toolCalling) {

    private static final String KEY_PLACEHOLDER = "YOUR_OPENROUTER_API_KEY_HERE";

    // 키가 실제로 주입됐는지 판별한다(미설정·플레이스홀더면 false).
    public boolean isKeyConfigured() {
        return key != null && !key.isBlank() && !KEY_PLACEHOLDER.equals(key);
    }

    /**
     * 에이전트(function calling) 경로를 쓸지. OpenRouter는 모델마다 도구 지원 여부가 다르고
     * 모델은 환경변수(OPENROUTER_MODEL)로 갈아끼울 수 있으므로, 도구를 지원하지 않는 모델로
     * 바꿨을 때 <b>코드 변경 없이</b> 예전 경로로 되돌릴 수 있는 스위치를 둔다.
     * 끄면 프론트가 /agent/chats/stream을 아예 호출하지 않고 기존 자유 대화 경로만 쓴다.
     * 값이 없으면 켜진 것으로 본다(기본 동작이 에이전트).
     */
    public boolean isToolCallingEnabled() {
        return toolCalling == null || toolCalling;
    }
}
