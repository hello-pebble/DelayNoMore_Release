package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlackMessageComposerTest {

    private final SlackMessageComposer composer = new SlackMessageComposer();

    @Test
    void 작업번호는_계획id_오름차순_작업원순서의_전역번호다() {
        // 대시보드가 계획을 id 역순으로 내려줘도 번호는 id 오름차순으로 재현돼야 한다 —
        // 번호의 소스오브트루스는 정렬 규칙(멘션 해석이 같은 규칙으로 번호를 되짚는다).
        TodayDashboardResponse dashboard = new TodayDashboardResponse("2026-09-11", 1, 3, List.of(
                planItem(2L, "영어 회화", List.of(task("t3", "쉐도잉 20분", false))),
                planItem(1L, "정보처리기사", List.of(
                        task("t1", "기출 1회 풀기", false),
                        task("t2", "오답 정리", true)))));

        String text = composer.composeChecklist(dashboard);

        assertThat(text).contains("9월 11일");
        assertThat(text).contains("1/3 완료");
        int first = text.indexOf("1. ⬜ 기출 1회 풀기");
        int second = text.indexOf("2. ✅ 오답 정리");
        int third = text.indexOf("3. ⬜ 쉐도잉 20분");
        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
        assertThat(text.indexOf("*정보처리기사*")).isLessThan(text.indexOf("*영어 회화*"));
    }

    @Test
    void 같은_대시보드는_항상_같은_번호를_만든다() {
        TodayDashboardResponse dashboard = new TodayDashboardResponse("2026-09-11", 0, 2, List.of(
                planItem(5L, "목표", List.of(task("a", "할 일 1", false), task("b", "할 일 2", false)))));

        assertThat(composer.composeChecklist(dashboard))
                .isEqualTo(composer.composeChecklist(dashboard));
    }

    private static TodayDashboardResponse.PlanItem planItem(long id, String goalName, List<Object> tasks) {
        PlanResponse plan = new PlanResponse(id, goalName, null, null, null, null, "CONFIRMED",
                null, null, null, null, null, 0L, new PlanResponse.Progress(0, tasks.size()), false);
        int done = (int) tasks.stream()
                .filter(t -> Boolean.TRUE.equals(((Map<?, ?>) t).get("completed"))).count();
        return new TodayDashboardResponse.PlanItem(plan, tasks, done, tasks.size(), null, false);
    }

    private static Map<String, Object> task(String id, String content, boolean completed) {
        return Map.of("id", id, "content", content, "completed", completed);
    }
}
