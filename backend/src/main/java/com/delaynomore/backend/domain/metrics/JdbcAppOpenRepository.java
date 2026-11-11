package com.delaynomore.backend.domain.metrics;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcAppOpenRepository implements AppOpenRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAppOpenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // StrictMode 이중 마운트·재시도가 같은 visit_id로 들어와도 1행 — 멱등은 PK가 보장한다.
    @Override
    public void record(String visitId, String ownerId, String createdAt) {
        jdbc.update("""
                INSERT INTO app_opens (visit_id, owner_id, created_at)
                VALUES (:visitId, :ownerId, :createdAt)
                ON CONFLICT (visit_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("visitId", visitId)
                .addValue("ownerId", ownerId)
                .addValue("createdAt", createdAt));
    }

    @Override
    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_opens", new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }
}
