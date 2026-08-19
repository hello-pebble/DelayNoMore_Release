package com.delaynomore.backend.domain.auth.repository;

import java.util.Optional;

/**
 * 사용자·세션 저장소. users와 auth_sessions 두 테이블을 한 저장소로 다룬다 —
 * 항상 로그인 흐름 안에서 함께 움직이는 데이터라 저장소를 쪼갤 이유가 없다.
 *
 * <p>owner 개념과의 관계: 로그인 사용자의 소유자 키는 {@code users.id}의 UUID 문자열이다.
 * 기존 게스트 키(브라우저 UUID)와 같은 모양으로 plans.owner 등 TEXT 컬럼에 공존한다(V4 주석).
 */
public interface AuthRepository {

    // 로그인 성공 시의 사용자 뷰 — id는 소유자 키로 그대로 쓰는 UUID 문자열.
    record UserAccount(String id, String nickname, String email) {
    }

    /**
     * 있으면 조회, 없으면 생성 — 동시 최초 로그인이 겹쳐도 깨지지 않아야 하므로 구현은
     * 원자적 upsert여야 한다. email은 매 로그인 최신화, nickname은 최초 가입 시에만 저장한다.
     */
    UserAccount upsertUser(String provider, String providerSubject, String email, String nickname);

    void createSession(String tokenHash, String userId, int ttlDays);

    // 유효(미만료) 세션의 사용자 id. 만료·미존재면 empty — 판정은 저장소가 한다.
    Optional<String> findUserIdByToken(String tokenHash);

    void deleteSession(String tokenHash);

    // 만료 세션 청소 — 로그인 시 한 번씩 불려 테이블이 무한히 자라는 것만 막는다(스케줄러 없음).
    void deleteExpiredSessions();

    /**
     * 게스트 데이터 흡수(re-key) — 게스트 소유의 모든 행을 사용자 소유로 옮긴다. 멱등:
     * 게스트 행이 없으면 전부 0-row no-op이므로 매 로그인마다 호출해도 안전하고, 새 기기의
     * 게스트 데이터도 로그인만 하면 자동으로 흡수된다. 충돌 규칙은 구현 주석 참조.
     */
    void absorbGuest(String userId, String guestId);
}
