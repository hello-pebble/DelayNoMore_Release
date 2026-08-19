package com.delaynomore.backend.domain.challenge.repository;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
// [FOR UPDATE를 쓰지 않은 이유] JdbcPlanRepository는 "행 전체를 읽어 자바에서 가공"해야 해서
// SELECT ... FOR UPDATE로 원자 구간을 연다. 여기서 필요한 건 카운터 하나에 대한 비교-후-증가뿐이라
// 조건부 UPDATE 한 문장이면 충분하고, 왕복도 락 보유 시간도 짧다.
//
// [잠금 순서] 참가자 INSERT(자기 행) → 지갑 UPDATE(자기 행) → 챌린지 UPDATE(경합 행) 순으로
// 모든 스레드가 동일하게 진행하므로 교착이 생기지 않는다. 경합하는 챌린지 행 락은 마지막에,
// 가장 짧게 잡는다.
//
// [실패 시 원상복구] 이 메서드들은 호출한 @Transactional 서비스 메서드가 연 트랜잭션 안에서
// 실행돼야 한다(propagation REQUIRED). CHALLENGE_FULL로 던지면 앞서 성공한 참가자 INSERT와
// 포인트 차감이 롤백으로 함께 사라진다 — 자리를 못 얻은 참가자는 포인트도 잃지 않는다.
@Repository
@Profile("postgres")
public class JdbcChallengeRepository implements ChallengeRepository {

    // 데모 초기 잔액 — 인메모리 구현과 같은 값이어야 한다.
    static final int INITIAL_BALANCE = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<Challenge> challengeMapper = this::mapChallenge;

    public JdbcChallengeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Challenge save(Challenge challenge) {
        String sql = """
                INSERT INTO challenges (owner, title, duration_days, capacity, entry_fee, participant_count, created_at)
                VALUES (:owner, :title, :durationDays, :capacity, :entryFee, :participantCount, :createdAt)
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, new MapSqlParameterSource()
                .addValue("owner", challenge.owner())
                .addValue("title", challenge.title())
                .addValue("durationDays", challenge.durationDays())
                .addValue("capacity", challenge.capacity())
                .addValue("entryFee", challenge.entryFee())
                .addValue("participantCount", challenge.participantCount())
                .addValue("createdAt", challenge.createdAt()), Long.class);
        return challenge.withId(id);
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
    public Set<Long> findJoinedChallengeIds(String owner) {
        return new HashSet<>(jdbc.queryForList("SELECT challenge_id FROM challenge_participants WHERE owner = :owner",
                new MapSqlParameterSource("owner", owner), Long.class));
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
    public Challenge join(long challengeId, String owner, String joinedAt) {
        // 존재 확인 전용 조회 — 여기서 읽은 participant_count로는 아무 판정도 하지 않는다.
        // 정원 판정은 아래 조건부 UPDATE가 단독으로 한다(읽은 값은 이미 낡았을 수 있으므로).
        Challenge current = findById(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));

        // 1) 중복 참가 — 복합 PK가 판정한다. 충돌이면 0행이므로 애플리케이션의 사전 조회가 필요 없다.
        int inserted = jdbc.update("""
                INSERT INTO challenge_participants (challenge_id, owner, joined_at)
                VALUES (:challengeId, :owner, :joinedAt)
                ON CONFLICT (challenge_id, owner) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("challengeId", challengeId)
                .addValue("owner", owner)
                .addValue("joinedAt", joinedAt));
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

        // 3) 자리 예약 — 이 한 문장이 이 기능의 핵심이다. 0행이면 그 사이 정원이 찼다는 뜻이고,
        //    예외를 던지면 1·2가 롤백으로 함께 되돌아간다.
        int reserved = jdbc.update("""
                UPDATE challenges SET participant_count = participant_count + 1
                 WHERE id = :id AND participant_count < capacity
                """, new MapSqlParameterSource("id", challengeId));
        if (reserved == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_FULL);
        }

        return findById(challengeId).orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
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
                rs.getString("created_at"));
    }
}
