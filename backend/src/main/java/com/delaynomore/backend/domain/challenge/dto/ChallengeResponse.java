package com.delaynomore.backend.domain.challenge.dto;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.entity.ChallengeParticipant;

// 챌린지 단건 응답. joined·myPayout은 "요청한 게스트 기준"의 파생 값이라 엔티티가 아니라 여기서
// 채운다(프론트가 참가 여부·정산 판정을 다시 갖지 않게 — 규칙 소유권은 서버).
// status도 서버가 파생한다: RECRUITING(모집중) / ACTIVE(진행중) / CLOSED(정산 종료). 프론트는
// 이 문자열을 그리기만 한다.
public record ChallengeResponse(
        long id,
        String title,
        int durationDays,
        int capacity,
        int entryFee,
        int participantCount,
        int remainingSeats,
        boolean full,
        boolean joined,
        String createdAt,
        String status,
        String endsAt,     // 시작 전에는 null — 정원이 찬 순간부터 기간을 센다
        Integer myPayout) { // null = 미참가 또는 미정산, 0 = 미완주, 양수 = 배당/환불액

    public static ChallengeResponse from(Challenge challenge, ChallengeParticipant mine) {
        return new ChallengeResponse(
                challenge.id(),
                challenge.title(),
                challenge.durationDays(),
                challenge.capacity(),
                challenge.entryFee(),
                challenge.participantCount(),
                challenge.remainingSeats(),
                challenge.full(),
                mine != null,
                challenge.createdAt(),
                challenge.settled() ? "CLOSED" : challenge.started() ? "ACTIVE" : "RECRUITING",
                challenge.endsAt(),
                mine == null ? null : mine.payout());
    }
}
