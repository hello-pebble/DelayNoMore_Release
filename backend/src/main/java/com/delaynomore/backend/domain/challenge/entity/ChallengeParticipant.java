package com.delaynomore.backend.domain.challenge.entity;

// 챌린지 참가 레코드(challenge_participants 행과 1:1인 값 부분).
// planId는 참가 시점에 서버가 자동 연결한 완주 판정 근거 계획 — 계획이 삭제되면 null이 되고
// (JDBC: FK ON DELETE SET NULL, 인메모리: findById empty), 정산에서 패배로 수렴한다.
// payout은 정산 결과: null = 미정산, 0 = 미완주(패배), 양수 = 배당 또는 환불액.
// completedAt은 "마감 전까지 100% 완료 상태"의 서버 기록(v0.34.0): null = 미완주. 마감·정산 이후의
// 변경은 기록되지 않으므로(저장소의 markCompletion 조건) 마감 시각의 상태가 동결되고, 정산은 이
// 값만 본다 — 정산 시점의 완료율을 다시 읽으면 마감 후 체크가 인정될 수 있다.
public record ChallengeParticipant(String owner, Long planId, Integer payout, String completedAt) {

    public ChallengeParticipant withPayout(int amount) {
        return new ChallengeParticipant(owner, planId, amount, completedAt);
    }

    public ChallengeParticipant withCompletedAt(String newCompletedAt) {
        return new ChallengeParticipant(owner, planId, payout, newCompletedAt);
    }
}
