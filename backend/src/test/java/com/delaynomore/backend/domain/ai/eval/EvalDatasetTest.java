package com.delaynomore.backend.domain.ai.eval;

import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.AgentToolRegistry;
import com.delaynomore.backend.domain.ai.agent.tools.CarryOverTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetReflectionHistoryTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetTodayTasksTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetWeeklySummaryTool;
import com.delaynomore.backend.domain.ai.agent.tools.GetWorkloadRecommendationTool;
import com.delaynomore.backend.domain.ai.agent.tools.UpdatePlanTasksTool;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 데이터셋 무결성 — 토큰을 한 개도 쓰지 않고 CI에서 돈다.
 *
 * <p>평가 데이터셋은 조용히 썩는다. 도구 이름을 바꾸거나 케이스를 복사-붙여넣기 하면서 id가
 * 겹치면, 실행기는 아무 불평 없이 <b>영원히 통과하지 못할 케이스</b>나 <b>덮어써진 결과</b>를
 * 만들어낸다. 그런 오류는 실제 모델을 부른 뒤에야 드러나므로 비싸다 — 여기서 미리 잡는다.
 */
class EvalDatasetTest {

    private static final Set<String> REAL_TOOL_NAMES = realToolNames();

    private static Set<String> realToolNames() {
        // 레지스트리를 실제로 조립해 이름을 얻는다 — 목록을 여기 다시 적으면 그 목록이 또 썩는다.
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                new GetTodayTasksTool(mock()), new GetWeeklySummaryTool(mock()),
                new GetReflectionHistoryTool(mock()), new GetWorkloadRecommendationTool(mock()),
                new UpdatePlanTasksTool(), new CarryOverTool(mock())));
        return Set.copyOf(registry.toolsFor(PlanStatus.DRAFT).stream().map(AgentTool::name).toList());
    }

    private final EvalDataset dataset = EvalDataset.loadDefault();

    @Test
    void 데이터셋이_읽히고_케이스가_있다() {
        assertThat(dataset.name()).isNotBlank();
        assertThat(dataset.cases()).isNotEmpty();
    }

    @Test
    void 케이스_id가_유일하다() {
        // 중복 id는 리포트에서 케이스가 합쳐져 결과가 조용히 뭉개진다(EvalReport가 id로 묶는다).
        assertThat(dataset.cases().stream().map(EvalCase::id).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void 모든_케이스가_필수_필드를_갖는다() {
        assertThat(dataset.cases()).allSatisfy(testCase -> {
            assertThat(testCase.id()).as("id").isNotBlank();
            assertThat(testCase.description()).as("%s.description", testCase.id()).isNotBlank();
            assertThat(testCase.message()).as("%s.message", testCase.id()).isNotBlank();
            assertThat(testCase.fixture()).as("%s.fixture", testCase.id()).isNotNull();
            assertThat(testCase.status()).as("%s.status", testCase.id()).isNotNull();
        });
    }

    @Test
    void 기대_도구_이름이_실제_도구와_일치한다() {
        // 오타 하나면 그 케이스는 영원히 실패한다 — 그리고 그걸 실제 모델을 부른 뒤에 알게 된다.
        assertThat(dataset.cases()).allSatisfy(testCase -> {
            assertThat(REAL_TOOL_NAMES).as("%s.expectTools", testCase.id())
                    .containsAll(testCase.expectTools());
            assertThat(REAL_TOOL_NAMES).as("%s.forbidTools", testCase.id())
                    .containsAll(testCase.forbidTools());
            assertThat(REAL_TOOL_NAMES).as("%s.avoidTools", testCase.id())
                    .containsAll(testCase.avoidTools());
        });
    }

    @Test
    void 케이스마다_채점할_기준이_하나는_있다() {
        // 기대도 금지도 없고 expectNoTools도 아니면 그 케이스는 무조건 통과한다 — 통과율만 부풀린다.
        assertThat(dataset.cases()).allSatisfy(testCase ->
                assertThat(testCase.expectNoTools()
                        || !testCase.expectTools().isEmpty()
                        || !testCase.forbidTools().isEmpty()
                        || !testCase.avoidTools().isEmpty())
                        .as("%s: 기대·금지·회피·expectNoTools 중 하나는 있어야 한다", testCase.id())
                        .isTrue());
    }

    @Test
    void 기대와_금지가_겹치지_않는다() {
        // 교집합으로 검사하는 이유: AssertJ의 doesNotContainAnyElementsOf는 인자가 빈 목록이면
        // 예외를 던진다. 대부분의 케이스는 forbidTools가 비어 있어 그 경로가 기본값이다.
        assertThat(dataset.cases()).allSatisfy(testCase -> {
            Set<String> overlap = new java.util.LinkedHashSet<>(testCase.expectTools());
            overlap.retainAll(testCase.forbidTools());
            assertThat(overlap).as("%s: 같은 도구를 기대하면서 금지할 수 없다", testCase.id()).isEmpty();
        });
    }

    @Test
    void 기대한_도구는_그_상태에서_실제로_노출되는_도구다() {
        // 상태 권한 표와 데이터셋이 어긋나면(예: CONFIRMED에서 update_plan_tasks를 기대) 그 케이스는
        // 모델이 아무리 잘해도 통과할 수 없다. 데이터셋의 버그를 모델 탓으로 오독하는 걸 막는다.
        AgentToolRegistry registry = registry();

        assertThat(dataset.cases()).allSatisfy(testCase -> {
            Set<String> exposed = exposedNames(registry, testCase);
            assertThat(exposed).as("%s: %s 상태에서 노출되지 않는 도구를 기대하고 있다",
                            testCase.id(), testCase.status())
                    .containsAll(testCase.expectTools());
        });
    }

    @Test
    void forbid는_그_상태에서_노출되지_않는_도구여야_한다() {
        // forbidTools의 실행은 "권한 표가 깨졌다"는 뜻이라 빌드를 깨뜨린다. 그런데 노출되는 도구를
        // forbid에 넣으면 모델이 정상적으로 그걸 쓸 때마다 빌드가 깨진다 — 설계 결함이 아닌데 사고로
        // 보고되는 것이다. 노출되지만 이 요청엔 부적절한 도구는 avoidTools 쪽이다.
        AgentToolRegistry registry = registry();

        assertThat(dataset.cases()).allSatisfy(testCase -> {
            Set<String> exposed = exposedNames(registry, testCase);
            assertThat(testCase.forbidTools())
                    .as("%s: %s에서 노출되는 도구를 forbid에 뒀다 — avoidTools로 옮겨야 한다",
                            testCase.id(), testCase.status())
                    .noneMatch(exposed::contains);
        });
    }

    @Test
    void avoid는_그_상태에서_노출되는_도구여야_한다() {
        // 반대 방향의 함정: 노출되지 않는 도구를 avoid에 넣으면 영원히 발화하지 않는다.
        // 아무것도 재지 않는 케이스가 통과율만 부풀리는 것을 막는다.
        AgentToolRegistry registry = registry();

        assertThat(dataset.cases()).allSatisfy(testCase -> {
            Set<String> exposed = exposedNames(registry, testCase);
            assertThat(exposed)
                    .as("%s: %s에서 노출되지 않는 도구를 avoid에 뒀다 — 영원히 발화하지 않는다",
                            testCase.id(), testCase.status())
                    .containsAll(testCase.avoidTools());
        });
    }

    @Test
    void only_필터는_접두사로_축을_고른다() {
        EvalDataset filtered = dataset.filter("notool,read.today");

        assertThat(filtered.cases()).extracting(EvalCase::id)
                .allSatisfy(id -> assertThat(id).matches("^(notool|read\\.today).*"))
                .contains("notool.greeting", "notool.thanks", "read.today.draft", "read.today.after_greeting");
        // 부분집합 결과가 전체 실행 리포트처럼 보이면 통과율이 오독된다 — 이름에 남긴다.
        assertThat(filtered.name()).isEqualTo(dataset.name() + " (only=notool,read.today)");
    }

    @Test
    void only_필터가_비었으면_전체를_그대로_쓴다() {
        assertThat(dataset.filter(null)).isEqualTo(dataset);
        assertThat(dataset.filter("   ")).isEqualTo(dataset);
    }

    @Test
    void only_필터가_아무것도_고르지_못하면_실패한다() {
        // 0케이스로 조용히 성공하면 "아무것도 재지 않은 실행"이 통과로 읽힌다. 오타의 대가를 즉시 치른다.
        assertThatThrownBy(() -> dataset.filter("notoool"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("맞는 케이스가 없습니다")
                .hasMessageContaining("notool.greeting");
    }

    // 레지스트리를 실제로 조립한다 — 노출 표를 테스트에 다시 적으면 그 사본이 또 썩는다.
    private static AgentToolRegistry registry() {
        return new AgentToolRegistry(List.of(
                new GetTodayTasksTool(mock()), new GetWeeklySummaryTool(mock()),
                new GetReflectionHistoryTool(mock()), new GetWorkloadRecommendationTool(mock()),
                new UpdatePlanTasksTool(), new CarryOverTool(mock())));
    }

    private static Set<String> exposedNames(AgentToolRegistry registry, EvalCase testCase) {
        return Set.copyOf(registry.toolsFor(testCase.status()).stream().map(AgentTool::name).toList());
    }
}
