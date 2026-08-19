package com.delaynomore.backend.domain.challenge.dto;

import com.delaynomore.backend.domain.challenge.entity.Challenge;

// 챌린지 단건 응답. mine·joined는 "요청한 게스트 기준"의 파생 값이라 엔티티가 아니라 여기서 채운다
// (프론트가 소유자 비교 로직을 다시 갖지 않게 — 규칙 소유권은 서버).
public record ChallengeResponse(
        long id,
        String title,
        int durationDays,
        int capacity,
        int entryFee,
        int participantCount,
        int remainingSeats,
        boolean full,
        boolean mine,
        boolean joined,
        String createdAt) {

    public static ChallengeResponse from(Challenge challenge, String viewer, boolean joined) {
        return new ChallengeResponse(
                challenge.id(),
                challenge.title(),
                challenge.durationDays(),
                challenge.capacity(),
                challenge.entryFee(),
                challenge.participantCount(),
                challenge.remainingSeats(),
                challenge.full(),
                challenge.owner().equals(viewer),
                joined,
                challenge.createdAt());
    }
}
