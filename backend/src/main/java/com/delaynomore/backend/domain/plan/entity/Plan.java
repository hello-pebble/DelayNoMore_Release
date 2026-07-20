package com.delaynomore.backend.domain.plan.entity;

import java.util.List;
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

    // {완료, 전체} 개수 묶음 — 진행률(PlanResponse.progress)과 회고 완료 개수 재계산이 공유한다.
    public record TaskCounts(int completed, int total) {
    }

    // 전 날짜 합산 완료/전체 개수 — 완료율 계산은 서버 소유(프론트는 표시만).
    public TaskCounts countAllTasks() {
        if (tasks == null) {
            return new TaskCounts(0, 0);
        }
        int completed = 0;
        int total = 0;
        for (Object dayTasks : tasks.values()) {
            TaskCounts day = countList(dayTasks);
            completed += day.completed();
            total += day.total();
        }
        return new TaskCounts(completed, total);
    }

    // 특정 날짜의 완료/전체 개수 — 회고 저장 시 클라이언트 수치를 믿지 않고 서버가 재계산한다.
    public TaskCounts countTasksOn(String date) {
        return countList(tasks == null ? null : tasks.get(date));
    }

    // 날짜 범위(from ≤ 키 ≤ to, 양끝 포함) 합산 완료/전체 개수 — 주간 요약이 주 버킷마다 호출한다.
    // YYYY-MM-DD는 사전순=시간순이라 문자열 비교로 범위를 판정한다(PlanDates 관례와 동일).
    public TaskCounts countTasksBetween(String fromInclusive, String toInclusive) {
        if (tasks == null || fromInclusive == null || toInclusive == null) {
            return new TaskCounts(0, 0);
        }
        int completed = 0;
        int total = 0;
        for (Map.Entry<String, Object> entry : tasks.entrySet()) {
            String key = entry.getKey();
            if (key.compareTo(fromInclusive) < 0 || key.compareTo(toInclusive) > 0) {
                continue;
            }
            TaskCounts day = countList(entry.getValue());
            completed += day.completed();
            total += day.total();
        }
        return new TaskCounts(completed, total);
    }

    // tasks 내부 구조는 프론트 원본 그대로라 방어적으로 센다: 날짜 값이 List가 아니면 빈 것으로
    // (0/0 허용 — 할 일 없는 날의 회고도 유효), 항목은 Map이면서 completed == Boolean.TRUE인
    // 것만 완료로 센다.
    private static TaskCounts countList(Object dayTasks) {
        if (!(dayTasks instanceof List<?> list)) {
            return new TaskCounts(0, 0);
        }
        int completed = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> task && Boolean.TRUE.equals(task.get("completed"))) {
                completed++;
            }
        }
        return new TaskCounts(completed, list.size());
    }
}
