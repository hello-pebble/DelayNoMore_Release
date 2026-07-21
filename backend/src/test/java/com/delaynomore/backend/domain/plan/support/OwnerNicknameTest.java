package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// X-Nickname 헤더 해석 — 퍼센트 인코딩 복원(한글은 헤더에 원문으로 못 실린다)과
// 닉네임 규칙(트림 후 한글·영문·숫자 2~20자, 프론트 nickname.js와 미러) 검증.
class OwnerNicknameTest {

    @Test
    void resolve_퍼센트인코딩된한글_복원() {
        // given — encodeURIComponent("김코치")
        String encoded = "%EA%B9%80%EC%BD%94%EC%B9%98";

        // when / then
        assertThat(OwnerNickname.resolve(encoded)).isEqualTo("김코치");
    }

    @Test
    void resolve_영문숫자혼합_그대로통과() {
        assertThat(OwnerNickname.resolve("coach2")).isEqualTo("coach2");
    }

    @Test
    void resolve_앞뒤공백_트림후통과() {
        // 인코딩된 공백(%20)도 디코드 후 트림된다
        assertThat(OwnerNickname.resolve("%20coach2%20")).isEqualTo("coach2");
    }

    @Test
    void resolve_null또는공백_NICKNAME_REQUIRED예외() {
        for (String raw : new String[]{null, "", "   "}) {
            BusinessException exception = catchThrowableOfType(
                    BusinessException.class, () -> OwnerNickname.resolve(raw));
            assertThat(exception.getErrorCode()).as("raw: [%s]", raw).isEqualTo(ErrorCode.NICKNAME_REQUIRED);
        }
    }

    @Test
    void resolve_규칙위반_NICKNAME_INVALID예외() {
        // 1자 / 21자 / 기호 / 중간 공백 / 인코딩된 기호
        for (String raw : new String[]{"a", "a".repeat(21), "nick!", "%EA%B9%80%20%EC%BD%94", "nick%2Bname"}) {
            BusinessException exception = catchThrowableOfType(
                    BusinessException.class, () -> OwnerNickname.resolve(raw));
            assertThat(exception.getErrorCode()).as("raw: [%s]", raw).isEqualTo(ErrorCode.NICKNAME_INVALID);
        }
    }

    @Test
    void resolve_깨진퍼센트시퀀스_NICKNAME_INVALID예외() {
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> OwnerNickname.resolve("%zz코치"));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NICKNAME_INVALID);
    }
}
