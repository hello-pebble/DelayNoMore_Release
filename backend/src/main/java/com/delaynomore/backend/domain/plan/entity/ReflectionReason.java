package com.delaynomore.backend.domain.plan.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 회고 이유 선택지 — 코드·라벨의 소스오브트루스(ReflectionDifficulty와 같은 체제).
@Getter
@RequiredArgsConstructor
public enum ReflectionReason {
    AS_PLANNED("계획대로 진행됐어요"),
    NOT_ENOUGH_TIME("시간이 부족했어요"),
    TOO_MUCH_WORK("분량이 많았어요"),
    HARD_TO_FOCUS("집중이 잘 안 됐어요"),
    HARDER_THAN_EXPECTED("생각보다 어려웠어요");

    private final String label;
}
