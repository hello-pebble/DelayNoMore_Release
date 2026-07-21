package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Plan;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

// 계획 보관함 JDBC 구현 — PostgreSQL에 영속한다(v0.12.0). postgres 프로필에서만 활성화된다.
// ObjectMapper는 tools.jackson(Jackson 3.x) 타입이어야 한다 — Boot 4.1의 JacksonAutoConfiguration이
// tools.jackson.databind.json.JsonMapper(ObjectMapper의 하위 타입) 빈만 등록하고, 구 Jackson 2
// (com.fasterxml.jackson.databind.ObjectMapper)는 별도 빈으로 자동구성되지 않는다.
// update/mutate/deleteById의 원자성은 인메모리의 computeIfPresent 대신 "트랜잭션 + SELECT ...
// FOR UPDATE 후 검사/변형/기록"으로 얻는다 — 이 메서드들은 호출한 @Transactional 서비스 메서드가
// 연 트랜잭션(propagation REQUIRED) 안에서 실행돼야 잠금이 커밋까지 유지된다. 가드/뮤테이터가
// 예외를 던지면 트랜잭션이 롤백돼 저장소가 변경되지 않은 채 예외가 전파된다(인메모리와 동일 계약).
// tasks(Map)는 JSONB 컬럼에 보관한다 — 쓰기는 CAST(:tasks AS jsonb), 읽기는 Jackson으로 역직렬화.
@Repository
@Profile("postgres")
public class JdbcPlanRepository implements PlanRepository {

    private static final TypeReference<Map<String, Object>> TASKS_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Plan> planMapper;

    public JdbcPlanRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.planMapper = this::mapPlan;
    }

    @Override
    public Plan save(Plan plan) {
        String sql = """
                INSERT INTO plans (owner, goal_name, duration, daily_hours, current_level,
                                   tasks, status, confirmed_at, start_date, end_date, created_at, saved_at)
                VALUES (:owner, :goalName, :duration, :dailyHours, :currentLevel,
                        CAST(:tasks AS jsonb), :status, :confirmedAt, :startDate, :endDate, :createdAt, :savedAt)
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, planParams(plan), Long.class);
        return plan.withId(id);
    }

    @Override
    public List<Plan> findAllByOwner(String owner) {
        String sql = "SELECT * FROM plans WHERE owner = :owner ORDER BY saved_at DESC, id DESC";
        return jdbc.query(sql, new MapSqlParameterSource("owner", owner), planMapper);
    }

    @Override
    public Optional<Plan> findById(long id) {
        String sql = "SELECT * FROM plans WHERE id = :id";
        List<Plan> found = jdbc.query(sql, new MapSqlParameterSource("id", id), planMapper);
        return found.stream().findFirst();
    }

    @Override
    public long countByOwner(String owner) {
        String sql = "SELECT COUNT(*) FROM plans WHERE owner = :owner";
        Long count = jdbc.queryForObject(sql, new MapSqlParameterSource("owner", owner), Long.class);
        return count == null ? 0L : count;
    }

    // 트랜잭션 안에서 현재 행을 잠그고(FOR UPDATE) 가드를 통과하면 통째로 교체한 뒤 "교체 전 값"을
    // 돌려준다(없으면 null). 가드가 예외를 던지면 트랜잭션 롤백으로 UPDATE가 무효화된다.
    @Override
    public Plan update(Plan plan, Consumer<Plan> currentStateGuard) {
        Plan previous = selectForUpdate(plan.id());
        if (previous == null) {
            return null;
        }
        currentStateGuard.accept(previous);
        String sql = """
                UPDATE plans SET owner = :owner, goal_name = :goalName, duration = :duration,
                                 daily_hours = :dailyHours, current_level = :currentLevel,
                                 tasks = CAST(:tasks AS jsonb), status = :status, confirmed_at = :confirmedAt,
                                 start_date = :startDate, end_date = :endDate, created_at = :createdAt,
                                 saved_at = :savedAt
                WHERE id = :id
                """;
        jdbc.update(sql, planParams(plan));
        return previous;
    }

    // read-modify-write — 현재 행을 잠그고 읽어 mutator로 새 상태를 만든 뒤 교체한다. mutator가
    // 현재 상태를 그대로 반환하면 값이 같아 사실상 no-op(동일 값 재기록, 무해). 부재 시 null.
    @Override
    public Plan mutate(long id, UnaryOperator<Plan> mutator) {
        Plan current = selectForUpdate(id);
        if (current == null) {
            return null;
        }
        Plan next = mutator.apply(current);
        String sql = """
                UPDATE plans SET owner = :owner, goal_name = :goalName, duration = :duration,
                                 daily_hours = :dailyHours, current_level = :currentLevel,
                                 tasks = CAST(:tasks AS jsonb), status = :status, confirmed_at = :confirmedAt,
                                 start_date = :startDate, end_date = :endDate, created_at = :createdAt,
                                 saved_at = :savedAt
                WHERE id = :id
                """;
        jdbc.update(sql, planParams(next));
        return next;
    }

    // 현재 행을 잠그고 가드를 통과하면 제거한 뒤 "제거된 값"을 돌려준다(없으면 null).
    @Override
    public Plan deleteById(long id, Consumer<Plan> currentStateGuard) {
        Plan removed = selectForUpdate(id);
        if (removed == null) {
            return null;
        }
        currentStateGuard.accept(removed);
        jdbc.update("DELETE FROM plans WHERE id = :id", new MapSqlParameterSource("id", id));
        return removed;
    }

    @Override
    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM plans", new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    // 행 단위 원자 구간의 진입점 — 호출자 트랜잭션 안에서 잠금을 잡는다. 없으면 null.
    private Plan selectForUpdate(long id) {
        List<Plan> locked = jdbc.query("SELECT * FROM plans WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), planMapper);
        return locked.isEmpty() ? null : locked.getFirst();
    }

    private MapSqlParameterSource planParams(Plan plan) {
        return new MapSqlParameterSource()
                .addValue("id", plan.id())
                .addValue("owner", plan.owner())
                .addValue("goalName", plan.goalName())
                .addValue("duration", plan.duration())
                .addValue("dailyHours", plan.dailyHours())
                .addValue("currentLevel", plan.currentLevel())
                .addValue("tasks", writeTasks(plan.tasks()))
                .addValue("status", plan.status())
                .addValue("confirmedAt", plan.confirmedAt())
                .addValue("startDate", plan.startDate())
                .addValue("endDate", plan.endDate())
                .addValue("createdAt", plan.createdAt())
                .addValue("savedAt", plan.savedAt());
    }

    private Plan mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new Plan(
                rs.getLong("id"),
                rs.getString("owner"),
                rs.getString("goal_name"),
                (Integer) rs.getObject("duration"),
                (Integer) rs.getObject("daily_hours"),
                rs.getString("current_level"),
                readTasks(rs.getString("tasks")),
                rs.getString("status"),
                rs.getString("confirmed_at"),
                rs.getString("start_date"),
                rs.getString("end_date"),
                rs.getString("created_at"),
                rs.getLong("saved_at"));
    }

    // tasks 직렬화 — null이면 SQL NULL(문자열 "null"이 아님)로 남긴다.
    private String writeTasks(Map<String, Object> tasks) {
        if (tasks == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tasks);
        } catch (JacksonException e) {
            throw new IllegalStateException("tasks JSON 직렬화 실패", e);
        }
    }

    private Map<String, Object> readTasks(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TASKS_TYPE);
        } catch (JacksonException e) {
            throw new IllegalStateException("tasks JSON 역직렬화 실패", e);
        }
    }
}
