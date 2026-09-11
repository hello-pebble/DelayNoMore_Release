package com.delaynomore.backend.domain.slack.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Slack 요청 서명 검증 — https://api.slack.com/authentication/verifying-requests-from-slack
 *
 * <p>서명 베이스는 {@code v0:<timestamp>:<raw body>}의 HMAC-SHA256(hex)이고, 원문 바이트
 * 기준이라 컨트롤러는 body를 파싱 전 String으로 받아 그대로 넘겨야 한다. 타임스탬프가
 * 5분 창을 벗어나면 재전송(replay) 공격으로 보고 거부한다. 비교는 상수 시간
 * (MessageDigest.isEqual) — 문자열 equals는 타이밍 부채널을 연다.
 */
public final class SlackSignatureVerifier {

    private static final long ALLOWED_SKEW_SECONDS = 60 * 5;

    private SlackSignatureVerifier() {
    }

    public static boolean verify(String signingSecret, String timestamp, String rawBody,
                                 String signature, long nowEpochSeconds) {
        if (signingSecret == null || timestamp == null || rawBody == null || signature == null) {
            return false;
        }
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowEpochSeconds - requestTime) > ALLOWED_SKEW_SECONDS) {
            return false;
        }
        String expected = "v0=" + hmacHex(signingSecret, "v0:" + timestamp + ":" + rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String secret, String base) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // HmacSHA256은 JVM 필수 알고리즘이라 도달 불가 — 계약 위반이면 검증 실패로 수렴시킨다.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
