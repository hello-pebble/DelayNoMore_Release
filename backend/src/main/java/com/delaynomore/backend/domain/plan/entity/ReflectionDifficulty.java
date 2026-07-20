package com.delaynomore.backend.domain.plan.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 회고 체감 난이도 선택지 — 코드·라벨의 소스오브트루스. 프론트는 메타 API로 받아 렌더링만
// 하고(서버 미가용 시 폴백 사본만 보유), 저장 검증(ReflectionSaveRequest)도 이 enum과
// 드리프트 가드 테스트로 일치를 보장한다.
@Getter
@RequiredArgsConstructor
public enum ReflectionDifficulty {
    EASY("여유로웠어요"),
    NORMAL("적당했어요"),
    HARD("벅찼어요");

    private final String label;
}
