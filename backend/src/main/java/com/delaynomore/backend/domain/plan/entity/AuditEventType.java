package com.delaynomore.backend.domain.plan.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 변경 이력 이벤트 종류 — 코드·화면 라벨의 소스오브트루스. 발행(AuditEventService)은 이
// enum으로만 하고, 저장·응답(AuditEvent.type)은 String을 유지한다(DB 행 1:1 관례,
// 기존 데이터·응답 형식 불변). 프론트 라벨은 메타 API로 내려간다.
@Getter
@RequiredArgsConstructor
public enum AuditEventType {
    PLAN_CREATED("계획 생성"),
    PLAN_UPDATED("계획 수정"),
    PLAN_CONFIRMED("계획 고정"),
    TASK_COMPLETED("할 일 완료"),
    TASK_REOPENED("완료 해제"),
    REFLECTION_SAVED("회고 저장"),
    PLAN_DELETED("계획 삭제"),
    // 다음 계획 분량 추천(로드맵 4·5번) — 조회·선택·생성 흐름을 추적한다.
    WORKLOAD_RECOMMENDATION_VIEWED("다음 분량 추천 조회"),
    WORKLOAD_RECOMMENDATION_ACCEPTED("추천 분량 채택"),
    WORKLOAD_RECOMMENDATION_OVERRIDDEN("추천 분량 변경"),
    PLAN_CREATED_FROM_RECOMMENDATION("추천 기반 계획 생성");

    private final String label;
}
