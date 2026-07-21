package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;

import java.util.regex.Pattern;

// X-Guest-Id 헤더 → 소유자 키 해석기. 브라우저가 최초 1회 생성해 localStorage에 보관하는 안정
// 식별자(guest_id.js)로, 서버로 오는 유일한 소유자 스코프다. 닉네임은 화면 표시용 라벨일 뿐
// 서버로 오지 않는다 — 다른 브라우저에서 같은 닉네임을 써도 별도의 보관함이 된다.
// ASCII 값이라 퍼센트 인코딩이 필요 없다(닉네임 시절과 달리 URLDecoder 불필요).
// 패턴은 crypto.randomUUID()와 폴백(g-<base36>-<base36>·구 s-... 세션 표기) 양쪽을 허용하는
// 관대한 규칙. 로그인 도입 시 이 키가 memberId로 이전된다(BACKEND_MIGRATION.md).
public final class OwnerGuestId {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private OwnerGuestId() {
    }

    public static String resolve(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            throw new BusinessException(ErrorCode.GUEST_ID_REQUIRED);
        }
        String guestId = rawHeader.trim();
        if (!VALID.matcher(guestId).matches()) {
            throw new BusinessException(ErrorCode.GUEST_ID_INVALID);
        }
        return guestId;
    }
}
