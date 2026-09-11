package com.delaynomore.backend.domain.slack.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 슬랙 저장소 JDBC 구현 — postgres 프로필에서만 활성화된다. 모든 클레임의 판정은 SQL 한 문장
// (조건부 INSERT/UPDATE의 갱신 행 수)이 한다 — 사전 SELECT로 세어 보지 않는다(CONCURRENCY.md).
@Repository
@Profile("postgres")
public class JdbcSlackRepository implements SlackRepository {

    private static final RowMapper<SlackLink> LINK_MAPPER = (rs, rowNum) -> new SlackLink(
            rs.getString("owner"), rs.getString("slack_team_id"), rs.getString("slack_user_id"),
            rs.getString("slack_channel_id"), rs.getInt("active_start_min"), rs.getInt("active_end_min"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSlackRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SlackLink> findLinkByOwner(String owner) {
        return jdbc.query("SELECT * FROM slack_links WHERE owner = :owner",
                        new MapSqlParameterSource("owner", owner), LINK_MAPPER)
                .stream().findFirst();
    }

    @Override
    public Optional<SlackLink> findLinkBySlackUser(String teamId, String slackUserId) {
        return jdbc.query("""
                        SELECT * FROM slack_links
                         WHERE slack_team_id = :team AND slack_user_id = :user
                        """, new MapSqlParameterSource()
                        .addValue("team", teamId).addValue("user", slackUserId), LINK_MAPPER)
                .stream().findFirst();
    }

    @Override
    public void upsertLink(SlackLink link) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("owner", link.owner())
                .addValue("team", link.teamId())
                .addValue("user", link.slackUserId())
                .addValue("channel", link.channelId())
                .addValue("start", link.activeStartMin())
                .addValue("end", link.activeEndMin());
        // 같은 슬랙 계정의 다른 owner 연결을 먼저 지운다 — UNIQUE(slack_team_id, slack_user_id)
        // 위반을 "재연결 = 이전 연결 해제"라는 규칙으로 해소한다.
        jdbc.update("""
                DELETE FROM slack_links
                 WHERE slack_team_id = :team AND slack_user_id = :user AND owner <> :owner
                """, params);
        jdbc.update("""
                INSERT INTO slack_links (owner, slack_team_id, slack_user_id, slack_channel_id,
                                         active_start_min, active_end_min)
                VALUES (:owner, :team, :user, :channel, :start, :end)
                ON CONFLICT (owner) DO UPDATE SET
                    slack_team_id = EXCLUDED.slack_team_id,
                    slack_user_id = EXCLUDED.slack_user_id,
                    slack_channel_id = EXCLUDED.slack_channel_id,
                    active_start_min = EXCLUDED.active_start_min,
                    active_end_min = EXCLUDED.active_end_min,
                    updated_at = now()
                """, params);
    }

    @Override
    public void deleteLinkByOwner(String owner) {
        jdbc.update("DELETE FROM slack_links WHERE owner = :owner",
                new MapSqlParameterSource("owner", owner));
    }

    @Override
    public List<SlackLink> findAllLinks() {
        return jdbc.query("SELECT * FROM slack_links", LINK_MAPPER);
    }

    @Override
    public void saveLinkCode(String code, String owner, int ttlMinutes) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("code", code).addValue("owner", owner).addValue("ttl", ttlMinutes);
        jdbc.update("DELETE FROM slack_link_codes WHERE owner = :owner", params); // 재발급 = 이전 코드 무효
        jdbc.update("""
                INSERT INTO slack_link_codes (code, owner, expires_at)
                VALUES (:code, :owner, now() + make_interval(mins => :ttl))
                """, params);
    }

    // 판정(만료)과 삭제가 DELETE ... RETURNING 한 문장 — 같은 코드를 동시에 입력해도 한 번만 성공.
    @Override
    public Optional<String> consumeLinkCode(String code) {
        return jdbc.queryForList("""
                        DELETE FROM slack_link_codes
                         WHERE code = :code AND expires_at > now()
                        RETURNING owner
                        """, new MapSqlParameterSource("code", code), String.class)
                .stream().findFirst();
    }

    @Override
    public void deleteExpiredLinkCodes() {
        jdbc.update("DELETE FROM slack_link_codes WHERE expires_at < now()",
                new MapSqlParameterSource());
    }

    @Override
    public boolean claimDailySend(String owner, LocalDate date, String kind) {
        return jdbc.update("""
                INSERT INTO slack_daily_sends (owner, send_date, kind)
                VALUES (:owner, :date, :kind)
                ON CONFLICT (owner, send_date, kind) DO NOTHING
                """, sendParams(owner, date, kind)) == 1;
    }

    @Override
    public boolean reclaimUnsent(String owner, LocalDate date, String kind,
                                 int retryAfterMinutes, int maxAttempts) {
        return jdbc.update("""
                UPDATE slack_daily_sends
                   SET attempts = attempts + 1, claimed_at = now()
                 WHERE owner = :owner AND send_date = :date AND kind = :kind
                   AND sent_at IS NULL
                   AND claimed_at < now() - make_interval(mins => :retryAfter)
                   AND attempts < :maxAttempts
                """, sendParams(owner, date, kind)
                .addValue("retryAfter", retryAfterMinutes)
                .addValue("maxAttempts", maxAttempts)) == 1;
    }

    @Override
    public void markSent(String owner, LocalDate date, String kind) {
        jdbc.update("""
                UPDATE slack_daily_sends SET sent_at = now()
                 WHERE owner = :owner AND send_date = :date AND kind = :kind
                """, sendParams(owner, date, kind));
    }

    @Override
    public boolean updateActiveHours(String owner, int activeStartMin, int activeEndMin) {
        return jdbc.update("""
                UPDATE slack_links SET active_start_min = :start, active_end_min = :end, updated_at = now()
                 WHERE owner = :owner
                """, new MapSqlParameterSource()
                .addValue("owner", owner).addValue("start", activeStartMin).addValue("end", activeEndMin)) == 1;
    }

    private static final RowMapper<ReflectionSession> SESSION_MAPPER = (rs, rowNum) -> new ReflectionSession(
            rs.getString("owner"), rs.getDate("session_date").toLocalDate(), rs.getLong("plan_id"),
            rs.getString("state"), rs.getString("difficulty"));

    @Override
    public void createReflectionSession(String owner, LocalDate date, long planId, String state) {
        jdbc.update("""
                INSERT INTO slack_reflection_sessions (owner, session_date, plan_id, state)
                VALUES (:owner, :date, :planId, :state)
                ON CONFLICT (owner, session_date, plan_id) DO NOTHING
                """, sessionParams(owner, date, planId).addValue("state", state));
    }

    @Override
    public Optional<ReflectionSession> findAwaitingReflectionSession(String owner, LocalDate date) {
        return jdbc.query("""
                        SELECT * FROM slack_reflection_sessions
                         WHERE owner = :owner AND session_date = :date AND state LIKE 'AWAITING%'
                         ORDER BY plan_id LIMIT 1
                        """, sessionParams(owner, date, 0L), SESSION_MAPPER)
                .stream().findFirst();
    }

    @Override
    public Optional<ReflectionSession> findPendingReflectionSession(String owner, LocalDate date) {
        return jdbc.query("""
                        SELECT * FROM slack_reflection_sessions
                         WHERE owner = :owner AND session_date = :date AND state = 'PENDING'
                         ORDER BY plan_id LIMIT 1
                        """, sessionParams(owner, date, 0L), SESSION_MAPPER)
                .stream().findFirst();
    }

    @Override
    public void updateReflectionSession(String owner, LocalDate date, long planId, String state, String difficulty) {
        jdbc.update("""
                UPDATE slack_reflection_sessions
                   SET state = :state, difficulty = COALESCE(:difficulty, difficulty), updated_at = now()
                 WHERE owner = :owner AND session_date = :date AND plan_id = :planId
                """, sessionParams(owner, date, planId)
                .addValue("state", state).addValue("difficulty", difficulty));
    }

    @Override
    public void closeReflectionSessions(String owner, LocalDate date, String state) {
        jdbc.update("""
                UPDATE slack_reflection_sessions SET state = :state, updated_at = now()
                 WHERE owner = :owner AND session_date = :date
                   AND (state LIKE 'AWAITING%' OR state = 'PENDING')
                """, sessionParams(owner, date, 0L).addValue("state", state));
    }

    private static MapSqlParameterSource sessionParams(String owner, LocalDate date, long planId) {
        return new MapSqlParameterSource()
                .addValue("owner", owner).addValue("date", date).addValue("planId", planId);
    }

    @Override
    public boolean claimEvent(String eventId) {
        return jdbc.update("""
                INSERT INTO slack_event_dedup (event_id) VALUES (:eventId)
                ON CONFLICT (event_id) DO NOTHING
                """, new MapSqlParameterSource("eventId", eventId)) == 1;
    }

    @Override
    public void deleteOldEvents() {
        jdbc.update("DELETE FROM slack_event_dedup WHERE received_at < now() - interval '1 day'",
                new MapSqlParameterSource());
    }

    private static MapSqlParameterSource sendParams(String owner, LocalDate date, String kind) {
        return new MapSqlParameterSource()
                .addValue("owner", owner).addValue("date", date).addValue("kind", kind);
    }
}
