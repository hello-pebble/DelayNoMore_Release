package com.delaynomore.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// openrouter.api.* 설정 바인딩. 비밀값(key)은 환경변수(OPENROUTER_API_KEY)로만 주입한다.
@ConfigurationProperties(prefix = "openrouter.api")
public record OpenRouterProperties(String url, String key, String model, Boolean toolCalling,
                                   Boolean streamUsage) {

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

    /**
     * 스트리밍 응답 끝에 usage 청크를 요청할지(OpenAI 호환 {@code stream_options.include_usage}).
     * 이걸 켜지 않으면 스트리밍 경로는 토큰 사용량을 <b>알 방법이 없다</b> — 비스트리밍과 달리
     * 응답 본문에 usage가 들어오지 않기 때문이다.
     *
     * <p>다만 이 필드는 업스트림 모델에 따라 무시되거나 다르게 동작할 수 있으므로, 이상이 생기면
     * 코드 변경 없이 끌 수 있게 스위치로 뒀다(tool-calling 스위치와 같은 이유). 꺼도 계측만
     * 사라지고 스트리밍 자체는 그대로 동작한다. 값이 없으면 켜진 것으로 본다.
     */
    public boolean isStreamUsageEnabled() {
        return streamUsage == null || streamUsage;
    }
}
