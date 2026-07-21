package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// X-Guest-Id 헤더 해석 — 브라우저 게스트 ID(ASCII, 퍼센트 인코딩 없음) 통과·검증.
// 규칙: 트림 후 영문·숫자·하이픈 8~64자(crypto.randomUUID + g-/s- 폴백 허용).
class OwnerGuestIdTest {

    @Test
    void resolve_UUID형식_그대로통과() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertThat(OwnerGuestId.resolve(uuid)).isEqualTo(uuid);
    }

    @Test
    void resolve_폴백형식_그대로통과() {
        // guest_id.js의 비보안 컨텍스트 폴백(g-<base36>-<base36>) 및 구 세션 표기(s-...)
        assertThat(OwnerGuestId.resolve("g-abc123-xyz789")).isEqualTo("g-abc123-xyz789");
        assertThat(OwnerGuestId.resolve("s-lx9f2a-8h3k1p0q")).isEqualTo("s-lx9f2a-8h3k1p0q");
    }

    @Test
    void resolve_앞뒤공백_트림후통과() {
        assertThat(OwnerGuestId.resolve("  abc12345  ")).isEqualTo("abc12345");
    }

    @Test
    void resolve_null또는공백_GUEST_ID_REQUIRED예외() {
        for (String raw : new String[]{null, "", "   "}) {
            BusinessException exception = catchThrowableOfType(
                    BusinessException.class, () -> OwnerGuestId.resolve(raw));
            assertThat(exception.getErrorCode()).as("raw: [%s]", raw).isEqualTo(ErrorCode.GUEST_ID_REQUIRED);
        }
    }

    @Test
    void resolve_규칙위반_GUEST_ID_INVALID예외() {
        // 8자 미만 / 64자 초과 / 한글 / 언더스코어 등 기호 / 중간 공백 / 퍼센트(더이상 디코딩 안 함)
        for (String raw : new String[]{
                "abc1234",              // 7자
                "a".repeat(65),         // 65자
                "테스터테스터테스",       // 한글
                "guest_id!",            // 기호
                "abc 12345",            // 중간 공백
                "%EA%B9%80%EC%BD%94"    // 퍼센트 — 이제 그냥 무효
        }) {
            BusinessException exception = catchThrowableOfType(
                    BusinessException.class, () -> OwnerGuestId.resolve(raw));
            assertThat(exception.getErrorCode()).as("raw: [%s]", raw).isEqualTo(ErrorCode.GUEST_ID_INVALID);
        }
    }
}
