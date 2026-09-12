package com.delaynomore.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ai.rate-limit.* 설정 바인딩(v0.30.0) — LLM 호출의 일일 상한. 종량제 API 키를 쓰는 데모
 * 서버의 재무 방어선이다.
 *
 * <p>두 층인 이유: 소유자당 상한만으로는 막히지 않는다. 게스트 ID는 브라우저가 만드는 값이라
 * 얼마든지 새로 발급할 수 있어서, 소유자 한도는 "정상 사용자의 폭주"만 막는다. 실제로 지갑을
 * 지키는 것은 <b>전역 상한</b>이다.
 *
 * <p>0 이하는 무제한 — 로컬·테스트에서 한도를 끄는 탈출구다.
 */
@ConfigurationProperties(prefix = "ai.rate-limit")
public record AiRateLimitProperties(Integer dailyCallsPerOwner, Integer dailyCallsGlobal) {

    private static final int DEFAULT_PER_OWNER = 60;
    private static final int DEFAULT_GLOBAL = 1500;

    public int perOwnerLimit() {
        return dailyCallsPerOwner == null ? DEFAULT_PER_OWNER : dailyCallsPerOwner;
    }

    public int globalLimit() {
        return dailyCallsGlobal == null ? DEFAULT_GLOBAL : dailyCallsGlobal;
    }

    public boolean perOwnerEnabled() {
        return perOwnerLimit() > 0;
    }

    public boolean globalEnabled() {
        return globalLimit() > 0;
    }
}
