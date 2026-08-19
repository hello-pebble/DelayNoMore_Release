package com.delaynomore.backend.domain.auth.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// 세션 토큰 생성·해시 유틸. 토큰 원문은 응답으로 한 번 내려간 뒤 서버에 남지 않고,
// 저장·조회는 전부 SHA-256 해시로만 한다(auth_sessions.token_hash).
public final class SessionToken {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionToken() {
    }

    // 256비트 랜덤 → URL-safe Base64 43자. 추측 불가능성이 인증의 전부이므로 SecureRandom 고정.
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM에 필수 탑재 — 도달 불가.
            throw new IllegalStateException(e);
        }
    }
}
