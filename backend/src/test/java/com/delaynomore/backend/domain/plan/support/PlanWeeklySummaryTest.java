package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.domain.plan.support.PlanWeeklySummary.WeekBucket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 계획 상대 7일 버킷("N주차") 슬라이스 단위 테스트 — 경계·부분 주·방어 케이스.
class PlanWeeklySummaryTest {

    @Test
    void buckets_하루짜리_단일주차() {
        List<WeekBucket> buckets = PlanWeeklySummary.buckets("2026-07-16", "2026-07-16");

        assertThat(buckets).containsExactly(new WeekBucket(1, "2026-07-16", "2026-07-16"));
    }

    @Test
    void buckets_정확히7일_단일주차() {
        List<WeekBucket> buckets = PlanWeeklySummary.buckets("2026-07-16", "2026-07-22");

        assertThat(buckets).containsExactly(new WeekBucket(1, "2026-07-16", "2026-07-22"));
    }

    @Test
    void buckets_8일_2주차_둘째주는하루() {
        List<WeekBucket> buckets = PlanWeeklySummary.buckets("2026-07-16", "2026-07-23");

        assertThat(buckets).containsExactly(
                new WeekBucket(1, "2026-07-16", "2026-07-22"),
                new WeekBucket(2, "2026-07-23", "2026-07-23"));
    }

    @Test
    void buckets_정확히14일_2주차_각7일() {
        List<WeekBucket> buckets = PlanWeeklySummary.buckets("2026-07-16", "2026-07-29");

        assertThat(buckets).containsExactly(
                new WeekBucket(1, "2026-07-16", "2026-07-22"),
                new WeekBucket(2, "2026-07-23", "2026-07-29"));
    }

    @Test
    void buckets_10일_둘째주는_endDate에서잘린부분주() {
        List<WeekBucket> buckets = PlanWeeklySummary.buckets("2026-07-16", "2026-07-25");

        assertThat(buckets).containsExactly(
                new WeekBucket(1, "2026-07-16", "2026-07-22"),
                new WeekBucket(2, "2026-07-23", "2026-07-25")); // 3일 부분 주
    }

    @Test
    void buckets_월경계_LocalDate로정확히계산() {
        // 07-30 시작, 8일 → 2주차가 8월로 넘어간다(문자열 +7일이 아니라 실제 날짜 연산인지 확인)
        List<WeekBucket> buckets = PlanWeeklySummary.buckets("2026-07-30", "2026-08-06");

        assertThat(buckets).containsExactly(
                new WeekBucket(1, "2026-07-30", "2026-08-05"),
                new WeekBucket(2, "2026-08-06", "2026-08-06"));
    }

    @Test
    void buckets_null또는비ISO_빈리스트() {
        assertThat(PlanWeeklySummary.buckets(null, "2026-07-22")).isEmpty();
        assertThat(PlanWeeklySummary.buckets("2026-07-16", null)).isEmpty();
        assertThat(PlanWeeklySummary.buckets("2026-7-1", "2026-07-22")).isEmpty(); // 비패딩
    }

    @Test
    void buckets_범위역전_빈리스트() {
        assertThat(PlanWeeklySummary.buckets("2026-07-22", "2026-07-16")).isEmpty();
    }
}
