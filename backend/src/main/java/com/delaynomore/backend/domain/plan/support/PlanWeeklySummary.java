package com.delaynomore.backend.domain.plan.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 계획을 startDate 기준 7일 버킷("N주차")으로 슬라이스하는 단일 소유처.
 * 계획이 짧고(초안 1~14일, 이월 시 최대 365일) startDate가 계획 생성 시 산출된 뒤 불변이라,
 * ISO 캘린더 주(월~일)로 쪼개 경계에 부분 주가 생기는 것보다 계획 상대 주가 자연스럽다.
 * 1주차 = [startDate, startDate+6], 2주차 = [startDate+7, startDate+13] … 마지막 주는 endDate에서
 * 잘린 부분 주. 완료 개수 자체는 세지 않는다 — 버킷 경계만 내주고, 개수는 Plan.countTasksBetween이
 * 소유한다("날짜/개수 계산은 도메인 소유" 관례 유지).
 */
public final class PlanWeeklySummary {

    private static final int DAYS_PER_WEEK = 7;

    private PlanWeeklySummary() {
    }

    // 주차 번호(1부터)와 [from, to](양끝 포함, YYYY-MM-DD) 경계. Plan.countTasksBetween의 인자로 쓴다.
    public record WeekBucket(int index, String from, String to) {
    }

    // startDate/endDate가 null·비ISO거나 범위가 역전(end < start)이면 빈 리스트 — 방어적으로 다뤄
    // 호출부(WeeklySummaryResponse.from)가 그대로 빈 weeks를 내려준다.
    public static List<WeekBucket> buckets(String startDate, String endDate) {
        if (!PlanDates.isIsoDate(startDate) || !PlanDates.isIsoDate(endDate)) {
            return List.of();
        }
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        if (end.isBefore(start)) {
            return List.of();
        }
        List<WeekBucket> result = new ArrayList<>();
        LocalDate cursor = start;
        int index = 1;
        while (!cursor.isAfter(end)) {
            LocalDate bucketEnd = cursor.plusDays(DAYS_PER_WEEK - 1L);
            if (bucketEnd.isAfter(end)) {
                bucketEnd = end;
            }
            result.add(new WeekBucket(index, cursor.toString(), bucketEnd.toString()));
            cursor = cursor.plusDays(DAYS_PER_WEEK);
            index++;
        }
        return result;
    }
}
