package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.entity.Plan;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 계획 두 상태의 비교(diff) 유틸 — AuditEventService(이벤트 종류 복원)와
 * PlanService(고정 계획 가드: "completed 토글 외 구조 변경" 거부)가 같은 판정 기준을 공유한다.
 * tasks는 프론트 원본 그대로(opaque Map)라 방어적으로 파싱한다(ReflectionService.countTodayTasks와
 * 같은 instanceof 가드). 항목 키는 id를 우선 쓰고, id가 없으면 배열 위치(idx:n)로 대신한다.
 */
final class PlanTaskDiff {

    private PlanTaskDiff() {
    }

    record TaskView(String content, boolean completed) {}

    static Map<String, Map<String, TaskView>> parseTasks(Map<String, Object> tasks) {
        Map<String, Map<String, TaskView>> parsed = new TreeMap<>(); // 날짜 키 정렬 → 발행 순서 안정
        if (tasks == null) return parsed;
        for (Map.Entry<String, Object> dayEntry : tasks.entrySet()) {
            if (!(dayEntry.getValue() instanceof List<?> list)) continue;
            Map<String, TaskView> day = new LinkedHashMap<>();
            int index = 0;
            for (Object item : list) {
                if (item instanceof Map<?, ?> task) {
                    Object id = task.get("id");
                    String key = id != null ? String.valueOf(id) : "idx:" + index;
                    String content = task.get("content") != null ? String.valueOf(task.get("content")) : "";
                    day.put(key, new TaskView(content, Boolean.TRUE.equals(task.get("completed"))));
                }
                index++;
            }
            parsed.put(dayEntry.getKey(), day);
        }
        return parsed;
    }

    // 구조 변경 여부 — completed(완료 토글로 처리)·status/confirmedAt(고정으로 처리)·savedAt(매 PUT마다
    // 변함)을 제외한 정규화 뷰가 다른가? 스칼라 + 날짜별 (항목키 → content) 맵을 비교한다.
    static boolean hasStructuralChange(Plan previous, Plan updated,
                                       Map<String, Map<String, TaskView>> prevTasks,
                                       Map<String, Map<String, TaskView>> nextTasks) {
        if (!Objects.equals(previous.goalName(), updated.goalName())
                || !Objects.equals(previous.duration(), updated.duration())
                || !Objects.equals(previous.dailyHours(), updated.dailyHours())
                || !Objects.equals(previous.currentLevel(), updated.currentLevel())
                || !Objects.equals(previous.startDate(), updated.startDate())
                || !Objects.equals(previous.endDate(), updated.endDate())) {
            return true;
        }
        return !contentView(prevTasks).equals(contentView(nextTasks));
    }

    static Map<String, Map<String, String>> contentView(Map<String, Map<String, TaskView>> tasks) {
        Map<String, Map<String, String>> view = new HashMap<>();
        for (Map.Entry<String, Map<String, TaskView>> dayEntry : tasks.entrySet()) {
            Map<String, String> day = new HashMap<>();
            dayEntry.getValue().forEach((key, task) -> day.put(key, task.content()));
            view.put(dayEntry.getKey(), day);
        }
        return view;
    }
}
