package com.delaynomore.backend.domain.plan.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// 미완료 이월 연산 — 예전엔 프론트(carryOverTasks)가 계산해 PUT으로 보내던 것을 서버가 직접
// 수행한다(연산 소유권 이관). 의미는 프론트 구현 그대로: fromDate의 미완료 항목을 toDate 배열
// 뒤에 붙이고, 항목 ID는 보존한다(변경 이력이 "같은 할 일의 이동"으로 인식). fromDate가 비면
// 키를 지우고, 날짜 키 오름차순(YYYY-MM-DD라 사전순=날짜순)으로 재조립해 Day 순서를 지킨다.
// tasks 내부는 프론트 원본 그대로 보관된 opaque 구조라 방어적으로 다룬다(PlanTaskDiff와 같은 태도).
final class PlanCarryOver {

    record Result(Map<String, Object> tasks, int movedCount) {
    }

    private PlanCarryOver() {
    }

    static Result apply(Map<String, Object> tasks, String fromDate, String toDate) {
        Object dayTasks = tasks == null ? null : tasks.get(fromDate);
        if (!(dayTasks instanceof List<?> list)) {
            return new Result(tasks, 0);
        }
        List<Object> remaining = new ArrayList<>();
        List<Object> moved = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> task && Boolean.TRUE.equals(task.get("completed"))) {
                remaining.add(item);
            } else {
                moved.add(item);
            }
        }
        if (moved.isEmpty()) {
            return new Result(tasks, 0);
        }

        Object destination = tasks.get(toDate);
        List<Object> merged = destination instanceof List<?> destList
                ? new ArrayList<>(destList)
                : new ArrayList<>();
        // 목적지에 같은 ID가 이미 있으면(비정상 데이터) 접미사로 회피 — 프론트 렌더 key 충돌 방지.
        Set<Object> existingIds = new HashSet<>();
        for (Object item : merged) {
            if (item instanceof Map<?, ?> task) {
                existingIds.add(task.get("id"));
            }
        }
        for (Object item : moved) {
            if (item instanceof Map<?, ?> task && task.get("id") != null && existingIds.contains(task.get("id"))) {
                Map<String, Object> renamed = new LinkedHashMap<>();
                task.forEach((k, v) -> renamed.put(String.valueOf(k), v));
                renamed.put("id", task.get("id") + "-m" + System.currentTimeMillis());
                merged.add(renamed);
            } else {
                merged.add(item);
            }
        }

        // TreeMap으로 날짜 키를 정렬한 뒤 LinkedHashMap으로 옮긴다 — JSON 직렬화 순서가 곧
        // 프론트의 Day 순서라, 새 키가 끝에 붙어 순서가 깨지는 것을 막는다.
        Map<String, Object> sorted = new TreeMap<>(tasks);
        if (remaining.isEmpty()) {
            sorted.remove(fromDate);
        } else {
            sorted.put(fromDate, remaining);
        }
        sorted.put(toDate, merged);
        return new Result(new LinkedHashMap<>(sorted), moved.size());
    }
}
