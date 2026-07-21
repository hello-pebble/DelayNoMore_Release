package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Reflection;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

// 회고 JDBC 구현 — PostgreSQL에 영속한다(v0.12.0). postgres 프로필에서만 활성화된다.
// upsert는 인메모리의 compute 대신 "트랜잭션 + SELECT ... FOR UPDATE 후 INSERT/UPDATE"로
// 원자화한다 — createdAt 보존은 remapper(서비스 람다)가 기존 값을 받아 처리하므로 SQL은 형식만
// 제공한다. 호출자(@Transactional ReflectionService.save)의 트랜잭션 안에서 실행돼야 한다.
@Repository
@Profile("postgres")
public class JdbcReflectionRepository implements ReflectionRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<Reflection> reflectionMapper = this::mapReflection;

    public JdbcReflectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Reflection upsert(long planId, String date, UnaryOperator<Reflection> remapper) {
        Reflection existing = selectForUpdate(planId, date);
        Reflection next = remapper.apply(existing);
        if (existing == null) {
            String sql = """
                    INSERT INTO reflections (plan_id, date, completed_count, total_count,
                                             difficulty, reason, created_at, updated_at)
                    VALUES (:planId, :date, :completedCount, :totalCount,
                            :difficulty, :reason, :createdAt, :updatedAt)
                    """;
            jdbc.update(sql, reflectionParams(next));
        } else {
            // created_at은 갱신하지 않는다 — 최초 저장 시각 보존은 remapper가 이미 처리했다.
            String sql = """
                    UPDATE reflections SET completed_count = :completedCount, total_count = :totalCount,
                                           difficulty = :difficulty, reason = :reason, updated_at = :updatedAt
                    WHERE plan_id = :planId AND date = :date
                    """;
            jdbc.update(sql, reflectionParams(next));
        }
        return next;
    }

    @Override
    public Optional<Reflection> findByPlanIdAndDate(long planId, String date) {
        String sql = "SELECT * FROM reflections WHERE plan_id = :planId AND date = :date";
        List<Reflection> found = jdbc.query(sql, keyParams(planId, date), reflectionMapper);
        return found.stream().findFirst();
    }

    @Override
    public List<Reflection> findAllByPlanId(long planId) {
        String sql = "SELECT * FROM reflections WHERE plan_id = :planId ORDER BY date DESC";
        return jdbc.query(sql, new MapSqlParameterSource("planId", planId), reflectionMapper);
    }

    @Override
    public void deleteAllByPlanId(long planId) {
        jdbc.update("DELETE FROM reflections WHERE plan_id = :planId",
                new MapSqlParameterSource("planId", planId));
    }

    private Reflection selectForUpdate(long planId, String date) {
        String sql = "SELECT * FROM reflections WHERE plan_id = :planId AND date = :date FOR UPDATE";
        List<Reflection> locked = jdbc.query(sql, keyParams(planId, date), reflectionMapper);
        return locked.isEmpty() ? null : locked.getFirst();
    }

    private MapSqlParameterSource keyParams(long planId, String date) {
        return new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("date", date);
    }

    private MapSqlParameterSource reflectionParams(Reflection r) {
        return new MapSqlParameterSource()
                .addValue("planId", r.planId())
                .addValue("date", r.date())
                .addValue("completedCount", r.completedCount())
                .addValue("totalCount", r.totalCount())
                .addValue("difficulty", r.difficulty())
                .addValue("reason", r.reason())
                .addValue("createdAt", r.createdAt())
                .addValue("updatedAt", r.updatedAt());
    }

    private Reflection mapReflection(ResultSet rs, int rowNum) throws SQLException {
        return new Reflection(
                rs.getLong("plan_id"),
                rs.getString("date"),
                rs.getInt("completed_count"),
                rs.getInt("total_count"),
                rs.getString("difficulty"),
                rs.getString("reason"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
