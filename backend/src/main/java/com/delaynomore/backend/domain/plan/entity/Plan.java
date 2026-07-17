package com.delaynomore.backend.domain.plan.entity;

import java.util.Map;

// 보관된 계획 한 건. 지금은 인메모리 보관용이지만, 추후 로그인+PostgreSQL 도입 시 이 record가
// 테이블 행과 1:1로 대응하도록 필드를 평탄하게 유지한다. tasks는 프론트 스키마 원본 그대로 보관.
public record Plan(
        Long id,                   // 서버 발급 ID (저장 전 null)
        String goalName,
        Integer duration,
        Integer dailyHours,
        String currentLevel,
        Map<String, Object> tasks, // {날짜: [{id, content, completed}]}
        String status,             // DRAFT | CONFIRMED(고정) — 프론트 상태 그대로 왕복
        String confirmedAt,        // 고정 시각(ISO 문자열, 미고정이면 null)
        String startDate,
        String endDate,
        String createdAt,          // 프론트가 만든 ISO 문자열 그대로
        long savedAt               // 서버 저장/갱신 시각(epoch ms) — 목록 정렬 기준
) {

    public Plan withId(long newId) {
        return new Plan(newId, goalName, duration, dailyHours, currentLevel, tasks,
                status, confirmedAt, startDate, endDate, createdAt, savedAt);
    }

    public boolean isConfirmed() {
        return "CONFIRMED".equals(status);
    }
}
