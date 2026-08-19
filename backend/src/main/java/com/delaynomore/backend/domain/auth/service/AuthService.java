package com.delaynomore.backend.domain.auth.service;

import com.delaynomore.backend.domain.auth.client.GoogleTokenVerifier;
import com.delaynomore.backend.domain.auth.dto.AuthDtos.AuthConfigResponse;
import com.delaynomore.backend.domain.auth.dto.AuthDtos.LoginResponse;
import com.delaynomore.backend.domain.auth.repository.AuthRepository;
import com.delaynomore.backend.domain.auth.support.SessionToken;
import com.delaynomore.backend.domain.plan.support.OwnerGuestId;
import com.delaynomore.backend.global.config.GoogleOauthProperties;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 로그인 흐름의 오케스트레이션 — 검증(Google) → 사용자 upsert → 게스트 흡수 → 세션 발급.
 *
 * <p>게스트 흡수를 <b>매 로그인마다</b> 수행하는 것이 설계의 축이다. absorbGuest가 멱등이라
 * (이미 흡수된 게스트는 전부 0-row no-op) 신규/기존 가입을 구분할 필요가 없고, 새 기기에서
 * 쌓은 게스트 데이터도 그 기기에서 로그인하는 순간 자동으로 계정에 합쳐진다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    static final int SESSION_TTL_DAYS = 30;

    // 프론트 닉네임 규칙(nickname.js)과 동일 — 서버는 신뢰 경계라 다시 검증하고, 어긋나면
    // 로그인을 막는 대신 닉네임만 버린다(닉네임은 부가 정보지 로그인 조건이 아니다).
    private static final Pattern NICKNAME_VALID = Pattern.compile("^[0-9A-Za-z가-힣]{2,20}$");

    private final AuthRepository authRepository;
    private final GoogleTokenVerifier tokenVerifier;
    private final GoogleOauthProperties properties;

    // ponytail: 트랜잭션 안에서 Google tokeninfo HTTP 왕복 1회가 일어난다(커넥션 점유) —
    // 로그인 시에만 발생하는 저트래픽 전제. 병목이 되면 검증을 트랜잭션 밖으로 분리.
    @Transactional
    public LoginResponse login(String credential, String rawNickname, String rawGuestId) {
        if (!properties.isLoginEnabled()) {
            throw new BusinessException(ErrorCode.AUTH_DISABLED);
        }
        GoogleTokenVerifier.GoogleIdentity identity = tokenVerifier.verify(credential);
        AuthRepository.UserAccount account =
                authRepository.upsertUser("google", identity.sub(), identity.email(), sanitizeNickname(rawNickname));

        absorbGuestIfPresent(account.id(), rawGuestId);
        authRepository.deleteExpiredSessions();

        String token = SessionToken.generate();
        authRepository.createSession(SessionToken.hash(token), account.id(), SESSION_TTL_DAYS);
        log.info("login ok: user={}", account.id());
        return new LoginResponse(token, account.nickname(), account.email());
    }

    public void logout(String authorizationHeader) {
        String token = extractBearer(authorizationHeader);
        if (token != null) {
            authRepository.deleteSession(SessionToken.hash(token));
        }
        // 토큰이 없거나 형식이 틀려도 조용히 성공 — 로그아웃은 어차피 "세션이 없는 상태"가 목표다.
    }

    public AuthConfigResponse config() {
        boolean enabled = properties.isLoginEnabled();
        return new AuthConfigResponse(enabled, enabled ? properties.clientId() : null);
    }

    // 게스트 헤더가 유효할 때만 흡수한다. 헤더가 없거나 형식이 틀려도 로그인 자체는 성공 —
    // 흡수는 부가 동작이지 로그인 조건이 아니다(예: localStorage가 막힌 브라우저).
    private void absorbGuestIfPresent(String userId, String rawGuestId) {
        if (rawGuestId == null || rawGuestId.isBlank()) {
            return;
        }
        String guestId;
        try {
            guestId = OwnerGuestId.resolve(rawGuestId);
        } catch (BusinessException e) {
            log.warn("guest absorb skipped: invalid X-Guest-Id");
            return;
        }
        if (!guestId.equals(userId)) { // 프론트가 로그인 후에도 옛 guestId를 계속 보내는 경우의 자기 흡수 방지
            authRepository.absorbGuest(userId, guestId);
        }
    }

    private String sanitizeNickname(String rawNickname) {
        if (rawNickname == null) {
            return null;
        }
        String nickname = rawNickname.trim();
        return NICKNAME_VALID.matcher(nickname).matches() ? nickname : null;
    }

    public static String extractBearer(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
