package com.delaynomore.backend.domain.slack.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackSignatureVerifierTest {

    // 기대값은 독립 구현(openssl dgst -sha256 -hmac)으로 계산한 참조 벡터다 —
    // 구현이 자기 자신을 검증하는 순환을 피한다.
    private static final String SECRET = "test-secret";
    private static final String TIMESTAMP = "1725000000";
    private static final String BODY = "{\"type\":\"url_verification\",\"challenge\":\"abc\"}";
    private static final String SIGNATURE =
            "v0=c7668b2231f372966a3b3f69ba47db0197a89031c149f8d9c09759d920e224a4";
    private static final long NOW = 1725000000L;

    @Test
    void 올바른_서명은_통과한다() {
        assertThat(SlackSignatureVerifier.verify(SECRET, TIMESTAMP, BODY, SIGNATURE, NOW)).isTrue();
    }

    @Test
    void 본문이_한_글자라도_다르면_실패한다() {
        assertThat(SlackSignatureVerifier.verify(SECRET, TIMESTAMP, BODY + " ", SIGNATURE, NOW)).isFalse();
    }

    @Test
    void 시크릿이_다르면_실패한다() {
        assertThat(SlackSignatureVerifier.verify("other-secret", TIMESTAMP, BODY, SIGNATURE, NOW)).isFalse();
    }

    @Test
    void 타임스탬프가_5분_창을_벗어나면_서명이_맞아도_실패한다() {
        long sixMinutesLater = NOW + 6 * 60;
        assertThat(SlackSignatureVerifier.verify(SECRET, TIMESTAMP, BODY, SIGNATURE, sixMinutesLater)).isFalse();
    }

    @Test
    void 타임스탬프가_숫자가_아니면_실패한다() {
        assertThat(SlackSignatureVerifier.verify(SECRET, "not-a-number", BODY, SIGNATURE, NOW)).isFalse();
    }

    @Test
    void 널_인자는_예외_없이_실패한다() {
        assertThat(SlackSignatureVerifier.verify(SECRET, TIMESTAMP, BODY, null, NOW)).isFalse();
        assertThat(SlackSignatureVerifier.verify(null, TIMESTAMP, BODY, SIGNATURE, NOW)).isFalse();
    }
}
