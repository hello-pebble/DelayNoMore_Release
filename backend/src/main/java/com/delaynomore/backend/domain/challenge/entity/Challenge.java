package com.delaynomore.backend.domain.challenge.entity;

import java.time.Duration;
import java.time.Instant;

// 정원이 한정된 목표 챌린지. 기존 Plan과 같이 테이블 행과 1:1인 평면 record다.
// participantCount는 파생값이 아니라 challenges 행의 실제 컬럼이다 — 정원 판정이
// "SELECT COUNT(*)"가 아니라 이 컬럼 하나에 대한 조건부 UPDATE로 끝나야 원자적이기 때문이다
// (docs/CONCURRENCY.md). 이 값은 참가자 INSERT와 같은 원자 구간 안에서만 증가하므로 어긋나지 않는다.
//
// conditionKey는 자동 생성의 근거가 된 조건("어학:14")이다. 같은 조건의 모집 중 챌린지가 둘
// 생기지 않게 막는 것도 이 값이며, 판정은 애플리케이션이 아니라 부분 UNIQUE 인덱스가 한다.
//
// 상태는 enum이 아니라 두 시각 컬럼의 null 여부로 파생한다(v0.25.0 정산):
//   모집중 = !started() / 진행중 = started() && !settled() / 종료 = settled()
// 전이가 두 번(시작·정산)뿐이고 각 전이의 판정 주체가 저장소의 원자 구간이라, 전이표를 따로
// 소유할 타입을 만들 이유가 없다(PlanStatus와 달리 여기엔 능력 플래그를 물을 손님도 없다).
public record Challenge(
        Long id,
        String owner,
        String title,
        int durationDays,
        int capacity,
        int entryFee,
        int participantCount,
        String createdAt,
        String conditionKey,
        String startedAt,
        String settledAt) {

    public Challenge withId(long newId) {
        return new Challenge(newId, owner, title, durationDays, capacity, entryFee, participantCount,
                createdAt, conditionKey, startedAt, settledAt);
    }

    public Challenge withParticipantCount(int newCount) {
        return new Challenge(id, owner, title, durationDays, capacity, entryFee, newCount,
                createdAt, conditionKey, startedAt, settledAt);
    }

    public Challenge withStartedAt(String newStartedAt) {
        return new Challenge(id, owner, title, durationDays, capacity, entryFee, participantCount,
                createdAt, conditionKey, newStartedAt, settledAt);
    }

    public Challenge withSettledAt(String newSettledAt) {
        return new Challenge(id, owner, title, durationDays, capacity, entryFee, participantCount,
                createdAt, conditionKey, startedAt, newSettledAt);
    }

    public boolean full() {
        return participantCount >= capacity;
    }

    public int remainingSeats() {
        return Math.max(0, capacity - participantCount);
    }

    public boolean started() {
        return startedAt != null;
    }

    public boolean settled() {
        return settledAt != null;
    }

    // 챌린지 종료 시각 — 시작(정원 찬 순간)부터 기간만큼. 시작 전에는 종료도 없다(null).
    public String endsAt() {
        return startedAt == null ? null
                : Instant.parse(startedAt).plus(Duration.ofDays(durationDays)).toString();
    }
}
