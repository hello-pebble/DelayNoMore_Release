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
    PLAN_DELETED("계획 삭제");

    private final String label;
}
