package com.delaynomore.backend.domain.knowledge.repository;

import com.delaynomore.backend.domain.knowledge.entity.KnowledgeDoc;
import com.delaynomore.backend.domain.knowledge.repository.KnowledgeRepository.ChunkWithSource;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.jdbc.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// JdbcKnowledgeRepository 통합 테스트 — V11 스키마, 문서+청크 동시 저장, 조인 로드 정렬,
// FK 캐스케이드(문서 삭제 → 청크, 계획 삭제 → 문서·청크). Docker 없으면 통째로 건너뛴다.
class JdbcKnowledgeRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private KnowledgeRepository knowledgeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newPlanId() {
        Plan saved = planRepository.save(new Plan(null, "guest-1", "정보처리기사", 1, 1, "초급",
                Map.of(), "CONFIRMED", null, null, "2026-07-21", "2026-07-21", "2026-07-21T09:00:00Z", 1L, null));
        return saved.id();
    }

    private int chunkCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM plan_knowledge_chunks", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void saveDoc_문서와청크가함께저장되고_조인로드는docId_seq순이다() {
        long planId = newPlanId();

        knowledgeRepository.saveDoc(planId, "DB 노트", "원문", "2026-07-21T10:00:00Z",
                List.of("청크0", "청크1"));
        knowledgeRepository.saveDoc(planId, "영어 노트", "원문2", "2026-07-21T11:00:00Z",
                List.of("영어청크0"));

        List<ChunkWithSource> chunks = knowledgeRepository.findChunksByPlanId(planId);
        assertThat(chunks).extracting(c -> c.docTitle() + ":" + c.seq())
                .containsExactly("DB 노트:0", "DB 노트:1", "영어 노트:0");
        // 목록은 최신(id DESC) 순 — 화면이 방금 올린 자료를 맨 위에 보여준다.
        assertThat(knowledgeRepository.findDocsByPlanId(planId))
                .extracting(KnowledgeDoc::title).containsExactly("영어 노트", "DB 노트");
        assertThat(knowledgeRepository.countByPlanId(planId)).isEqualTo(2);
    }

    @Test
    void deleteDoc_청크는FK캐스케이드로함께사라진다() {
        long planId = newPlanId();
        KnowledgeDoc doc = knowledgeRepository.saveDoc(planId, "노트", "원문",
                "2026-07-21T10:00:00Z", List.of("a", "b", "c"));
        assertThat(chunkCount()).isEqualTo(3);

        knowledgeRepository.deleteDoc(doc.id());

        assertThat(knowledgeRepository.findDocById(doc.id())).isEmpty();
        assertThat(chunkCount()).isZero(); // ON DELETE CASCADE (V11)
    }

    @Test
    void 계획을지우면_자료도청크도남지않는다() {
        long planId = newPlanId();
        knowledgeRepository.saveDoc(planId, "노트", "원문", "2026-07-21T10:00:00Z", List.of("a", "b"));

        // plans FK의 ON DELETE CASCADE — 서비스 캐스케이드(PlanService.delete)와 이중 안전망이다.
        planRepository.deleteById(planId, plan -> { });

        assertThat(knowledgeRepository.findDocsByPlanId(planId)).isEmpty();
        assertThat(chunkCount()).isZero();
    }

    @Test
    void deleteAllByPlanId_그계획것만지운다() {
        long planA = newPlanId();
        long planB = newPlanId();
        knowledgeRepository.saveDoc(planA, "A 노트", "원문", "2026-07-21T10:00:00Z", List.of("a"));
        knowledgeRepository.saveDoc(planB, "B 노트", "원문", "2026-07-21T10:00:00Z", List.of("b"));

        knowledgeRepository.deleteAllByPlanId(planA);

        assertThat(knowledgeRepository.findDocsByPlanId(planA)).isEmpty();
        assertThat(knowledgeRepository.findDocsByPlanId(planB)).hasSize(1);
        assertThat(chunkCount()).isEqualTo(1);
    }
}
