package com.delaynomore.backend.global.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 소유자 키를 주입한다 — 해석 규칙은 {@link OwnerArgumentResolver} 참조.
 * 로그인 사용자는 users.id의 UUID 문자열, 게스트는 X-Guest-Id 값이며 서비스·저장소 계층은
 * 둘을 구분하지 않는다(같은 owner TEXT 컬럼 공존, V4 마이그레이션 주석).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Owner {
}
