package com.delaynomore.backend.domain.challenge.entity;

// 챌린지 참가 레코드(challenge_participants 행과 1:1인 값 부분).
// planId는 참가 시점에 서버가 자동 연결한 완주 판정 근거 계획 — 계획이 삭제되면 null이 되고
// (JDBC: FK ON DELETE SET NULL, 인메모리: findById empty), 정산에서 패배로 수렴한다.
// payout은 정산 결과: null = 미정산, 0 = 미완주(패배), 양수 = 배당 또는 환불액.
public record ChallengeParticipant(String owner, Long planId, Integer payout) {

    public ChallengeParticipant withPayout(int amount) {
        return new ChallengeParticipant(owner, planId, amount);
    }
}
