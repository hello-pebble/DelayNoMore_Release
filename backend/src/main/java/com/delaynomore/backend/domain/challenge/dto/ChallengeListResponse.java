package com.delaynomore.backend.domain.challenge.dto;

import java.util.List;

// 목록 + 내 포인트 잔액을 한 번에 돌려준다 — 화면이 항상 둘을 함께 그리므로 별도 지갑 엔드포인트를
// 두지 않는다(요청 1회, 두 값의 시점 불일치도 없음).
public record ChallengeListResponse(int balance, List<ChallengeResponse> challenges) {
}
