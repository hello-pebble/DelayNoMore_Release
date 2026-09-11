package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.knowledge.repository.KnowledgeRepository.ChunkWithSource;
import com.delaynomore.backend.domain.knowledge.search.DomainKnowledgeSearcher.SearchHit;
import com.delaynomore.backend.domain.knowledge.service.PlanKnowledgeService;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchDomainKnowledgeToolTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private PlanKnowledgeService knowledgeService;
    private SearchDomainKnowledgeTool tool;

    @BeforeEach
    void setUp() {
        knowledgeService = mock(PlanKnowledgeService.class);
        tool = new SearchDomainKnowledgeTool(knowledgeService);
    }

    @Test
    void 노출은_PlanStatus_능력_플래그를_그대로_따른다() {
        // 판정 기준을 여기서 재검증하지 않는다 — 도구의 답과 플래그의 답이 같은지만 본다.
        for (PlanStatus status : PlanStatus.values()) {
            assertThat(tool.isAvailableFor(status))
                    .as("%s", status)
                    .isEqualTo(status.allowsDomainResearch());
        }
    }

    @Test
    void 자료가_없으면_실패가_아니라_정상_결과다() {
        when(knowledgeService.search(anyLong(), anyString(), anyString())).thenReturn(List.of());
        when(knowledgeService.countDocs(anyLong(), anyString())).thenReturn(0);

        ToolResult result = tool.execute(args("정규화"), context());

        assertThat(result.ok()).isTrue(); // fail이면 모델이 인자를 바꿔 재시도한다
        assertThat(result.toModelPayload().toString()).contains("등록된 참고 자료가 없습니다");
    }

    @Test
    void 발췌_총량이_상한을_넘으면_잘라내고_truncated를_표시한다() {
        String big = "가".repeat(500);
        when(knowledgeService.search(anyLong(), anyString(), anyString())).thenReturn(List.of(
                hit(0, big), hit(1, big), hit(2, big), hit(3, big)));
        when(knowledgeService.countDocs(anyLong(), anyString())).thenReturn(1);

        ToolResult result = tool.execute(args("정규화"), context());

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.payload();
        assertThat((Boolean) payload.get("truncated")).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) payload.get("hits");
        int total = hits.stream().mapToInt(h -> ((String) h.get("excerpt")).length()).sum();
        assertThat(total).isLessThanOrEqualTo(1_600);
    }

    @Test
    void 빈_query는_실패로_되돌린다() {
        ToolResult result = tool.execute(args("  "), context());
        assertThat(result.ok()).isFalse();
    }

    @Test
    void 보관_전_초안에서는_사유를_돌려준다() {
        AgentContext noPlan = new AgentContext("owner-1", "s", null, PlanStatus.DRAFT, "목표", 2, Map.of());
        assertThat(tool.execute(args("정규화"), noPlan).ok()).isFalse();
    }

    private tools.jackson.databind.JsonNode args(String query) {
        return jsonMapper.readTree("{\"query\":\"" + query + "\"}");
    }

    private static AgentContext context() {
        return new AgentContext("owner-1", "s", 1L, PlanStatus.CONFIRMED, "정보처리기사", 2, Map.of());
    }

    private static SearchHit hit(int seq, String content) {
        return new SearchHit(new ChunkWithSource(1L, "노트", seq, content), 0.5);
    }
}
