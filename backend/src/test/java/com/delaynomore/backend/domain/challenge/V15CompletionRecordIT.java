package com.delaynomore.backend.domain.challenge;

import com.delaynomore.backend.domain.plan.entity.Plan;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V15(기간 내 완주 기록)의 백필과 보류 — V14까지 적용한 상태에 챌린지·참가·목표를 심어 두고 V15를 적용한다.
 *
 * <ul>
 *   <li>정산 완료는 불변</li>
 *   <li>모집 중·마감 전은 현재 100% 상태로 초기화(JSONB 집계가 {@link Plan#allTasksDone()}과 동치)</li>
 *   <li>마감이 지난 미정산은 백필하지 않고 settlement_hold = TRUE</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class V15CompletionRecordIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private final JsonMapper json = new JsonMapper();

    private long insertPlan(JdbcTemplate jdbc, String owner, Map<String, Object> tasks) {
        String tasksJson = tasks == null ? null : json.writeValueAsString(tasks);
        return jdbc.queryForObject("""
                INSERT INTO plans (owner, goal_name, status, saved_at, tasks)
                VALUES (?, 'g', 'CONFIRMED', 1, CAST(? AS jsonb)) RETURNING id
                """, Long.class, owner, tasksJson);
    }

    // 같은 조건의 모집 중 챌린지는 동시에 하나뿐이다(부분 UNIQUE 인덱스) — 챌린지마다 조건 키를 달리한다.
    private long insertChallenge(JdbcTemplate jdbc, String conditionKey, String startedAt, String settledAt) {
        return jdbc.queryForObject("""
                INSERT INTO challenges (owner, title, duration_days, capacity, entry_fee, participant_count,
                                        created_at, condition_key, started_at, settled_at)
                VALUES ('system', 't', 1, 5, 100, 0, ?, ?, ?, ?) RETURNING id
                """, Long.class, Instant.now().toString(), conditionKey, startedAt, settledAt);
    }

    private void join(JdbcTemplate jdbc, long challengeId, String owner, long planId) {
        jdbc.update("INSERT INTO challenge_participants (challenge_id, owner, joined_at, plan_id) VALUES (?, ?, ?, ?)",
                challengeId, owner, Instant.now().toString(), planId);
    }

    @Test
    void 백필은_정산_완료를_건드리지_않고_마감_전만_초기화하며_마감_지난_미정산은_보류한다() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).target("14").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 목표들 — Plan.countAllTasks와 같은 방어가 필요한 모양을 섞는다.
        Map<String, Object> done = Map.of("2026-10-01", List.of(Map.of("id", "a", "content", "x", "completed", true)));
        Map<String, Object> partial = Map.of("2026-10-01", List.of(
                Map.of("id", "a", "content", "x", "completed", true),
                Map.of("id", "b", "content", "y", "completed", false)));
        Map<String, Object> empty = Map.of();
        Map<String, Object> weird = new LinkedHashMap<>();           // 배열이 아닌 날짜 값은 0개, 객체가 아닌 원소는 미완료
        weird.put("d1", "notalist");
        weird.put("d2", List.of(1, Map.of("completed", true)));
        Map<String, Object> ignoresNonList = new LinkedHashMap<>();  // 배열이 아닌 값은 무시하고 나머지가 전부 완료면 완주
        ignoresNonList.put("d", List.of(Map.of("completed", true)));
        ignoresNonList.put("x", 5);
        Map<String, Map<String, Object>> shapes = new LinkedHashMap<>();
        shapes.put("done", done);
        shapes.put("partial", partial);
        shapes.put("empty", empty);
        shapes.put("weird", weird);
        shapes.put("ignoresNonList", ignoresNonList);

        String hoursAgo = Instant.now().minusSeconds(3_600).toString();      // 1일짜리, 1시간 전 시작 → 마감 전
        String daysAgo = Instant.now().minusSeconds(3 * 86_400L).toString(); // 3일 전 시작 → 마감 지남
        long settledId = insertChallenge(jdbc, "자격증:14", daysAgo, daysAgo);   // 정산 완료
        long recruitingId = insertChallenge(jdbc, "어학:14", null, null);        // 모집 중
        long activeId = insertChallenge(jdbc, "운동:14", hoursAgo, null);        // 마감 전
        long overdueId = insertChallenge(jdbc, "독서:14", daysAgo, null);        // 마감 지난 미정산

        int i = 0;
        Map<String, Long> planIds = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : shapes.entrySet()) {
            String owner = "guest-" + e.getKey() + "-" + (i++);
            long planId = insertPlan(jdbc, owner, e.getValue());
            planIds.put(e.getKey(), planId);
            join(jdbc, settledId, owner, planId);
            join(jdbc, recruitingId, owner, planId);
            join(jdbc, activeId, owner, planId);
            join(jdbc, overdueId, owner, planId);
        }

        Flyway.configure().dataSource(ds).load().migrate();

        long expectedDone = 0;
        for (Map.Entry<String, Map<String, Object>> e : shapes.entrySet()) {
            long planId = planIds.get(e.getKey());
            boolean expectDone = allTasksDone(e.getValue());
            if (expectDone) {
                expectedDone++;
            }
            String owner = jdbc.queryForObject("SELECT owner FROM plans WHERE id = ?", String.class, planId);
            assertThat(completed(jdbc, recruitingId, owner) != null).as("%s 모집 중", e.getKey()).isEqualTo(expectDone);
            assertThat(completed(jdbc, activeId, owner) != null).as("%s 마감 전", e.getKey()).isEqualTo(expectDone);
            assertThat(completed(jdbc, settledId, owner)).as("%s 정산 완료는 불변", e.getKey()).isNull();
            assertThat(completed(jdbc, overdueId, owner)).as("%s 마감 지난 미정산은 백필 안 함", e.getKey()).isNull();
        }
        assertThat(hold(jdbc, settledId)).isFalse();
        assertThat(hold(jdbc, recruitingId)).isFalse();
        assertThat(hold(jdbc, activeId)).isFalse();
        assertThat(hold(jdbc, overdueId)).as("마감이 지난 미정산 건만 보류").isTrue();
        // 동치 검증이 의미 있으려면 완주/미완주 케이스가 모두 있어야 한다(done, ignoresNonList만 완주)
        assertThat(expectedDone).isEqualTo(2);
    }

    private static boolean allTasksDone(Map<String, Object> tasks) {
        return new Plan(null, "o", "g", 1, 1, "l", tasks, "CONFIRMED", null, null, null, null, null, 0L, null)
                .allTasksDone();
    }

    private static String completed(JdbcTemplate jdbc, long challengeId, String owner) {
        return jdbc.queryForObject("SELECT completed_at FROM challenge_participants WHERE challenge_id = ? AND owner = ?",
                String.class, challengeId, owner);
    }

    private static boolean hold(JdbcTemplate jdbc, long challengeId) {
        return jdbc.queryForObject("SELECT settlement_hold FROM challenges WHERE id = ?", Boolean.class, challengeId);
    }
}
