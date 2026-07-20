package com.delaynomore.backend.domain.plan.support;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 계획 날짜(startDate/duration 산출·endDate 검증)의 단일 소유처.
 * 예전엔 프론트(ai_engine.js의 getFormattedDate/브라우저 로컬 날짜)가 계산해 서버로 그대로
 * 통과시켰지만, 이제 서버가 tasks 날짜 키에서 startDate(최초 키)·duration(기간 span)을 산출하고
 * endDate 형식·범위를 검증한다. carry-over는 Plan 인스턴스 조립 '전에' 필드를 만들어야 해서
 * 인스턴스 메서드보다 static 유틸이 재사용하기 좋다(엔티티 countAllTasks의 "날짜/개수 계산은
 * 도메인 소유" 관례는 유지 — 위치만 support 유틸).
 */
public final class PlanDates {

    private PlanDates() {
    }

    // 엄격한 YYYY-MM-DD 판정 — LocalDate.parse가 받는 형식만 참("2026-7-1" 거부). tasks 키 검증
    // (PlanTasksValidator)과 endDate 검증(PlanDatesValidator)이 같은 파서를 공유하도록 여기로 모았다.
    public static boolean isIsoDate(String key) {
        if (key == null) {
            return false;
        }
        try {
            LocalDate.parse(key);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // tasks의 가장 이른 날짜 키 — startDate 산출용. 없으면 null.
    public static String minTaskKey(Map<String, Object> tasks) {
        return boundaryKey(tasks, true);
    }

    // tasks의 가장 늦은 날짜 키 — endDate 하한(포함) 검증용. 없으면 null.
    public static String maxTaskKey(Map<String, Object> tasks) {
        return boundaryKey(tasks, false);
    }

    // YYYY-MM-DD는 사전식 정렬이 곧 시간순이라 문자열 비교로 충분하다. ISO 형식이면서 값이 List인
    // 키만 본다 — 클래스 레벨 검증(PlanDatesValidator)은 @ValidPlanTasks 통과 여부와 무관하게
    // 실행될 수 있어(제약 실행 순서 미보장), 방어적으로 걸러야 NPE/오판이 없다.
    private static String boundaryKey(Map<String, Object> tasks, boolean min) {
        if (tasks == null) {
            return null;
        }
        String boundary = null;
        for (Map.Entry<String, Object> entry : tasks.entrySet()) {
            String key = entry.getKey();
            if (!isIsoDate(key) || !(entry.getValue() instanceof List<?>)) {
                continue;
            }
            if (boundary == null
                    || (min ? key.compareTo(boundary) < 0 : key.compareTo(boundary) > 0)) {
                boundary = key;
            }
        }
        return boundary;
    }

    // 기간(일수) = 종료일 - 시작일 + 1. 프론트가 보내던 duration 대신 서버가 범위로 산출한다.
    // null·비ISO·역전(end < start) 범위는 최소 1일로 클램프한다(계획은 항상 하루 이상).
    public static int spanDays(String startDate, String endDate) {
        if (!isIsoDate(startDate) || !isIsoDate(endDate)) {
            return 1;
        }
        long span = ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate)) + 1;
        return span < 1 ? 1 : (int) span;
    }
}
