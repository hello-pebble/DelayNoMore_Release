package com.delaynomore.backend.domain.knowledge.repository;

import com.delaynomore.backend.domain.knowledge.entity.KnowledgeDoc;

import java.util.List;
import java.util.Optional;

/**
 * 참고 자료 저장소 — 문서와 청크를 함께 관리한다. 점수 계산은 여기 없다: 저장소는 planId로
 * 청크를 로드만 하고, 순위는 서비스 계층의 검색기 한 곳이 매긴다(인메모리/JDBC 두 프로필이
 * 같은 순위를 내야 평가·QA가 재현된다).
 */
public interface KnowledgeRepository {

    /** 검색 입력 한 줄 — 출처 표기(문서 제목)까지 함께 로드한다. */
    record ChunkWithSource(long docId, String docTitle, int seq, String content) {
    }

    /** 문서 + 분할된 청크를 한 번에 저장하고 발급된 id가 채워진 문서를 돌려준다. */
    KnowledgeDoc saveDoc(long planId, String title, String content, String createdAt,
                         List<String> chunkContents);

    List<KnowledgeDoc> findDocsByPlanId(long planId); // 최신(id DESC) 순

    int countByPlanId(long planId);

    Optional<KnowledgeDoc> findDocById(long docId);

    List<ChunkWithSource> findChunksByPlanId(long planId); // (docId, seq) 오름차순

    void deleteDoc(long docId);

    /** 계획 삭제 캐스케이드(인메모리 프로필에는 FK가 없어 서비스가 직접 부른다 — reflections 관례). */
    void deleteAllByPlanId(long planId);
}
