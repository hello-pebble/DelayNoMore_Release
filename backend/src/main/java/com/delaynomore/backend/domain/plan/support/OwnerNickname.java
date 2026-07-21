package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

// X-Nickname 헤더 → 소유자 닉네임 해석기. 닉네임은 로그인 도입 전의 간이 계정 키로,
// 같은 닉네임이면 어느 브라우저에서든 같은 보관함을 본다(비밀번호 없는 스코프 키 — 인증이 아니다).
// HTTP 헤더 값은 ASCII만 안전하므로(RFC 9110 — fetch는 한글 원문 헤더를 거부한다) 클라이언트가
// encodeURIComponent로 퍼센트 인코딩해 보내고, 서버가 여기서 UTF-8로 복원한다.
// 규칙(트림 후 한글·영문·숫자 2~20자)은 프론트 nickname.js와 거울처럼 유지한다.
// Bean Validation을 안 쓰는 이유: 헤더는 @Valid 대상(요청 바디)이 아니라서, BusinessException으로
// 기존 GlobalExceptionHandler → ApiResponse.error 경로를 재사용한다.
public final class OwnerNickname {

    // 공백·기호 불허 — 인코딩 경계 사례(+, %, 공백)를 규칙 차원에서 차단한다.
    private static final Pattern VALID = Pattern.compile("^[0-9A-Za-z가-힣]{2,20}$");

    private OwnerNickname() {
    }

    public static String resolve(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            throw new BusinessException(ErrorCode.NICKNAME_REQUIRED);
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(rawHeader.trim(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) { // 깨진 퍼센트 시퀀스(%zz 등)
            throw new BusinessException(ErrorCode.NICKNAME_INVALID);
        }
        String nickname = decoded.trim();
        if (!VALID.matcher(nickname).matches()) {
            throw new BusinessException(ErrorCode.NICKNAME_INVALID);
        }
        return nickname;
    }
}
