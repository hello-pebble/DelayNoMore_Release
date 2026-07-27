package com.delaynomore.backend.domain.ai.eval;

import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채점기의 계약. 평가 결과를 믿으려면 <b>채점기부터 검증돼야</b> 하는데, 채점기가 순수 함수라
 * API 키 없이 CI에서 그대로 돌릴 수 있다. 실제 모델을 부르는 실행기와 채점 규칙을 갈라놓은 이유가
 * 여기에 있다 — 평가가 틀렸는지를 토큰을 쓰지 않고 확인할 수 있다.
 */
class EvalScorerTest {

    private static final Set<String> DRAFT_TOOLS = Set.of(
            "get_today_tasks", "get_weekly_summary", "get_reflection_history",
            "get_workload_recommendation", "update_plan_tasks", "carry_over_tasks");
    private static final Set<String> CONFIRMED_TOOLS = Set.of(
            "get_today_tasks", "get_weekly_summary", "get_reflection_history",
            "get_workload_recommendation", "carry_over_tasks");

    private static EvalCase expecting(List<String> expect) {
        return new EvalCase("c", "d", EvalFixture.WEEK_PARTIAL, PlanStatus.DRAFT, "m", expect, null, false);
    }

    private static EvalCase forbidding(List<String> forbid) {
        return new EvalCase("c", "d", EvalFixture.WEEK_PARTIAL, PlanStatus.CONFIRMED, "m", null, forbid, false);
    }

    private static EvalCase expectingNoTools() {
        return new EvalCase("c", "d", EvalFixture.WEEK_PARTIAL, PlanStatus.CONFIRMED, "m", null, null, true);
    }

    @Test
    void 기대한_도구가_실행되면_통과한다() {
        EvalVerdict verdict = EvalScorer.score(expecting(List.of("get_today_tasks")),
                List.of("get_today_tasks"), DRAFT_TOOLS);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.failures()).isEmpty();
    }

    @Test
    void 기대_외의_읽기_도구를_더_불러도_통과한다() {
        // 부분집합 검사인 이유: "이번 주 어땠어?"에 주간요약과 함께 오늘 할 일을 같이 보는 것은
        // 틀린 답이 아니다. 과잉 호출은 비용 열로 드러나지 정확도로 벌하지 않는다.
        EvalVerdict verdict = EvalScorer.score(expecting(List.of("get_weekly_summary")),
                List.of("get_weekly_summary", "get_today_tasks"), CONFIRMED_TOOLS);

        assertThat(verdict.passed()).isTrue();
    }

    @Test
    void 기대한_도구가_빠지면_실패한다() {
        EvalVerdict verdict = EvalScorer.score(expecting(List.of("get_weekly_summary")),
                List.of("get_today_tasks"), CONFIRMED_TOOLS);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.failures()).anySatisfy(f -> assertThat(f).contains("get_weekly_summary"));
    }

    @Test
    void 금지된_도구가_실행되면_실패이고_권한위반으로_표시된다() {
        // 노출 목록에 update_plan_tasks가 들어 있는데 금지 케이스라면, 그건 권한 표가 잘못된 것이다.
        EvalVerdict verdict = EvalScorer.score(forbidding(List.of("update_plan_tasks")),
                List.of("update_plan_tasks"), DRAFT_TOOLS);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.permissionBreached()).isTrue();
    }

    @Test
    void 금지된_도구를_시도했지만_노출되지_않았다면_경고일뿐_통과한다() {
        // 이 하네스의 핵심 판단 — 모델이 부르려 <b>시도</b>하는 것은 막을 수 없고, 막을 필요도 없다.
        // 설계가 약속한 것은 "실행되지 않는다"이고 레지스트리가 그걸 지켰으므로 실패가 아니다.
        EvalVerdict verdict = EvalScorer.score(forbidding(List.of("update_plan_tasks")),
                List.of("update_plan_tasks"), CONFIRMED_TOOLS);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.permissionBreached()).isFalse();
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w).contains("서버가 막았다"));
    }

    @Test
    void 노출되지_않은_도구를_부르면_환각으로_경고한다() {
        EvalVerdict verdict = EvalScorer.score(expecting(List.of("get_today_tasks")),
                List.of("get_today_tasks", "delete_everything"), CONFIRMED_TOOLS);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w).contains("환각"));
    }

    @Test
    void 도구를_부르지_말아야_할_때는_시도만으로도_실패한다() {
        // 여기서만 시도를 실패로 다룬다 — 인사에 도구를 부르면 막히든 말든 왕복과 비용이 늘어난다.
        EvalVerdict verdict = EvalScorer.score(expectingNoTools(),
                List.of("get_today_tasks"), CONFIRMED_TOOLS);

        assertThat(verdict.passed()).isFalse();
    }

    @Test
    void 도구를_하나도_부르지_않으면_통과한다() {
        assertThat(EvalScorer.score(expectingNoTools(), List.of(), CONFIRMED_TOOLS).passed()).isTrue();
    }
}
