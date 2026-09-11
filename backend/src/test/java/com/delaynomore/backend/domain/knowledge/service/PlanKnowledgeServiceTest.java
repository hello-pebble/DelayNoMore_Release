package com.delaynomore.backend.domain.knowledge.service;

import com.delaynomore.backend.domain.knowledge.entity.KnowledgeDoc;
import com.delaynomore.backend.domain.knowledge.repository.InMemoryKnowledgeRepository;
import com.delaynomore.backend.domain.knowledge.search.BigramLexicalSearcher;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanKnowledgeServiceTest {

    private static final String OWNER = "owner-1";

    private PlanService planService;
    private InMemoryKnowledgeRepository repository;
    private PlanKnowledgeService service;

    @BeforeEach
    void setUp() {
        planService = mock(PlanService.class);
        repository = new InMemoryKnowledgeRepository();
        service = new PlanKnowledgeService(planService, repository, new BigramLexicalSearcher());
        when(planService.getPlan(1L, OWNER)).thenReturn(plan("CONFIRMED"));
        // 소유 불일치 — 실제 PlanService와 같은 계약(404).
        when(planService.getPlan(eq(1L), eq("other")))
                .thenThrow(new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    @Test
    void 추가하면_청크가_함께_저장되고_목록은_최신순이다() {
        service.add(1L, OWNER, "노트 A", "정규화 요약 내용");
        service.add(1L, OWNER, "노트 B", "조인 요약 내용");

        assertThat(service.list(1L, OWNER)).extracting(KnowledgeDoc::title)
                .containsExactly("노트 B", "노트 A");
        assertThat(repository.findChunksByPlanId(1L)).hasSize(2);
    }

    @Test
    void 소유_불일치는_404다() {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.add(1L, "other", "노트", "내용"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void 종결_계획에는_추가할_수_없다() {
        when(planService.getPlan(1L, OWNER)).thenReturn(plan("COMPLETED"));

        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.add(1L, OWNER, "노트", "내용"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
    }

    @Test
    void 개수_한도를_넘으면_400이다() {
        for (int i = 0; i < PlanKnowledgeService.MAX_DOCS_PER_PLAN; i++) {
            service.add(1L, OWNER, "노트 " + i, "내용 " + i);
        }
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.add(1L, OWNER, "초과", "내용"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
    }

    @Test
    void 길이_한도를_넘으면_400이다() {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.add(1L, OWNER, "긴 자료", "가".repeat(PlanKnowledgeService.MAX_CONTENT_CHARS + 1)));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KNOWLEDGE_DOC_TOO_LARGE);
    }

    @Test
    void 다른_계획의_문서_id로는_지울_수_없다() {
        when(planService.getPlan(2L, OWNER)).thenReturn(plan("CONFIRMED"));
        KnowledgeDoc doc = service.add(1L, OWNER, "노트", "내용");

        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.delete(2L, doc.id(), OWNER)); // 내 계획 2로 계획 1의 문서를 지우려는 시도
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.KNOWLEDGE_DOC_NOT_FOUND);
        assertThat(service.list(1L, OWNER)).hasSize(1);
    }

    @Test
    void 검색은_관련_청크를_돌려준다() {
        service.add(1L, OWNER, "DB 노트", "제1정규형은 원자값. 제2정규형은 부분 함수 종속 제거.");
        service.add(1L, OWNER, "영어 노트", "관계대명사 정리.");

        var hits = service.search(1L, OWNER, "정규화 설명");

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().chunk().docTitle()).isEqualTo("DB 노트");
    }

    private static PlanResponse plan(String status) {
        return new PlanResponse(1L, "정보처리기사", null, null, null, null, status,
                null, null, null, null, null, 0L, new PlanResponse.Progress(0, 0), false);
    }
}
