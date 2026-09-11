package com.delaynomore.backend.domain.knowledge.service;

import com.delaynomore.backend.domain.knowledge.entity.KnowledgeDoc;
import com.delaynomore.backend.domain.knowledge.repository.KnowledgeRepository;
import com.delaynomore.backend.domain.knowledge.repository.KnowledgeRepository.ChunkWithSource;
import com.delaynomore.backend.domain.knowledge.search.DomainKnowledgeSearcher;
import com.delaynomore.backend.domain.knowledge.search.DomainKnowledgeSearcher.SearchHit;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 계획별 참고 자료(도메인 지식) — 업로드·목록·삭제·검색. 소유 판정은 자체 owner 컬럼이 아니라
 * plan 경유다: 모든 진입점이 {@code planService.getPlan(planId, owner)}를 먼저 통과하고,
 * 소유 불일치는 그 안에서 404(PLAN_NOT_FOUND)로 끝난다(reflections 선례).
 *
 * <p>개수·길이 상한은 스키마 CHECK가 아니라 여기 상수가 소유한다(규칙 소유권은 서버 코드).
 * 검색 순위는 {@link DomainKnowledgeSearcher} 한 곳이 매긴다 — 저장소는 로드만.
 */
@Service
@RequiredArgsConstructor
public class PlanKnowledgeService {

    public static final int MAX_DOCS_PER_PLAN = 10;
    public static final int MAX_CONTENT_CHARS = 20_000;
    public static final int MAX_TITLE_CHARS = 100;
    public static final int SEARCH_TOP_K = 3;

    private final PlanService planService;
    private final KnowledgeRepository knowledgeRepository;
    private final DomainKnowledgeSearcher searcher;

    @Transactional
    public KnowledgeDoc add(long planId, String owner, String title, String content) {
        PlanResponse plan = planService.getPlan(planId, owner); // 소유 불일치 → 404
        if (PlanStatus.fromStored(plan.status()).isTerminal()) {
            // 종결 상태 전면 잠금 관례 — 자료 추가도 변경이다(조회·검색은 허용).
            throw new BusinessException(ErrorCode.PLAN_LOCKED);
        }
        String trimmedTitle = title == null ? "" : title.strip();
        String trimmedContent = content == null ? "" : content.strip();
        if (trimmedTitle.isEmpty() || trimmedTitle.length() > MAX_TITLE_CHARS || trimmedContent.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (trimmedContent.length() > MAX_CONTENT_CHARS) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_DOC_TOO_LARGE);
        }
        if (knowledgeRepository.countByPlanId(planId) >= MAX_DOCS_PER_PLAN) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_LIMIT_EXCEEDED);
        }
        List<String> chunks = KnowledgeChunker.split(trimmedContent);
        return knowledgeRepository.saveDoc(planId, trimmedTitle, trimmedContent,
                Instant.now().toString(), chunks);
    }

    public List<KnowledgeDoc> list(long planId, String owner) {
        planService.getPlan(planId, owner);
        return knowledgeRepository.findDocsByPlanId(planId);
    }

    @Transactional
    public void delete(long planId, long docId, String owner) {
        PlanResponse plan = planService.getPlan(planId, owner);
        if (PlanStatus.fromStored(plan.status()).isTerminal()) {
            throw new BusinessException(ErrorCode.PLAN_LOCKED);
        }
        KnowledgeDoc doc = knowledgeRepository.findDocById(docId)
                .filter(d -> d.planId() == planId) // 다른 계획의 문서 id로는 지울 수 없다
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_DOC_NOT_FOUND));
        knowledgeRepository.deleteDoc(doc.id());
    }

    /** 검색 — 소유 확인 후 계획의 전체 청크를 로드해 검색기 한 곳에서 순위를 매긴다. */
    public List<SearchHit> search(long planId, String owner, String query) {
        planService.getPlan(planId, owner);
        List<ChunkWithSource> chunks = knowledgeRepository.findChunksByPlanId(planId);
        return searcher.search(query, chunks, SEARCH_TOP_K);
    }

    public int countDocs(long planId, String owner) {
        planService.getPlan(planId, owner);
        return knowledgeRepository.countByPlanId(planId);
    }
}
