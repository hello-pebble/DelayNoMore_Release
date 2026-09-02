package com.delaynomore.backend.domain.challenge.repository;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.entity.ChallengeParticipant;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 챌린지 JDBC 구현 — PostgreSQL에 영속한다. postgres 프로필에서만 활성화된다.
//
// [정원을 지키는 방식: 조건부 UPDATE]
// 정원 판정의 정답은 "검사를 쓰기 안으로 넣는 것"이다. 아래 한 문장이 검사와 증가를 동시에 한다:
//     UPDATE challenges SET participant_count = participant_count + 1
//      WHERE id = :id AND participant_count < capacity
// READ COMMITTED에서 뒤늦은 트랜잭션은 먼저 온 트랜잭션이 잡은 행 쓰기 락에 블로킹되고, 락이
// 풀리면 갱신된 행 버전으로 WHERE를 다시 평가한다. 자리가 찼으면 0행이 갱신되고 우리는 그것을
// CHALLENGE_FULL로 읽는다. 검사와 증가 사이에 다른 트랜잭션이 낄 틈이 없다.
// 반면 "SELECT participant_count → 자바에서 if → UPDATE +1"은 두 문장 사이가 열려 있어 정원을
// 넘긴다(ChallengeJoinConcurrencyIT가 실제로 초과를 재현한다).
//
// [정산도 같은 원칙이다(v0.25.0)] "챌린지는 최대 한 번 정산된다"의 판정은
//     UPDATE challenges SET settled_at = :now WHERE id = :id AND settled_at IS NULL
// 이 단독으로 한다. 동시 정산자는 행 락에 블로킹 후 WHERE 재평가로 0행 → 물러난다.
//
// [FOR UPDATE를 쓰지 않은 이유] JdbcPlanRepository는 "행 전체를 읽어 자바에서 가공"해야 해서
// SELECT ... FOR UPDATE로 원자 구간을 연다. 여기서 필요한 건 카운터·시각 컬럼에 대한
// 비교-후-갱신뿐이라 조건부 UPDATE 한 문장이면 충분하고, 왕복도 락 보유 시간도 짧다.
//
// [잠금 순서] 참가자 INSERT(자기 행) → 지갑 UPDATE(자기 행) → 챌린지 UPDATE(경합 행) 순으로
// 모든 스레드가 동일하게 진행하므로 교착이 생기지 않는다. 경합하는 챌린지 행 락은 마지막에,
// 가장 짧게 잡는다. 정산은 반대로 챌린지 행(claim)을 먼저 잡지만, 참가 경로와 겹치는 자원이
// 챌린지 행 하나뿐이라(정산은 지갑을 증가만, 참가는 자기 지갑만) 순환 대기가 생기지 않는다.
//
// [실패 시 원상복구] 이 메서드들은 호출한 @Transactional 서비스 메서드가 연 트랜잭션 안에서
// 실행돼야 한다(propagation REQUIRED). CHALLENGE_FULL로 던지면 앞서 성공한 참가자 INSERT와
// 포인트 차감이 롤백으로 함께 사라진다 — 자리를 못 얻은 참가자는 포인트도 잃지 않는다.
// 정산도 같다: recordPayout 도중 실패하면 claim(settled_at)째 롤백돼 부분 지급이 없다.
@Repository
@Profile("postgres")
public class JdbcChallengeRepository implements ChallengeRepository {

    // 데모 초기 잔액 — 인메모리 구현과 같은 값이어야 한다.
    static final int INITIAL_BALANCE = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<Challenge> challengeMapper = this::mapChallenge;
    private final RowMapper<ChallengeParticipant> participantMapper = this::mapParticipant;

    public JdbcChallengeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Challenge save(Challenge challenge) {
        String sql = """
                INSERT INTO challenges (owner, title, duration_days, capacity, entry_fee, participant_count,
                                        created_at, condition_key, started_at, settled_at)
                VALUES (:owner, :title, :durationDays, :capacity, :entryFee, :participantCount,
                        :createdAt, :conditionKey, :startedAt, :settledAt)
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, params(challenge), Long.class);
        return challenge.withId(id);
    }

    private static MapSqlParameterSource params(Challenge challenge) {
        return new MapSqlParameterSource()
                .addValue("owner", challenge.owner())
                .addValue("title", challenge.title())
                .addValue("durationDays", challenge.durationDays())
                .addValue("capacity", challenge.capacity())
                .addValue("entryFee", challenge.entryFee())
                .addValue("participantCount", challenge.participantCount())
                .addValue("createdAt", challenge.createdAt())
                .addValue("conditionKey", challenge.conditionKey())
                .addValue("startedAt", challenge.startedAt())
                .addValue("settledAt", challenge.settledAt());
    }

    @Override
    public List<Challenge> findAll() {
        return jdbc.query("SELECT * FROM challenges ORDER BY created_at DESC, id DESC",
                new MapSqlParameterSource(), challengeMapper);
    }

    @Override
    public Optional<Challenge> findById(long id) {
        return jdbc.query("SELECT * FROM challenges WHERE id = :id",
                new MapSqlParameterSource("id", id), challengeMapper).stream().findFirst();
    }

    @Override
    public Map<Long, ChallengeParticipant> findMyParticipations(String owner) {
        Map<Long, ChallengeParticipant> mine = new HashMap<>();
        jdbc.query("SELECT challenge_id, owner, plan_id, payout FROM challenge_participants WHERE owner = :owner",
                new MapSqlParameterSource("owner", owner),
                rs -> {
                    mine.put(rs.getLong("challenge_id"), mapParticipantRow(rs));
                });
        return mine;
    }

    @Override
    public List<ChallengeParticipant> findParticipants(long challengeId) {
        return jdbc.query("SELECT owner, plan_id, payout FROM challenge_participants WHERE challenge_id = :id",
                new MapSqlParameterSource("id", challengeId), participantMapper);
    }

    // 지갑 지연 생성 — ON CONFLICT DO NOTHING이라 동시 최초 조회가 겹쳐도 중복 INSERT로 깨지지 않는다.
    @Override
    public int balanceOf(String owner) {
        ensureWallet(owner);
        Integer balance = jdbc.queryForObject("SELECT balance FROM point_wallets WHERE owner = :owner",
                new MapSqlParameterSource("owner", owner), Integer.class);
        return balance == null ? 0 : balance;
    }

    @Override
    public Challenge join(long challengeId, String owner, String joinedAt, long planId) {
        // 존재 확인 전용 조회 — 여기서 읽은 participant_count로는 아무 판정도 하지 않는다.
        // 정원 판정은 아래 조건부 UPDATE가 단독으로 한다(읽은 값은 이미 낡았을 수 있으므로).
        Challenge current = findById(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        // 1) 중복 참가 — 복합 PK가 판정한다. 충돌이면 0행이므로 애플리케이션의 사전 조회가 필요 없다.
        int inserted = jdbc.update("""
                INSERT INTO challenge_participants (challenge_id, owner, joined_at, plan_id)
                VALUES (:challengeId, :owner, :joinedAt, :planId)
                ON CONFLICT (challenge_id, owner) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("challengeId", challengeId)
                .addValue("owner", owner)
                .addValue("joinedAt", joinedAt)
                .addValue("planId", planId));
        if (inserted == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_ALREADY_JOINED);
        }

        // 2) 참가비 차감 — 잔액 검사도 WHERE 안에 있다. 0행이면 잔액 부족.
        ensureWallet(owner);
        int debited = jdbc.update("""
                UPDATE point_wallets SET balance = balance - :fee
                 WHERE owner = :owner AND balance >= :fee
                """, new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("fee", current.entryFee()));
        if (debited == 0) {
            throw new BusinessException(ErrorCode.POINTS_INSUFFICIENT);
        }

        // 3) 자리 예약 — 이 한 문장이 이 기능의 핵심이다. 정원 검사에 더해
        //    - settled_at IS NULL: 미달인 채 환불 마감된 챌린지는 여전히 미달이라, 이 가드가
        //      없으면 마감 후 참가가 뚫린다.
        //    - CASE ... started_at: 마지막 자리를 채우는 참가가 시작 시각을 같은 문장에서 기록한다
        //      ("정원이 찬 순간 = 시작"의 판정도 원자 구간 안에 — 환불 정산과의 레이스를 막는다).
        //    0행이면 재조회로 사유를 고른다(재조회는 에러 메시지 선택용일 뿐, 판정은 이미 끝났다).
        int reserved = jdbc.update("""
                UPDATE challenges
                   SET participant_count = participant_count + 1,
                       started_at = CASE WHEN participant_count + 1 >= capacity THEN :now ELSE started_at END
                 WHERE id = :id AND participant_count < capacity AND settled_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", challengeId)
                .addValue("now", joinedAt));
        if (reserved == 0) {
            boolean closed = findById(challengeId).map(Challenge::settled).orElse(false);
            throw new BusinessException(closed ? ErrorCode.CHALLENGE_CLOSED : ErrorCode.CHALLENGE_FULL);
        }

        return findById(challengeId).orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }

    // 정산권 청구 — 판정(미정산 + 경로 조건)이 WHERE 안에 있다. 1행 = 이 트랜잭션이 정산권을 땄다.
    // requireStarted=false(미달 환불)의 started_at IS NULL 조건은 "읽을 땐 미달이었는데 그 사이
    // 마지막 참가로 시작된" 챌린지를 환불로 마감하는 TOCTOU를 막는다 — 참가의 3단계 UPDATE와
    // 같은 행 락에서 직렬화된다.
    @Override
    public boolean claimSettlement(long challengeId, String settledAt, boolean requireStarted) {
        String startedCondition = requireStarted ? "started_at IS NOT NULL" : "started_at IS NULL";
        int claimed = jdbc.update("""
                UPDATE challenges SET settled_at = :settledAt
                 WHERE id = :id AND settled_at IS NULL AND %s
                """.formatted(startedCondition), new MapSqlParameterSource()
                .addValue("id", challengeId)
                .addValue("settledAt", settledAt));
        return claimed == 1;
    }

    // 정산 지급 — claim을 딴 트랜잭션 안에서만 호출된다. 도중 실패하면 claim째 롤백(부분 지급 없음).
    @Override
    public void recordPayout(long challengeId, String owner, int amount) {
        ensureWallet(owner);
        jdbc.update("UPDATE point_wallets SET balance = balance + :amount WHERE owner = :owner",
                new MapSqlParameterSource().addValue("owner", owner).addValue("amount", amount));
        jdbc.update("""
                UPDATE challenge_participants SET payout = :amount
                 WHERE challenge_id = :challengeId AND owner = :owner
                """, new MapSqlParameterSource()
                .addValue("challengeId", challengeId)
                .addValue("owner", owner)
                .addValue("amount", amount));
    }

    // 씨앗 등록 — 복합 PK(condition_key, owner)가 "같은 사람 중복 카운트"를 DB에서 막는다.
    // ON CONFLICT DO NOTHING이라 재고정·동시 고정에도 예외 없이 멱등하다(계획 고정 트랜잭션을
    // 오염시키지 않는 것이 계약이다).
    @Override
    public int recordSeed(String conditionKey, String owner, String seededAt) {
        jdbc.update("""
                INSERT INTO challenge_seeds (condition_key, owner, seeded_at)
                VALUES (:conditionKey, :owner, :seededAt)
                ON CONFLICT (condition_key, owner) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("conditionKey", conditionKey)
                .addValue("owner", owner)
                .addValue("seededAt", seededAt));
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM challenge_seeds WHERE condition_key = :conditionKey",
                new MapSqlParameterSource("conditionKey", conditionKey), Integer.class);
        return count == null ? 0 : count;
    }

    // 조건별 생성 — 정원 판정과 같은 원칙이다. "같은 조건의 모집 중 챌린지가 있나?"를 자바에서
    // 확인하고 INSERT하면 그 사이가 열려 동시 고정 두 건이 같은 챌린지를 둘 만든다(TOCTOU).
    // 판정은 부분 UNIQUE 인덱스(uq_challenges_open_condition, WHERE participant_count < capacity
    // AND settled_at IS NULL)가 단독으로 하고, 충돌은 ON CONFLICT DO NOTHING으로 흡수한다.
    // 인덱스의 WHERE 덕분에 정원이 찼거나 정산 마감된 챌린지는 인덱스에서 빠져 같은 조건의 다음
    // 챌린지가 열릴 수 있다.
    @Override
    public void createIfNoOpenCondition(Challenge challenge) {
        jdbc.update("""
                INSERT INTO challenges (owner, title, duration_days, capacity, entry_fee, participant_count,
                                        created_at, condition_key, started_at, settled_at)
                VALUES (:owner, :title, :durationDays, :capacity, :entryFee, :participantCount,
                        :createdAt, :conditionKey, :startedAt, :settledAt)
                ON CONFLICT DO NOTHING
                """, params(challenge));
    }

    private void ensureWallet(String owner) {
        jdbc.update("""
                INSERT INTO point_wallets (owner, balance) VALUES (:owner, :initial)
                ON CONFLICT (owner) DO NOTHING
                """, new MapSqlParameterSource().addValue("owner", owner).addValue("initial", INITIAL_BALANCE));
    }

    private Challenge mapChallenge(ResultSet rs, int rowNum) throws SQLException {
        return new Challenge(
                rs.getLong("id"),
                rs.getString("owner"),
                rs.getString("title"),
                rs.getInt("duration_days"),
                rs.getInt("capacity"),
                rs.getInt("entry_fee"),
                rs.getInt("participant_count"),
                rs.getString("created_at"),
                rs.getString("condition_key"),
                rs.getString("started_at"),
                rs.getString("settled_at"));
    }

    private ChallengeParticipant mapParticipant(ResultSet rs, int rowNum) throws SQLException {
        return mapParticipantRow(rs);
    }

    private ChallengeParticipant mapParticipantRow(ResultSet rs) throws SQLException {
        long planId = rs.getLong("plan_id");
        Long planIdOrNull = rs.wasNull() ? null : planId;
        int payout = rs.getInt("payout");
        Integer payoutOrNull = rs.wasNull() ? null : payout;
        return new ChallengeParticipant(rs.getString("owner"), planIdOrNull, payoutOrNull);
    }
}
