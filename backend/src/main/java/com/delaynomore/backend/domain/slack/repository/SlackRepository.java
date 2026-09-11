package com.delaynomore.backend.domain.slack.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 슬랙 연결·연결 코드·발송 클레임·이벤트 중복 제거 저장소.
 *
 * <p>클레임 메서드(claimDailySend·reclaimUnsent·claimEvent)는 boolean을 반환한다 —
 * true를 받은 호출만 해당 작업(발송·이벤트 처리)의 권리를 가진다. 판정 주체는 저장소의
 * 원자 연산(JDBC는 조건부 INSERT/UPDATE, 인메모리는 키 단위 원자 구간)이지 호출부의
 * 사전 검사가 아니다(docs/CONCURRENCY.md의 관통 원칙).
 */
public interface SlackRepository {

    /** 슬랙 연결 한 건. activeStartMin/EndMin은 KST 자정 기준 분(v0.26은 start만 사용). */
    record SlackLink(String owner, String teamId, String slackUserId, String channelId,
                     int activeStartMin, int activeEndMin) {
    }

    // --- 연결(link) ---

    Optional<SlackLink> findLinkByOwner(String owner);

    Optional<SlackLink> findLinkBySlackUser(String teamId, String slackUserId);

    /** 연결 저장(owner 기준 교체). 같은 슬랙 계정이 다른 owner에 연결돼 있으면 그 행을 먼저 지운다. */
    void upsertLink(SlackLink link);

    void deleteLinkByOwner(String owner);

    /** 발송 루프가 도는 전체 연결 목록 — 데모 규모(수십 건)라 전체 스캔으로 충분하다. */
    List<SlackLink> findAllLinks();

    // --- 연결 코드 ---

    /** 코드 저장. 같은 owner의 기존 코드는 교체한다(재발급 = 이전 코드 무효). */
    void saveLinkCode(String code, String owner, int ttlMinutes);

    /**
     * 코드 소비 — 유효(미만료)한 코드면 owner를 돌려주며 그 자리에서 삭제한다(재사용 불가).
     * 삭제와 판정이 한 연산이라 같은 코드를 동시에 입력해도 한 번만 성공한다.
     */
    Optional<String> consumeLinkCode(String code);

    /** 만료 코드 lazy 청소(발급 시 호출 — V5 세션 관례). */
    void deleteExpiredLinkCodes();

    // --- 일일 발송 클레임 ---

    /** 첫 클레임 — (owner, date, kind) 행 INSERT. false = 이미 클레임됨. */
    boolean claimDailySend(String owner, LocalDate date, String kind);

    /**
     * 미전송 행의 재시도권 클레임 — sent_at이 비어 있고 직전 클레임에서 retryAfterMinutes가
     * 지났으며 attempts가 maxAttempts 미만인 행만 정확히 한 호출이 가져간다.
     */
    boolean reclaimUnsent(String owner, LocalDate date, String kind,
                          int retryAfterMinutes, int maxAttempts);

    /** 전송 확인 기록 — 이후 재클레임 대상에서 빠진다. */
    void markSent(String owner, LocalDate date, String kind);

    // --- 이벤트 중복 제거 ---

    /** event_id 클레임. false = 이미 받은 이벤트(Slack 재전송) — 처리하지 않는다. */
    boolean claimEvent(String eventId);

    /** 하루 지난 dedup 행 lazy 청소(발송 루프에서 호출). */
    void deleteOldEvents();
}
