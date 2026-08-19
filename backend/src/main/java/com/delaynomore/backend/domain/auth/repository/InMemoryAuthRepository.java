package com.delaynomore.backend.domain.auth.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 사용자·세션 인메모리 구현 — postgres 프로필이 아닐 때(단위 테스트·롤백 경로)만 활성화된다.
// 재시작 시 전부 사라지는 휘발성이라 로그인 기능의 실사용 전제는 postgres 프로필이다.
@Repository
@Profile("!postgres")
public class InMemoryAuthRepository implements AuthRepository {

    private record User(String id, String email, String nickname) {
    }

    private record Session(String userId, Instant expiresAt) {
    }

    // key: provider + "/" + providerSubject
    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public UserAccount upsertUser(String provider, String providerSubject, String email, String nickname) {
        User user = users.compute(provider + "/" + providerSubject, (key, existing) -> existing == null
                ? new User(UUID.randomUUID().toString(), email, nickname)
                : new User(existing.id(), email, existing.nickname())); // email 최신화, nickname은 가입 값 유지
        return new UserAccount(user.id(), user.nickname(), user.email());
    }

    @Override
    public void createSession(String tokenHash, String userId, int ttlDays) {
        sessions.put(tokenHash, new Session(userId, Instant.now().plus(ttlDays, ChronoUnit.DAYS)));
    }

    @Override
    public Optional<String> findUserIdByToken(String tokenHash) {
        Session session = sessions.get(tokenHash);
        if (session == null || session.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(session.userId());
    }

    @Override
    public void deleteSession(String tokenHash) {
        sessions.remove(tokenHash);
    }

    @Override
    public void deleteExpiredSessions() {
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
    }

    // 인메모리 모드에서는 no-op — 계획·챌린지가 각자의 인메모리 저장소(다른 빈의 맵)에 있어
    // 여기서 옮길 수 없고, 휘발성 데모 모드에 이관 기능까지 구현할 가치가 없다. 흡수의 실측
    // 검증은 JDBC 구현 + 통합 테스트가 담당한다.
    @Override
    public void absorbGuest(String userId, String guestId) {
    }
}
