package com.delaynomore.backend.domain.auth.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

// 사용자·세션 JDBC 구현 — postgres 프로필에서만 활성화된다.
@Repository
@Profile("postgres")
public class JdbcAuthRepository implements AuthRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // "있으면 조회, 없으면 생성"을 한 문장으로 — SELECT 후 INSERT 두 단계는 동시 최초 로그인이
    // 겹칠 때 깨진다. email은 매 로그인 최신화(제공자 쪽 변경 반영), nickname은 최초 가입 값 유지
    // (DO UPDATE에서 제외 — 로그인할 때마다 브라우저 로컬 닉네임으로 덮이면 다른 기기 로그인 시
    // 서버 닉네임이 널뛴다).
    @Override
    public UserAccount upsertUser(String provider, String providerSubject, String email, String nickname) {
        return jdbc.queryForObject("""
                INSERT INTO users (provider, provider_subject, email, nickname)
                VALUES (:provider, :subject, :email, :nickname)
                ON CONFLICT (provider, provider_subject) DO UPDATE SET email = EXCLUDED.email
                RETURNING id, nickname, email
                """, new MapSqlParameterSource()
                        .addValue("provider", provider)
                        .addValue("subject", providerSubject)
                        .addValue("email", email)
                        .addValue("nickname", nickname),
                (rs, rowNum) -> new UserAccount(rs.getString("id"), rs.getString("nickname"), rs.getString("email")));
    }

    @Override
    public void createSession(String tokenHash, String userId, int ttlDays) {
        jdbc.update("""
                INSERT INTO auth_sessions (token_hash, user_id, expires_at)
                VALUES (:hash, :userId, now() + make_interval(days => :ttl))
                """, new MapSqlParameterSource()
                .addValue("hash", tokenHash)
                .addValue("userId", UUID.fromString(userId))
                .addValue("ttl", ttlDays));
    }

    // 만료 판정도 WHERE 안에 있다 — 서버(JVM) 시계가 아니라 DB 시계 하나로 발급·판정을 통일한다.
    @Override
    public Optional<String> findUserIdByToken(String tokenHash) {
        return jdbc.queryForList("""
                        SELECT user_id FROM auth_sessions
                         WHERE token_hash = :hash AND expires_at > now()
                        """, new MapSqlParameterSource("hash", tokenHash), UUID.class)
                .stream().findFirst().map(UUID::toString);
    }

    @Override
    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM auth_sessions WHERE token_hash = :hash",
                new MapSqlParameterSource("hash", tokenHash));
    }

    @Override
    public void deleteExpiredSessions() {
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at < now()", new MapSqlParameterSource());
    }

    // 게스트 → 사용자 re-key. 호출한 @Transactional 서비스 메서드의 트랜잭션 안에서 전부
    // 성공하거나 전부 되돌아간다. owner 컬럼은 FK 없는 TEXT라(전환기 두 종류 id 공존, V4 주석)
    // 값 치환만으로 이전이 끝난다.
    @Override
    public void absorbGuest(String userId, String guestId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("uid", userId)
                .addValue("gid", guestId);

        jdbc.update("UPDATE plans        SET owner    = :uid WHERE owner    = :gid", params);
        jdbc.update("UPDATE audit_events SET owner_id = :uid WHERE owner_id = :gid", params);
        jdbc.update("UPDATE challenges   SET owner    = :uid WHERE owner    = :gid", params);

        // 참가 충돌: 같은 챌린지에 사용자로도 게스트로도 참가돼 있으면 게스트 행을 버린다
        // (복합 PK (challenge_id, owner)라 둘 다 남길 수 없다). 버려진 참가의 entry_fee는
        // 환불하지 않는다 — 중복 참가 자체가 병합 불가능한 상태라 한쪽을 포기하는 게 규칙이다.
        jdbc.update("""
                DELETE FROM challenge_participants gp
                 WHERE gp.owner = :gid
                   AND EXISTS (SELECT 1 FROM challenge_participants up
                                WHERE up.challenge_id = gp.challenge_id AND up.owner = :uid)
                """, params);
        jdbc.update("UPDATE challenge_participants SET owner = :uid WHERE owner = :gid", params);

        // 지갑 병합: 사용자 지갑이 있으면 잔액 합산, 없으면 게스트 잔액 그대로 이관. 그 후 게스트
        // 행 삭제. ponytail: 새 게스트를 만들 때마다 초기 지급 잔액이 합산되는 어뷰징이 가능하지만
        // 포인트는 비금전 게이미피케이션이라 수용한다 — 문제가 되면 초기 지급분을 빼고 합산.
        jdbc.update("""
                INSERT INTO point_wallets (owner, balance)
                SELECT :uid, balance FROM point_wallets WHERE owner = :gid
                ON CONFLICT (owner) DO UPDATE SET balance = point_wallets.balance + EXCLUDED.balance
                """, params);
        jdbc.update("DELETE FROM point_wallets WHERE owner = :gid", params);
    }
}
