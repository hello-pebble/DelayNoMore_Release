package com.delaynomore.backend.domain.knowledge.repository;

import com.delaynomore.backend.domain.knowledge.entity.KnowledgeDoc;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// 참고 자료 인메모리 구현 — postgres 프로필이 아닐 때(단위 테스트·로컬·평가 하네스)만 활성화.
@Repository
@Profile("!postgres")
public class InMemoryKnowledgeRepository implements KnowledgeRepository {

    private record StoredDoc(KnowledgeDoc doc, List<String> chunks) {
    }

    private final ConcurrentHashMap<Long, StoredDoc> docs = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    @Override
    public KnowledgeDoc saveDoc(long planId, String title, String content, String createdAt,
                                List<String> chunkContents) {
        long id = idSequence.incrementAndGet();
        KnowledgeDoc doc = new KnowledgeDoc(id, planId, title, content, createdAt);
        docs.put(id, new StoredDoc(doc, List.copyOf(chunkContents)));
        return doc;
    }

    @Override
    public List<KnowledgeDoc> findDocsByPlanId(long planId) {
        return docs.values().stream()
                .map(StoredDoc::doc)
                .filter(d -> d.planId() == planId)
                .sorted(Comparator.comparingLong(KnowledgeDoc::id).reversed())
                .toList();
    }

    @Override
    public int countByPlanId(long planId) {
        return (int) docs.values().stream().filter(s -> s.doc().planId() == planId).count();
    }

    @Override
    public Optional<KnowledgeDoc> findDocById(long docId) {
        return Optional.ofNullable(docs.get(docId)).map(StoredDoc::doc);
    }

    @Override
    public List<ChunkWithSource> findChunksByPlanId(long planId) {
        List<ChunkWithSource> chunks = new ArrayList<>();
        docs.values().stream()
                .filter(s -> s.doc().planId() == planId)
                .sorted(Comparator.comparingLong(s -> s.doc().id()))
                .forEach(s -> {
                    for (int seq = 0; seq < s.chunks().size(); seq++) {
                        chunks.add(new ChunkWithSource(s.doc().id(), s.doc().title(), seq, s.chunks().get(seq)));
                    }
                });
        return chunks;
    }

    @Override
    public void deleteDoc(long docId) {
        docs.remove(docId);
    }

    @Override
    public void deleteAllByPlanId(long planId) {
        docs.entrySet().removeIf(e -> e.getValue().doc().planId() == planId);
    }
}
