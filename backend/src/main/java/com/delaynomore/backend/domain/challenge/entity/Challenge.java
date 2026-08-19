package com.delaynomore.backend.domain.challenge.entity;

// 정원이 한정된 목표 챌린지. 기존 Plan과 같이 테이블 행과 1:1인 평면 record다.
// participantCount는 파생값이 아니라 challenges 행의 실제 컬럼이다 — 정원 판정이
// "SELECT COUNT(*)"가 아니라 이 컬럼 하나에 대한 조건부 UPDATE로 끝나야 원자적이기 때문이다
// (docs/CONCURRENCY.md). 이 값은 참가자 INSERT와 같은 원자 구간 안에서만 증가하므로 어긋나지 않는다.
public record Challenge(
        Long id,
        String owner,
        String title,
        int durationDays,
        int capacity,
        int entryFee,
        int participantCount,
        String createdAt) {

    public Challenge withId(long newId) {
        return new Challenge(newId, owner, title, durationDays, capacity, entryFee, participantCount, createdAt);
    }

    public Challenge withParticipantCount(int newCount) {
        return new Challenge(id, owner, title, durationDays, capacity, entryFee, newCount, createdAt);
    }

    public boolean full() {
        return participantCount >= capacity;
    }

    public int remainingSeats() {
        return Math.max(0, capacity - participantCount);
    }
}
