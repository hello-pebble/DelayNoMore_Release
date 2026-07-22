package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.Reflection;
import com.delaynomore.backend.domain.plan.support.WorkloadRecommendation.Recommendation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 다음 계획 분량 추천 규칙 — 서버가 소유하는 결정 로직. 규칙 표 전 분기 + ±1/[1,5] 클램프 +
// 미래 날짜 제외를 검증한다(AI 무관, 순수 함수).
class WorkloadRecommendationTest {

    private static final String START = "2026-07-01";

    @Test
    void 관찰_3일미만이면_추천없이_기존분량유지() {
        // 2일치 계획(둘 다 오늘 이하) → insufficientHistory, delta 0.
        Plan plan = plan(START, 2, 3, 1);
        LocalDate today = LocalDate.parse("2026-07-02");

        Recommendation rec = WorkloadRecommendation.compute(plan, List.of(), today);

        assertThat(rec.insufficientHistory()).isTrue();
        assertThat(rec.observedDays()).isEqualTo(2);
        assertThat(rec.delta()).isZero();
        assertThat(rec.recommendedTasksPerDay()).isEqualTo(rec.currentTasksPerDay());
    }

    @Test
    void 완료율_50미만이면_하루1개감소() {
        // 5일 × 3개, 하루 1개만 완료 → 33% → -1.
        Plan plan = plan(START, 5, 3, 1);
        LocalDate today = LocalDate.parse("2026-07-05");

        Recommendation rec = WorkloadRecommendation.compute(plan, List.of(), today);

        assertThat(rec.currentTasksPerDay()).isEqualTo(3);
        assertThat(rec.completionRate()).isLessThan(50);
        assertThat(rec.delta()).isEqualTo(-1);
        assertThat(rec.recommendedTasksPerDay()).isEqualTo(2);
    }

    @Test
    void 벅참절반이상_분량많음반복이면_중간완료율에도_감소() {
        // 5일 × 3개, 하루 2개 완료 → 66%(중립 구간)지만 HARD 다수 + 분량많음 반복 → -1.
        Plan plan = plan(START, 5, 3, 2);
        LocalDate today = LocalDate.parse("2026-07-05");
        List<Reflection> reflections = List.of(
                refl("HARD", "TOO_MUCH_WORK"),
                refl("HARD", "TOO_MUCH_WORK"),
                refl("NORMAL", "AS_PLANNED"));

        Recommendation rec = WorkloadRecommendation.compute(plan, reflections, today);

        assertThat(rec.completionRate()).isBetween(50, 84);
        assertThat(rec.hardCount()).isEqualTo(2);
        assertThat(rec.delta()).isEqualTo(-1);
        assertThat(rec.recommendedTasksPerDay()).isEqualTo(2);
        assertThat(rec.topReasonCode()).isEqualTo("TOO_MUCH_WORK");
    }

    @Test
    void 중간완료율_강한신호없으면_현재분량유지() {
        // 66%, 회고 없음 → delta 0.
        Plan plan = plan(START, 5, 3, 2);
        LocalDate today = LocalDate.parse("2026-07-05");

        Recommendation rec = WorkloadRecommendation.compute(plan, List.of(), today);

        assertThat(rec.completionRate()).isBetween(50, 84);
        assertThat(rec.delta()).isZero();
        assertThat(rec.recommendedTasksPerDay()).isEqualTo(3);
    }

    @Test
    void 높은완료율_여유회고다수면_하루1개증가() {
        // 4일 × 2개 전부 완료 → 100%, EASY 다수 → +1.
        Plan plan = plan(START, 4, 2, 2);
        LocalDate today = LocalDate.parse("2026-07-04");
        List<Reflection> reflections = List.of(
                refl("EASY", "AS_PLANNED"),
                refl("EASY", "AS_PLANNED"),
                refl("NORMAL", "AS_PLANNED"));

        Recommendation rec = WorkloadRecommendation.compute(plan, reflections, today);

        assertThat(rec.completionRate()).isEqualTo(100);
        assertThat(rec.currentTasksPerDay()).isEqualTo(2);
        assertThat(rec.delta()).isEqualTo(1);
        assertThat(rec.recommendedTasksPerDay()).isEqualTo(3);
    }

    @Test
    void 최소1개_아래로는_감소하지않음() {
        // 5일 × 1개, 완료 0 → 0% → -1 시도지만 [1,5] 클램프로 1 유지, effectiveDelta 0.
        Plan plan = plan(START, 5, 1, 0);
        LocalDate today = LocalDate.parse("2026-07-05");

        Recommendation rec = WorkloadRecommendation.compute(plan, List.of(), today);

        assertThat(rec.currentTasksPerDay()).isEqualTo(1);
        assertThat(rec.recommendedTasksPerDay()).isEqualTo(1);
        assertThat(rec.delta()).isZero();
    }

    @Test
    void 최대5개_위로는_증가하지않음() {
        // 4일 × 5개 전부 완료 → 100%, EASY 다수 → +1 시도지만 [1,5] 클램프로 5 유지.
        Plan plan = plan(START, 4, 5, 5);
        LocalDate today = LocalDate.parse("2026-07-04");
        List<Reflection> reflections = List.of(refl("EASY", "AS_PLANNED"), refl("EASY", "AS_PLANNED"));

        Recommendation rec = WorkloadRecommendation.compute(plan, reflections, today);

        assertThat(rec.currentTasksPerDay()).isEqualTo(5);
        assertThat(rec.recommendedTasksPerDay()).isEqualTo(5);
        assertThat(rec.delta()).isZero();
    }

    @Test
    void 미래일정은_완료율과_관찰일수에서_제외() {
        // 5일 계획인데 오늘은 3일차 — 4·5일차(미래)는 완료율·관찰일수에서 빠진다.
        LinkedHashMap<String, Object> tasks = new LinkedHashMap<>();
        LocalDate start = LocalDate.parse(START);
        // 1~3일차: 3개 중 3개 완료(과거). 4~5일차: 3개 중 0개(미래).
        for (int i = 0; i < 3; i++) {
            tasks.put(start.plusDays(i).toString(), dayTasks(3, 3));
        }
        for (int i = 3; i < 5; i++) {
            tasks.put(start.plusDays(i).toString(), dayTasks(3, 0));
        }
        Plan plan = new Plan(1L, "owner", "목표", 5, 2, "수준", tasks, "CONFIRMED", null,
                START, start.plusDays(4).toString(), null, 0L);
        LocalDate today = start.plusDays(2); // 3일차

        Recommendation rec = WorkloadRecommendation.compute(plan, List.of(), today);

        assertThat(rec.observedDays()).isEqualTo(3);        // 미래 2일 제외
        assertThat(rec.totalCount()).isEqualTo(9);          // 3일 × 3개만
        assertThat(rec.completionRate()).isEqualTo(100);    // 과거 전부 완료 — 미래 미완료가 끌어내리지 않음
    }

    @Test
    void 버튼노출_완료했거나_3일이상실행() {
        Plan confirmedDone = plan(START, 2, 2, 2);          // 고정 + 전부 완료(2일) → 완료로 노출
        Plan runningThree = plan(START, 3, 2, 0);           // 3일 실행 → 노출
        Plan shortDraft = draft(START, 2, 2, 0);            // 2일 미완료 초안 → 미노출
        LocalDate today = LocalDate.parse("2026-07-05");    // 모든 날짜가 과거

        assertThat(WorkloadRecommendation.isEligible(confirmedDone, today, confirmedDone.countAllTasks())).isTrue();
        assertThat(WorkloadRecommendation.isEligible(runningThree, today, runningThree.countAllTasks())).isTrue();
        assertThat(WorkloadRecommendation.isEligible(shortDraft, today, shortDraft.countAllTasks())).isFalse();
    }

    // === 헬퍼 ===

    private static Plan plan(String startDate, int days, int tasksPerDay, int completedPerDay) {
        return buildPlan(startDate, days, tasksPerDay, completedPerDay, "CONFIRMED");
    }

    private static Plan draft(String startDate, int days, int tasksPerDay, int completedPerDay) {
        return buildPlan(startDate, days, tasksPerDay, completedPerDay, "DRAFT");
    }

    private static Plan buildPlan(String startDate, int days, int tasksPerDay, int completedPerDay, String status) {
        LinkedHashMap<String, Object> tasks = new LinkedHashMap<>();
        LocalDate start = LocalDate.parse(startDate);
        for (int i = 0; i < days; i++) {
            tasks.put(start.plusDays(i).toString(), dayTasks(tasksPerDay, completedPerDay));
        }
        String end = start.plusDays(days - 1).toString();
        return new Plan(1L, "owner", "목표", days, 2, "수준", tasks, status, null, startDate, end, null, 0L);
    }

    private static List<Map<String, Object>> dayTasks(int total, int completed) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            list.add(Map.of("id", "t" + i, "content", "할 일 " + i, "completed", i < completed));
        }
        return list;
    }

    private static Reflection refl(String difficulty, String reason) {
        return new Reflection(1L, "2026-07-01", 0, 0, difficulty, reason, null, null);
    }
}
