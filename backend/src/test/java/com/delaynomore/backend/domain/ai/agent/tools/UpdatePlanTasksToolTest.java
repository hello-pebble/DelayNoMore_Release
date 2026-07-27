package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.ai.service.ChatPatchMerger;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * update_plan_tasks가 모델에게 돌려주는 payload의 계약 테스트.
 *
 * 이 payload를 읽는 것은 사람이 아니라 모델이고, 모델은 여기 담긴 수를 그대로 사용자에게
 * 말한다. 그래서 "수를 세는 이름이 무엇을 세는지" 자체가 검증 대상이다 — 전체 재작성처럼
 * 두 수가 우연히 같아지는 경우에는 이름이 틀려도 드러나지 않으므로, 아래 테스트들은 모두
 * <b>계획 일부만</b> 고쳐 두 수를 일부러 갈라놓는다.
 */
class UpdatePlanTasksToolTest {

    private final UpdatePlanTasksTool tool = new UpdatePlanTasksTool();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 일부_날짜만_고치면_바뀐_날짜_수와_계획_전체_길이가_구분된다() {
        AgentContext context = contextWithPlanOf(7);

        ToolResult result = tool.execute(patch("""
                {"patch": {"2026-07-27": ["프롬프트 엔지니어링 기본기"],
                           "2026-07-28": ["문서 요약 실습"]}}
                """), context);

        assertThat(result.ok()).isTrue();
        Map<String, Object> payload = payloadOf(result);
        assertThat(payload.get("changedDates")).isEqualTo(List.of("2026-07-27", "2026-07-28"));
        assertThat(payload.get("changedCount")).isEqualTo(2);
        assertThat(payload.get("totalDayCount")).isEqualTo(7);
    }

    /**
     * 예전 이름(dayCount)은 값이 "계획 전체 길이"인데 changedDates 바로 옆에 놓여 "바뀐 날짜
     * 수"로 읽혔다. 이름을 되돌리면 모델이 2일을 고치고 "7일을 바꿨습니다"라고 말할 수 있으므로,
     * 그 키가 다시 살아나지 않는 것까지 못 박는다.
     */
    @Test
    void 무엇을_세는지_모호한_dayCount_키는_payload에_없다() {
        ToolResult result = tool.execute(patch("""
                {"patch": {"2026-07-27": ["할 일 하나"]}}
                """), contextWithPlanOf(7));

        assertThat(payloadOf(result)).doesNotContainKey("dayCount");
    }

    @Test
    void 날짜를_지워_계획을_줄이면_줄어든_길이가_totalDayCount에_반영된다() {
        // 삭제(null)도 "바뀐 날짜"다 — changedCount는 세고, totalDayCount는 그만큼 줄어든다.
        ToolResult result = tool.execute(patch("""
                {"patch": {"2026-08-01": null, "2026-08-02": null}}
                """), contextWithPlanOf(7));

        Map<String, Object> payload = payloadOf(result);
        assertThat(payload.get("changedCount")).isEqualTo(2);
        assertThat(payload.get("totalDayCount")).isEqualTo(5);
    }

    /**
     * 노트는 모델이 실제로 따를 수 있는 선까지만 요구해야 한다. 날짜별 할 일을 전부 나열시키면
     * 바로 아래 계획 UI가 이미 보여주는 것을 산문으로 중복하게 되고, 모델은 그 지시를 무시한다 —
     * 무시당하는 노트가 하나 생기면 정말 지켜져야 할 노트(carry_over 등)의 무게까지 떨어진다.
     */
    @Test
    void 노트는_할_일_전체_나열을_요구하지_않는다() {
        ToolResult result = tool.execute(patch("""
                {"patch": {"2026-07-27": ["할 일 하나"]}}
                """), contextWithPlanOf(7));

        String note = (String) payloadOf(result).get("note");
        assertThat(note).contains("나열하지는 마세요");
    }

    @Test
    void 빈_patch는_실패로_돌려준다() {
        ToolResult result = tool.execute(patch("""
                {"patch": {}}
                """), contextWithPlanOf(7));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("patch가 비어 있습니다");
    }

    // --- helpers ---

    // 2026-07-27부터 연속 n일짜리 계획을 가진 DRAFT 문맥. 현재 계획은 실제 병합기로 정규화해
    // ({id, content, completed}) 저장본과 같은 모양을 쓴다.
    private AgentContext contextWithPlanOf(int days) {
        LocalDate start = LocalDate.parse("2026-07-27");
        Map<String, Object> seed = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            seed.put(start.plusDays(i).toString(), List.of("기존 할 일 " + (i + 1)));
        }
        Map<String, Object> current = ChatPatchMerger.merge(Map.of(), seed);
        return new AgentContext("guest-1", "session-1", 12L, PlanStatus.DRAFT, "생성형 AI 역량 평가", 2, current);
    }

    private JsonNode patch(String json) {
        return jsonMapper.readTree(json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(ToolResult result) {
        return (Map<String, Object>) result.payload();
    }
}
