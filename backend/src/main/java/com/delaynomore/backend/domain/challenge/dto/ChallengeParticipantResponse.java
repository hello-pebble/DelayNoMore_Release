package com.delaynomore.backend.domain.challenge.dto;

// 챌린지 참가자 현황 한 줄 — 리더보드용(v0.25.0).
// 소유자 식별자는 싣지 않는다: 닉네임은 서버가 모르고(프론트 전용 라벨), 게스트 ID는 데이터를
// 여는 bearer 성격이라 남에게 노출하면 안 된다. 그래서 응답은 익명이고, "누가 나인가"만
// me 플래그로 표시한다. 순위는 배열 순서(완료율 내림차순)가 곧 순위다.
// payout은 정산 후에만 값이 있다(null = 미정산).
public record ChallengeParticipantResponse(
        int ratePercent,
        int done,
        int total,
        boolean me,
        Integer payout) {
}
