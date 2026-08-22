package com.delaynomore.backend.domain.auth.repository;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.jdbc.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// JdbcAuthRepository 통합 테스트 — 사용자 upsert 원자성, 세션 만료 판정, 게스트 흡수(re-key)의
// 충돌 규칙(지갑 합산·중복 참가 폐기)과 멱등성.
class JdbcAuthRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private AuthRepository authRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void upsertUser_sameSubjectTwice_singleUserWithRefreshedEmailAndOriginalNickname() {
        AuthRepository.UserAccount first = authRepository.upsertUser("google", "sub-1", "a@x.com", "닉네임하나");
        AuthRepository.UserAccount second = authRepository.upsertUser("google", "sub-1", "b@x.com", "다른닉네임");

        assertThat(second.id()).isEqualTo(first.id());                 // 같은 sub → 같은 사용자
        assertThat(second.email()).isEqualTo("b@x.com");               // email은 매 로그인 최신화
        assertThat(second.nickname()).isEqualTo("닉네임하나");           // nickname은 최초 가입 값 유지
        Integer userCount = jdbc.queryForObject("SELECT count(*) FROM users",
                new MapSqlParameterSource(), Integer.class);
        assertThat(userCount).isEqualTo(1);
    }

    @Test
    void findUserIdByToken_expiredSessionIsRejected() {
        String userId = authRepository.upsertUser("google", "sub-1", null, null).id();
        authRepository.createSession("hash-valid", userId, 30);
        authRepository.createSession("hash-expired", userId, 30);
        // 만료를 기다릴 수 없으니 직접 과거로 되돌린다 — 판정은 WHERE expires_at > now()가 한다.
        jdbc.update("UPDATE auth_sessions SET expires_at = now() - interval '1 minute' WHERE token_hash = :h",
                new MapSqlParameterSource("h", "hash-expired"));

        assertThat(authRepository.findUserIdByToken("hash-valid")).contains(userId);
        assertThat(authRepository.findUserIdByToken("hash-expired")).isEmpty();
        assertThat(authRepository.findUserIdByToken("hash-unknown")).isEmpty();

        authRepository.deleteExpiredSessions();
        Integer remaining = jdbc.queryForObject("SELECT count(*) FROM auth_sessions",
                new MapSqlParameterSource(), Integer.class);
        assertThat(remaining).isEqualTo(1); // 만료 행만 청소됐다
    }

    @Test
    void absorbGuest_rekeysPlans_mergesWallet_dropsConflictingParticipation_andIsIdempotent() {
        String guestId = "guest-absorb-1";
        String userId = authRepository.upsertUser("google", "sub-1", null, null).id();

        // 게스트의 계획 1건 + 챌린지 참가로 지갑 생성(1000 - 100 = 900)
        planRepository.save(new Plan(null, guestId, "목표", 1, 1, "초급",
                Map.of(), "DRAFT", null, null, "2026-08-19", "2026-08-19", "2026-08-19T09:00:00Z", 1L, null));
        Challenge challenge = challengeRepository.save(new Challenge(null, guestId, "같이 달리기",
                7, 10, 100, 0, "2026-08-19T09:00:00Z", null));
        challengeRepository.join(challenge.id(), guestId, "2026-08-19T09:10:00Z");

        // 사용자도 같은 챌린지에 이미 참가(1000 - 100 = 900) — 흡수 시 게스트 참가 행은 버려져야 한다.
        challengeRepository.join(challenge.id(), userId, "2026-08-19T09:20:00Z");

        authRepository.absorbGuest(userId, guestId);

        // 계획·챌린지 소유가 사용자로 넘어갔다
        assertThat(planRepository.findAllByOwner(userId)).hasSize(1);
        assertThat(planRepository.findAllByOwner(guestId)).isEmpty();
        assertThat(challengeRepository.findById(challenge.id()).orElseThrow().owner()).isEqualTo(userId);

        // 참가는 사용자 행 하나만 남는다(게스트 중복 참가는 폐기, entry_fee 환불 없음)
        Integer participantRows = jdbc.queryForObject(
                "SELECT count(*) FROM challenge_participants WHERE challenge_id = :id",
                new MapSqlParameterSource("id", challenge.id()), Integer.class);
        assertThat(participantRows).isEqualTo(1);
        assertThat(challengeRepository.findJoinedChallengeIds(userId)).containsExactly(challenge.id());

        // 지갑은 합산(900 + 900) 후 게스트 행 삭제
        assertThat(challengeRepository.balanceOf(userId)).isEqualTo(1800);
        Integer guestWallets = jdbc.queryForObject(
                "SELECT count(*) FROM point_wallets WHERE owner = :o",
                new MapSqlParameterSource("o", guestId), Integer.class);
        assertThat(guestWallets).isZero();

        // 멱등성 — 두 번째 흡수는 전부 0-row no-op이라 아무것도 바뀌지 않는다
        authRepository.absorbGuest(userId, guestId);
        assertThat(planRepository.findAllByOwner(userId)).hasSize(1);
        assertThat(challengeRepository.balanceOf(userId)).isEqualTo(1800);
    }
}
