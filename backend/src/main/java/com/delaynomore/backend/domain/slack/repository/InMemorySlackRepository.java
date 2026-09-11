package com.delaynomore.backend.domain.slack.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// 슬랙 저장소 인메모리 구현 — postgres 프로필이 아닐 때(단위 테스트·로컬)만 활성화된다.
// 클레임의 원자성은 ConcurrentHashMap의 키 단위 원자 구간(putIfAbsent/compute)이 담당한다 —
// JDBC 구현의 조건부 INSERT/UPDATE와 같은 계약(정확히 한 호출만 true)을 지킨다.
@Repository
@Profile("!postgres")
public class InMemorySlackRepository implements SlackRepository {

    private record LinkCode(String owner, Instant expiresAt) {
    }

    private static final class DailySend {
        int attempts = 1;
        Instant claimedAt = Instant.now();
        Instant sentAt;
    }

    private final ConcurrentHashMap<String, SlackLink> linksByOwner = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkCode> codes = new ConcurrentHashMap<>();
    // key: owner + "/" + date + "/" + kind
    private final ConcurrentHashMap<String, DailySend> dailySends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> eventDedup = new ConcurrentHashMap<>();

    @Override
    public Optional<SlackLink> findLinkByOwner(String owner) {
        return Optional.ofNullable(linksByOwner.get(owner));
    }

    @Override
    public Optional<SlackLink> findLinkBySlackUser(String teamId, String slackUserId) {
        return linksByOwner.values().stream()
                .filter(link -> link.teamId().equals(teamId) && link.slackUserId().equals(slackUserId))
                .findFirst();
    }

    @Override
    public void upsertLink(SlackLink link) {
        // 같은 슬랙 계정의 기존 연결(다른 owner)을 먼저 지운다 — JDBC의 UNIQUE 제약과 같은 의미.
        findLinkBySlackUser(link.teamId(), link.slackUserId())
                .filter(existing -> !existing.owner().equals(link.owner()))
                .ifPresent(existing -> linksByOwner.remove(existing.owner()));
        linksByOwner.put(link.owner(), link);
    }

    @Override
    public void deleteLinkByOwner(String owner) {
        linksByOwner.remove(owner);
    }

    @Override
    public List<SlackLink> findAllLinks() {
        return List.copyOf(linksByOwner.values());
    }

    @Override
    public void saveLinkCode(String code, String owner, int ttlMinutes) {
        codes.entrySet().removeIf(e -> e.getValue().owner().equals(owner)); // 재발급 = 이전 코드 무효
        codes.put(code, new LinkCode(owner, Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES)));
    }

    @Override
    public Optional<String> consumeLinkCode(String code) {
        LinkCode removed = codes.remove(code); // 판정과 삭제가 한 연산 — 동시 입력도 한 번만 성공
        if (removed == null || removed.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(removed.owner());
    }

    @Override
    public void deleteExpiredLinkCodes() {
        codes.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
    }

    @Override
    public boolean claimDailySend(String owner, LocalDate date, String kind) {
        return dailySends.putIfAbsent(sendKey(owner, date, kind), new DailySend()) == null;
    }

    @Override
    public boolean reclaimUnsent(String owner, LocalDate date, String kind,
                                 int retryAfterMinutes, int maxAttempts) {
        boolean[] claimed = {false};
        dailySends.computeIfPresent(sendKey(owner, date, kind), (key, send) -> {
            if (send.sentAt == null
                    && send.claimedAt.isBefore(Instant.now().minus(retryAfterMinutes, ChronoUnit.MINUTES))
                    && send.attempts < maxAttempts) {
                send.attempts++;
                send.claimedAt = Instant.now();
                claimed[0] = true;
            }
            return send;
        });
        return claimed[0];
    }

    @Override
    public void markSent(String owner, LocalDate date, String kind) {
        dailySends.computeIfPresent(sendKey(owner, date, kind), (key, send) -> {
            send.sentAt = Instant.now();
            return send;
        });
    }

    @Override
    public boolean claimEvent(String eventId) {
        return eventDedup.putIfAbsent(eventId, Instant.now()) == null;
    }

    @Override
    public void deleteOldEvents() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        eventDedup.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    private static String sendKey(String owner, LocalDate date, String kind) {
        return owner + "/" + date + "/" + kind;
    }
}
