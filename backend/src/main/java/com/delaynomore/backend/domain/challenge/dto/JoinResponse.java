package com.delaynomore.backend.domain.challenge.dto;

// 참가 성공 응답 — 갱신된 챌린지와 차감 후 잔액. 실패는 예외(ErrorCode)로만 표현한다.
public record JoinResponse(ChallengeResponse challenge, int balance) {
}
