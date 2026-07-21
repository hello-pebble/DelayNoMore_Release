package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.AuditEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

// 계획 변경 이력 JDBC 구현 — PostgreSQL에 영속한다(v0.12.0). postgres 프로필에서만 활성화된다.
// 인메모리의 링버퍼 상한(MAX_EVENTS)을 두지 않는다 — 누적이 목적이고, 보존 정리는 추후 배치로
// (v0.12.0 범위 제외). plan_id에 FK가 없어(스키마 참고) 계획 삭제 후에도 이력이 남는다.
@Repository
@Profile("postgres")
public class JdbcAuditEventRepository implements AuditEventRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<AuditEvent> auditMapper = this::mapAuditEvent;

    public JdbcAuditEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuditEvent append(AuditEvent event) {
        String sql = """
                INSERT INTO audit_events (plan_id, owner_id, type, detail, session_id, created_at)
                VALUES (:planId, :ownerId, :type, :detail, :sessionId, :createdAt)
                RETURNING id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("planId", event.planId())
                .addValue("ownerId", event.ownerId())
                .addValue("type", event.type())
                .addValue("detail", event.detail())
                .addValue("sessionId", event.sessionId())
                .addValue("createdAt", event.createdAt());
        Long id = jdbc.queryForObject(sql, params, Long.class);
        return event.withId(id);
    }

    @Override
    public List<AuditEvent> findAllByPlanIdAndOwner(long planId, String ownerId) {
        String sql = """
                SELECT * FROM audit_events
                WHERE plan_id = :planId AND owner_id = :ownerId
                ORDER BY id DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("ownerId", ownerId);
        return jdbc.query(sql, params, auditMapper);
    }

    @Override
    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events",
                new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    private AuditEvent mapAuditEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEvent(
                rs.getLong("id"),
                rs.getLong("plan_id"),
                rs.getString("owner_id"),
                rs.getString("type"),
                rs.getString("detail"),
                rs.getString("session_id"),
                rs.getString("created_at"));
    }
}
