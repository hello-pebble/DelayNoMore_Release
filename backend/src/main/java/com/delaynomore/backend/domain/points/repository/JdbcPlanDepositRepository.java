package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PlanDeposit;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 목표 예치 JDBC 구현 — postgres 프로필에서만 활성화된다.
 *
 * <p>두 판정 모두 SQL 한 문장이 단독으로 한다(docs/CONCURRENCY.md 11절):
 * 중복 예치는 {@code ON CONFLICT (plan_id) DO NOTHING}의 0행, 이중 정산은
 * {@code WHERE settled_at IS NULL}의 0행이다. 애플리케이션이 미리 조회해 판정하지 않는다.
 */
@Repository
@Profile("postgres")
public class JdbcPlanDepositRepository implements PlanDepositRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<PlanDeposit> mapper = this::map;

    public JdbcPlanDepositRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean create(PlanDeposit deposit) {
        int inserted = jdbc.update("""
                INSERT INTO plan_deposits (plan_id, owner, amount, created_at)
                VALUES (:planId, :owner, :amount, :createdAt)
                ON CONFLICT (plan_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("planId", deposit.planId())
                .addValue("owner", deposit.owner())
                .addValue("amount", deposit.amount())
                .addValue("createdAt", deposit.createdAt()));
        return inserted == 1;
    }

    @Override
    public Optional<PlanDeposit> findByPlanId(long planId) {
        return jdbc.query("SELECT * FROM plan_deposits WHERE plan_id = :planId",
                new MapSqlParameterSource("planId", planId), mapper).stream().findFirst();
    }

    @Override
    public boolean claimSettlement(long planId, String settledAt, int refunded) {
        int claimed = jdbc.update("""
                UPDATE plan_deposits SET settled_at = :settledAt, refunded = :refunded
                 WHERE plan_id = :planId AND settled_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("settledAt", settledAt)
                .addValue("refunded", refunded));
        return claimed == 1;
    }

    @Override
    public void deleteByPlanId(long planId) {
        jdbc.update("DELETE FROM plan_deposits WHERE plan_id = :planId",
                new MapSqlParameterSource("planId", planId));
    }

    private PlanDeposit map(ResultSet rs, int rowNum) throws SQLException {
        int refunded = rs.getInt("refunded");
        Integer refundedOrNull = rs.wasNull() ? null : refunded;
        return new PlanDeposit(rs.getLong("plan_id"), rs.getString("owner"), rs.getInt("amount"),
                rs.getString("created_at"), rs.getString("settled_at"), refundedOrNull);
    }
}
