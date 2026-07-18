package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.entity.AuditEvent;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 계획 변경 이력 발행기 — 계획을 바꾸는 서비스(PlanService·ReflectionService)가 변경 직후 호출한다.
 * 프론트의 모든 변경(내용 수정·고정·완료 토글)이 PUT 전체 교체 하나로 들어오므로, 서버가
 * 이전 상태와 새 상태를 비교(diff)해 이벤트 종류를 복원한다 — 회고의 완료 개수처럼
 * 클라이언트가 보낸 의미를 믿는 대신 서버가 계산한다.
 * 기록은 인메모리 append라 실패할 일이 없지만, 어떤 경우에도 감사 기록이 본 변경을 깨서는 안 된다.
 */
@Service
@RequiredArgsConstructor
public class AuditEventService {

    // 임의 헤더를 그대로 저장하지 않기 위한 방어 — 트림 후 비면 null, 길면 절단.
    private static final int MAX_SESSION_ID_LENGTH = 64;
    private static final String GENERIC_UPDATE_DETAIL = "계획 내용 변경";

    private final AuditEventRepository auditEventRepository;

    public void recordPlanCreated(Plan saved, String sessionId) {
        append(saved.id(), "PLAN_CREATED", null, sessionId);
    }

    public void recordPlanDeleted(Plan deleted, String sessionId) {
        append(deleted.id(), "PLAN_DELETED", "\"" + deleted.goalName() + "\" 삭제", sessionId);
    }

    public void recordReflectionSaved(long planId, String date, int completedCount, int totalCount,
                                      String sessionId) {
        append(planId, "REFLECTION_SAVED",
                date + " 회고 저장 (" + completedCount + "/" + totalCount + " 완료)", sessionId);
    }

    // 갱신 diff 분류 — 한 PUT에서 0..n건을 발행한다(디바운스 배칭으로 토글 여러 개가 한 번에 올 수 있다).
    // 발행 순서: PLAN_CONFIRMED → TASK_*(날짜·항목 순) → PLAN_UPDATED. 완전 동일(no-op) PUT은 발행 없음.
    public void recordPlanUpdated(Plan previous, Plan updated, String sessionId) {
        if (!previous.isConfirmed() && updated.isConfirmed()) {
            append(updated.id(), "PLAN_CONFIRMED", null, sessionId);
        }

        Map<String, Map<String, TaskView>> prevTasks = parseTasks(previous.tasks());
        Map<String, Map<String, TaskView>> nextTasks = parseTasks(updated.tasks());

        // 양쪽에 모두 존재하는 (날짜, 항목)의 completed 플립만 완료/해제로 센다 —
        // 항목의 추가·삭제·이동은 아래 구조 변경(PLAN_UPDATED)이 담당한다.
        for (Map.Entry<String, Map<String, TaskView>> dayEntry : nextTasks.entrySet()) {
            Map<String, TaskView> prevDay = prevTasks.get(dayEntry.getKey());
            if (prevDay == null) continue;
            for (Map.Entry<String, TaskView> taskEntry : dayEntry.getValue().entrySet()) {
                TaskView before = prevDay.get(taskEntry.getKey());
                TaskView after = taskEntry.getValue();
                if (before == null || before.completed() == after.completed()) continue;
                String type = after.completed() ? "TASK_COMPLETED" : "TASK_REOPENED";
                append(updated.id(), type, "\"" + after.content() + "\" · " + dayEntry.getKey(), sessionId);
            }
        }

        if (hasStructuralChange(previous, updated, prevTasks, nextTasks)) {
            String detail = detectCarryOver(previous, updated, prevTasks, nextTasks);
            append(updated.id(), "PLAN_UPDATED", detail != null ? detail : GENERIC_UPDATE_DETAIL, sessionId);
        }
    }

    public List<AuditEventResponse> getEvents(long planId) {
        return auditEventRepository.findAllByPlanId(planId).stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    private void append(long planId, String type, String detail, String sessionId) {
        auditEventRepository.append(new AuditEvent(
                0, planId, type, detail, sanitizeSessionId(sessionId), Instant.now().toString()));
    }

    private static String sanitizeSessionId(String sessionId) {
        if (sessionId == null) return null;
        String trimmed = sessionId.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_SESSION_ID_LENGTH ? trimmed.substring(0, MAX_SESSION_ID_LENGTH) : trimmed;
    }

    // === diff 내부 표현 ===
    // tasks는 프론트 원본 그대로(opaque Map)라 방어적으로 파싱한다(ReflectionService.countTodayTasks와
    // 같은 instanceof 가드). 항목 키는 id를 우선 쓰고, id가 없으면 배열 위치(idx:n)로 대신한다.

    private record TaskView(String content, boolean completed) {}

    private static Map<String, Map<String, TaskView>> parseTasks(Map<String, Object> tasks) {
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

    // 구조 변경 여부 — completed(위에서 처리)·status/confirmedAt(고정에서 처리)·savedAt(매 PUT마다
    // 변함)을 제외한 정규화 뷰가 다른가? 스칼라 + 날짜별 (항목키 → content) 맵을 비교한다.
    private static boolean hasStructuralChange(Plan previous, Plan updated,
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

    private static Map<String, Map<String, String>> contentView(Map<String, Map<String, TaskView>> tasks) {
        Map<String, Map<String, String>> view = new HashMap<>();
        for (Map.Entry<String, Map<String, TaskView>> dayEntry : tasks.entrySet()) {
            Map<String, String> day = new HashMap<>();
            dayEntry.getValue().forEach((key, task) -> day.put(key, task.content()));
            view.put(dayEntry.getKey(), day);
        }
        return view;
    }

    // 이월 패턴 감지 — 프론트의 "미완료 내일로 이동"은 항목 ID를 보존한 채 다음 날짜로 옮기므로
    // 결정적으로 알아볼 수 있다: 한 날짜(D)에서만 제거 + 제거된 항목 전원 미완료 + D+1에 동일
    // (항목키, content)로 추가 + 그 외 날짜는 동일(endDate/duration 연장은 허용). 일치하면
    // "미완료 N건을 <D+1>로 이동" detail을, 아니면 null(일반 detail로 폴백)을 돌려준다.
    private static String detectCarryOver(Plan previous, Plan updated,
                                          Map<String, Map<String, TaskView>> prevTasks,
                                          Map<String, Map<String, TaskView>> nextTasks) {
        // 이월은 목표·시간·수준·시작일을 바꾸지 않는다(기간·종료일은 연장될 수 있어 비교에서 제외).
        if (!Objects.equals(previous.goalName(), updated.goalName())
                || !Objects.equals(previous.dailyHours(), updated.dailyHours())
                || !Objects.equals(previous.currentLevel(), updated.currentLevel())
                || !Objects.equals(previous.startDate(), updated.startDate())) {
            return null;
        }

        String removalDate = null;
        Map<String, String> removed = new HashMap<>(); // 항목키 → content
        for (String date : prevTasks.keySet()) {
            Map<String, TaskView> prevDay = prevTasks.get(date);
            Map<String, TaskView> nextDay = nextTasks.getOrDefault(date, Map.of());
            for (Map.Entry<String, TaskView> entry : prevDay.entrySet()) {
                if (nextDay.containsKey(entry.getKey())) continue;
                if (entry.getValue().completed()) return null; // 완료 항목이 사라짐 → 이월 아님
                if (removalDate != null && !removalDate.equals(date)) return null; // 두 날짜 이상에서 제거
                removalDate = date;
                removed.put(entry.getKey(), entry.getValue().content());
            }
        }
        if (removalDate == null) return null;

        String targetDate;
        try {
            targetDate = LocalDate.parse(removalDate).plusDays(1).toString();
        } catch (DateTimeParseException e) {
            return null; // 날짜 키가 YYYY-MM-DD가 아니면 판단 포기
        }

        Map<String, String> added = new HashMap<>();
        for (String date : nextTasks.keySet()) {
            Map<String, TaskView> nextDay = nextTasks.get(date);
            Map<String, TaskView> prevDay = prevTasks.getOrDefault(date, Map.of());
            for (Map.Entry<String, TaskView> entry : nextDay.entrySet()) {
                if (prevDay.containsKey(entry.getKey())) continue;
                if (!targetDate.equals(date)) return null; // 목적지(D+1) 밖에 추가됨 → 이월 아님
                added.put(entry.getKey(), entry.getValue().content());
            }
        }
        if (!removed.equals(added)) return null;

        // 이동분을 제외한 나머지 내용이 완전히 같아야 한다(다른 수정이 섞였으면 일반 수정으로).
        Map<String, Map<String, String>> prevView = contentView(prevTasks);
        Map<String, Map<String, String>> nextView = contentView(nextTasks);
        removed.keySet().forEach(prevView.getOrDefault(removalDate, Map.of())::remove);
        added.keySet().forEach(nextView.getOrDefault(targetDate, Map.of())::remove);
        prevView.values().removeIf(Map::isEmpty);
        nextView.values().removeIf(Map::isEmpty);
        if (!prevView.equals(nextView)) return null;

        return "미완료 " + removed.size() + "건을 " + targetDate + "로 이동";
    }
}
