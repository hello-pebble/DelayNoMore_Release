package com.delaynomore.backend.domain.ai.eval;

import com.delaynomore.backend.domain.ai.usage.TokenUsage;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리포트 렌더링 테스트. 실제 모델이 필요 없어 {@code ./gradlew test}(=CI)에서 돈다 —
 * "표가 제대로 나오는가"를 토큰을 쓰지 않고 확인할 수 있다는 뜻이다.
 *
 * <p>리포트는 사람이 읽고 릴리스 사이에 diff하는 산출물이라, 숫자 표기가 어긋나면 그대로
 * 기록에 남는다(첫 실측 리포트에 {@code $0.04795999999999999}가 찍힌 것이 그 예다).
 */
class EvalReportTest {

    @Test
    @DisplayName("비용은 부동소수점 원값이 아니라 유효숫자 4자리로 찍힌다")
    void 비용은_유효숫자로_찍는다() {
        // 0.0159 + 0.0159 + 0.0159 처럼 여러 턴의 cost를 더하면 double 오차가 누적된다
        String rendered = render(usage(1000, 100, 0.0159), usage(1000, 100, 0.0159), usage(1000, 100, 0.0159));

        assertThat(rendered).contains("비용 $0.0477");
        assertThat(rendered).doesNotContain("0.047699999");
    }

    @Test
    @DisplayName("총액이 작아도 유효숫자가 남는다 — 자릿수 고정이 아니기 때문이다")
    void 총액이_작아도_유효숫자가_남는다() {
        String rendered = render(usage(300, 20, 0.00008123));

        // %.5f로 고정했다면 $0.00008이 되어 유효숫자가 한 자리만 남았을 값이다
        assertThat(rendered).contains("비용 $0.00008123");
    }

    @Test
    @DisplayName("cost가 없는 응답이면 비용 항목을 아예 빼고 찍는다")
    void cost가_없으면_비용을_찍지_않는다() {
        String rendered = render(usage(1000, 100, null));

        assertThat(rendered).doesNotContain("비용");
        assertThat(rendered).contains("토큰 1100");
    }

    @Test
    @DisplayName("반복 실행의 통과 비율과 관측된 도구 조합이 모두 표에 남는다")
    void 반복_실행은_비율과_조합을_함께_보여준다() {
        EvalCase testCase = new EvalCase("notool.thanks", "감사 인사", EvalFixture.WEEK_PARTIAL,
                PlanStatus.DRAFT, "고마워!", List.of(), List.of(), true);
        EvalRunResult pass = new EvalRunResult(testCase, 1,
                new EvalVerdict(testCase.id(), true, List.of(), List.of(), false),
                List.of(), usage(1000, 100, null), 1, null);
        EvalRunResult fail = new EvalRunResult(testCase, 2,
                new EvalVerdict(testCase.id(), false, List.of("도구를 부르지 않아야 하는데 호출했다"), List.of(), false),
                List.of("get_today_tasks"), usage(2000, 120, null), 2, null);

        String rendered = new EvalReport("agent-tool-selection", "test-model", 2, List.of(pass, fail)).render();

        assertThat(rendered).contains("❌ 1/2");
        // 흔들림 자체가 신호이므로 관측된 조합을 모두 보여준다
        assertThat(rendered).contains("(없음) / get_today_tasks");
        assertThat(rendered).contains("**통과율 50%**");
        assertThat(rendered).contains("업스트림 왕복 3회");
        assertThat(rendered).contains("## 실패");
    }

    @Test
    @DisplayName("실행 오류는 판정 실패와 구분해 사유를 적는다")
    void 실행_오류는_사유를_적는다() {
        EvalCase testCase = anyCase();
        EvalRunResult errored = new EvalRunResult(testCase, 1, null, List.of(), TokenUsage.EMPTY, 0,
                "HttpServerErrorException: 502");

        String rendered = new EvalReport("agent-tool-selection", "test-model", 1, List.of(errored)).render();

        assertThat(rendered).contains("실행 오류: HttpServerErrorException: 502");
        assertThat(rendered).contains("**통과율 0%**");
    }

    private static String render(TokenUsage... usages) {
        EvalCase testCase = anyCase();
        List<EvalRunResult> results = new java.util.ArrayList<>();
        int repeat = 1;
        for (TokenUsage usage : usages) {
            results.add(new EvalRunResult(testCase, repeat++,
                    new EvalVerdict(testCase.id(), true, List.of(), List.of(), false),
                    List.of("get_today_tasks"), usage, 1, null));
        }
        return new EvalReport("agent-tool-selection", "test-model", results.size(), results).render();
    }

    private static EvalCase anyCase() {
        return new EvalCase("read.today.draft", "오늘 할 일 조회", EvalFixture.WEEK_PARTIAL,
                PlanStatus.DRAFT, "오늘 뭐 해야 해?", List.of("get_today_tasks"), List.of(), false);
    }

    private static TokenUsage usage(int prompt, int completion, Double cost) {
        return new TokenUsage(prompt, completion, prompt + completion, cost);
    }
}
