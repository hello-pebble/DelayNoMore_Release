package com.delaynomore.backend.domain.plan.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 날짜 산출·검증 순수 함수 단위 테스트 — startDate/duration 산출과 endDate 검증이 공유하는 헬퍼.
class PlanDatesTest {

    private static Map<String, Object> day(String content) {
        return Map.of("id", "t-1", "content", content, "completed", false);
    }

    @Test
    void isIsoDate_엄격한YYYYMMDD만_참() {
        assertThat(PlanDates.isIsoDate("2026-07-16")).isTrue();
        assertThat(PlanDates.isIsoDate("2026-7-1")).isFalse();  // 비패딩 거부
        assertThat(PlanDates.isIsoDate("Day 1")).isFalse();
        assertThat(PlanDates.isIsoDate(null)).isFalse();
    }

    @Test
    void spanDays_종료일에서시작일까지_포함일수() {
        assertThat(PlanDates.spanDays("2026-07-16", "2026-07-18")).isEqualTo(3);
        assertThat(PlanDates.spanDays("2026-07-16", "2026-07-16")).isEqualTo(1); // 하루짜리
    }

    @Test
    void spanDays_역전또는null_최소1로클램프() {
        assertThat(PlanDates.spanDays("2026-07-18", "2026-07-16")).isEqualTo(1); // end < start
        assertThat(PlanDates.spanDays(null, "2026-07-18")).isEqualTo(1);
        assertThat(PlanDates.spanDays("2026-07-16", null)).isEqualTo(1);
        assertThat(PlanDates.spanDays("2026-07-16", "2026-7-1")).isEqualTo(1); // 비ISO
    }

    @Test
    void minMaxTaskKey_ISO이면서List인키만_경계반환() {
        Map<String, Object> tasks = Map.of(
                "2026-07-18", List.of(day("총정리")),
                "2026-07-16", List.of(day("단어 암기")),
                "2026-07-17", List.of(day("듣기 연습")));

        assertThat(PlanDates.minTaskKey(tasks)).isEqualTo("2026-07-16");
        assertThat(PlanDates.maxTaskKey(tasks)).isEqualTo("2026-07-18");
    }

    @Test
    void minMaxTaskKey_비ISO또는비List키_방어적으로건너뜀() {
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(day("단어 암기")),
                "Day 2", List.of(day("무시")),   // 비ISO 키
                "2026-07-20", "이상한 값");         // 비List 값

        // 유효한(ISO + List) 키만 경계 후보 — 07-16 하나
        assertThat(PlanDates.minTaskKey(tasks)).isEqualTo("2026-07-16");
        assertThat(PlanDates.maxTaskKey(tasks)).isEqualTo("2026-07-16");
    }

    @Test
    void minMaxTaskKey_유효키없거나null_null반환() {
        assertThat(PlanDates.minTaskKey(null)).isNull();
        assertThat(PlanDates.maxTaskKey(null)).isNull();
        assertThat(PlanDates.minTaskKey(Map.of("Day 1", List.of(day("무시"))))).isNull();
    }
}
