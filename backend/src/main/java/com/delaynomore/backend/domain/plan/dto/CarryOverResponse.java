package com.delaynomore.backend.domain.plan.dto;

// 미완료 이월(POST /plans/{id}/carry-over) 응답 — 이동 건수와 이월 후 계획 전체를 함께 준다.
// movedCount 0은 "이월할 미완료가 없음"의 정상 no-op(계획 불변)이다.
public record CarryOverResponse(int movedCount, String targetDate, PlanResponse plan) {
}
