package com.delaynomore.backend.domain.knowledge.repository;

import com.delaynomore.backend.domain.knowledge.entity.KnowledgeDoc;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// 참고 자료 JDBC 구현 — postgres 프로필에서만 활성화. 점수 계산 SQL은 없다(로드만) —
// 순위는 서비스의 검색기가 매겨 두 프로필이 같은 결과를 낸다.
@Repository
@Profile("postgres")
public class JdbcKnowledgeRepository implements KnowledgeRepository {

    private static final RowMapper<KnowledgeDoc> DOC_MAPPER = (rs, rowNum) -> new KnowledgeDoc(
            rs.getLong("id"), rs.getLong("plan_id"), rs.getString("title"),
            rs.getString("content"), rs.getString("created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcKnowledgeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public KnowledgeDoc saveDoc(long planId, String title, String content, String createdAt,
                                List<String> chunkContents) {
        Long id = jdbc.queryForObject("""
                        INSERT INTO plan_knowledge_docs (plan_id, title, content, created_at)
                        VALUES (:planId, :title, :content, :createdAt)
                        RETURNING id
                        """, new MapSqlParameterSource()
                        .addValue("planId", planId).addValue("title", title)
                        .addValue("content", content).addValue("createdAt", createdAt),
                Long.class);
        for (int seq = 0; seq < chunkContents.size(); seq++) {
            jdbc.update("""
                    INSERT INTO plan_knowledge_chunks (doc_id, seq, content)
                    VALUES (:docId, :seq, :content)
                    """, new MapSqlParameterSource()
                    .addValue("docId", id).addValue("seq", seq)
                    .addValue("content", chunkContents.get(seq)));
        }
        return new KnowledgeDoc(id, planId, title, content, createdAt);
    }

    @Override
    public List<KnowledgeDoc> findDocsByPlanId(long planId) {
        return jdbc.query("""
                SELECT * FROM plan_knowledge_docs WHERE plan_id = :planId ORDER BY id DESC
                """, new MapSqlParameterSource("planId", planId), DOC_MAPPER);
    }

    @Override
    public int countByPlanId(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM plan_knowledge_docs WHERE plan_id = :planId",
                new MapSqlParameterSource("planId", planId), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<KnowledgeDoc> findDocById(long docId) {
        return jdbc.query("SELECT * FROM plan_knowledge_docs WHERE id = :id",
                        new MapSqlParameterSource("id", docId), DOC_MAPPER)
                .stream().findFirst();
    }

    @Override
    public List<ChunkWithSource> findChunksByPlanId(long planId) {
        return jdbc.query("""
                SELECT c.doc_id, d.title AS doc_title, c.seq, c.content
                  FROM plan_knowledge_chunks c
                  JOIN plan_knowledge_docs d ON d.id = c.doc_id
                 WHERE d.plan_id = :planId
                 ORDER BY c.doc_id, c.seq
                """, new MapSqlParameterSource("planId", planId), (rs, rowNum) -> new ChunkWithSource(
                rs.getLong("doc_id"), rs.getString("doc_title"),
                rs.getInt("seq"), rs.getString("content")));
    }

    @Override
    public void deleteDoc(long docId) {
        // 청크는 FK ON DELETE CASCADE가 함께 지운다(V11).
        jdbc.update("DELETE FROM plan_knowledge_docs WHERE id = :id",
                new MapSqlParameterSource("id", docId));
    }

    @Override
    public void deleteAllByPlanId(long planId) {
        jdbc.update("DELETE FROM plan_knowledge_docs WHERE plan_id = :planId",
                new MapSqlParameterSource("planId", planId));
    }
}
