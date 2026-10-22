package com.delaynomore.backend.domain.challenge.dto;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.entity.ChallengeParticipant;

// 챌린지 단건 응답. joined·myPayout은 "요청한 게스트 기준"의 파생 값이라 엔티티가 아니라 여기서
// 채운다(프론트가 참가 여부·정산 판정을 다시 갖지 않게 — 규칙 소유권은 서버).
// status도 서버가 파생한다: RECRUITING(모집중) / ACTIVE(진행중) / CLOSED(정산 종료) /
// REVIEWING(기간은 끝났지만 정산을 보류하고 확인 중 — v0.34.0 이전에 이미 마감이 지난 건). 프론트는
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
        Integer myPayout,  // null = 미참가 또는 미정산, 0 = 미완주, 양수 = 배당/환불액
        MyPlan myPlan) {   // 미참가면 null

    // 내가 이 챌린지 참가에 쓴 목표. deleted = 목표가 삭제돼 연결이 끊김(JDBC: plan_id SET NULL).
    // completed = 서버가 기록한 "현재 완주 상태"(completed_at != null) — 마감 전에는 체크를 해제하면
    // 바뀔 수 있으므로 화면은 이를 "완주 확정"으로 표현하지 않는다. 확정은 status가 CLOSED가 된 뒤다.
    public record MyPlan(String goalName, String status, int ratePercent, boolean deleted, boolean completed) {
    }

    public static ChallengeResponse from(Challenge challenge, ChallengeParticipant mine, boolean held, MyPlan myPlan) {
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
                statusOf(challenge, held),
                challenge.endsAt(),
                mine == null ? null : mine.payout(),
                myPlan);
    }

    private static String statusOf(Challenge challenge, boolean held) {
        if (challenge.settled()) {
            return "CLOSED";
        }
        if (held) {
            return "REVIEWING";
        }
        return challenge.started() ? "ACTIVE" : "RECRUITING";
    }
}
