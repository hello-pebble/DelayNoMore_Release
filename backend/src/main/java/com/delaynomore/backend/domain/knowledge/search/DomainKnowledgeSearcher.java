package com.delaynomore.backend.domain.knowledge.search;

import com.delaynomore.backend.domain.knowledge.repository.KnowledgeRepository.ChunkWithSource;

import java.util.List;

/**
 * 청크 검색기 — 질의와 청크 목록을 받아 상위 topK를 돌려준다. 인터페이스로 분리한 이유:
 * v0.29.0은 외부 의존성 없는 렉시컬 구현({@link BigramLexicalSearcher})으로 시작하지만,
 * 한국어 임베딩 모델을 확보하면 이 빈 하나만 교체해 벡터 검색으로 갈 수 있다 — 서비스·도구·
 * 저장소는 그대로다.
 */
public interface DomainKnowledgeSearcher {

    record SearchHit(ChunkWithSource chunk, double score) {
    }

    List<SearchHit> search(String query, List<ChunkWithSource> chunks, int topK);
}
