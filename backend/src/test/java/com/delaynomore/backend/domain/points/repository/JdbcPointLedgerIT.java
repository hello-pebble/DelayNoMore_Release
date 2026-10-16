package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.jdbc.AbstractPostgresIntegrationTest;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원장 JDBC 통합 테스트 — V12 스키마와 <b>UNIQUE (tx_key, account)가 실제로 이중 기표를
 * 막는지</b>를 진짜 PostgreSQL에서 잰다. 인메모리 구현의 Set 기반 멱등이 같은 계약을 흉내내지만,
 * 이 저장소의 관례대로 "판정은 DB가 한다"는 쪽이 원본이므로 여기서 증명한다.
 * Docker가 없으면 클래스가 통째로 스킵된다(AbstractPostgresIntegrationTest).
 */
class JdbcPointLedgerIT extends AbstractPostgresIntegrationTest {

    private static final String OWNER = "guest-ledger-0001";

    @Autowired
    private PointLedgerRepository ledger;

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PlanRepository planRepository;

    // challenge_participants.plan_id는 plans를 참조하는 FK다 — 참가 레코드를 만들려면 실제
    // 계획 행이 있어야 한다. id를 하드코딩하면 FK 위반으로 깨지므로 저장이 돌려준 id를 쓴다.
    private long planId(String owner) {
        String now = Instant.now().toString();
        return planRepository.save(new Plan(null, owner, "정보처리기사 실기", 14, 2, "초급", Map.of(),
                "CONFIRMED", now, null, "2026-09-01", "2026-09-14", now,
                System.currentTimeMillis(), "자격증")).id();
    }

    private static PointTransfer entryFee(long challengeId, String owner, int amount) {
        return new PointTransfer("join:" + challengeId + ":" + owner, owner,
                PointAccounts.escrowOfChallenge(challengeId), amount, PointTxKind.CHALLENGE_ENTRY,
                "challenge", String.valueOf(challengeId), Instant.now().toString());
    }

    @Test
    void post_한_거래는_두_행으로_남고_합이_0이다() {
        assertThat(ledger.post(entryFee(7L, OWNER, 100))).isTrue();

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE tx_key = :k",
                new MapSqlParameterSource("k", "join:7:" + OWNER), Integer.class);
        assertThat(rows).isEqualTo(2);
        assertThat(ledger.sumOf(OWNER)).isEqualTo(-100);
        assertThat(ledger.totalSum()).isZero();
    }

    @Test
    void post_같은_txKey_재시도는_UNIQUE_인덱스가_흡수한다() {
        ledger.post(entryFee(7L, OWNER, 100));

        assertThat(ledger.post(entryFee(7L, OWNER, 100))).isFalse();

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM point_ledger",
                new MapSqlParameterSource(), Integer.class);
        assertThat(rows).isEqualTo(2); // 여전히 두 줄 — 재시도가 돈을 두 번 옮기지 않는다
    }

    @Test
    void 지갑_지연생성은_발행_한_번만_기표한다() {
        // balanceOf는 목록 조회마다 불린다 — 여기가 새면 조회 횟수만큼 포인트가 발행된다.
        for (int i = 0; i < 3; i++) {
            challengeRepository.balanceOf(OWNER);
        }

        assertThat(ledger.sumOf(OWNER)).isEqualTo(1000);
        assertThat(ledger.sumOf(PointAccounts.ISSUANCE)).isEqualTo(-1000);
        assertThat(ledger.totalSum()).isZero();
    }

    @Test
    void 참가와_정산을_거친_뒤에도_계정별_합계가_잔액과_같다() {
        long id = challengeRepository.save(new Challenge(null, "system", "자격증 공부", 14, 5, 100, 0,
                Instant.now().toString(), "자격증:14", null, null)).id();
        challengeRepository.join(id, OWNER, Instant.now().toString(), planId(OWNER));
        challengeRepository.recordPayout(id, OWNER, 60, PointTxKind.CHALLENGE_PAYOUT);

        // 1000 − 100 + 60 = 960. 원장이 진실이고 잔액은 그 파생이라는 계약의 실측.
        assertThat(challengeRepository.balanceOf(OWNER)).isEqualTo(960);
        assertThat(ledger.sumOf(OWNER)).isEqualTo(960);
        assertThat(ledger.sumOf(PointAccounts.escrowOfChallenge(id))).isEqualTo(40);
        assertThat(ledger.totalSum()).isZero();
    }

    @Test
    void 계정_이관은_과거_내역까지_옮긴다() {
        challengeRepository.balanceOf(OWNER);

        ledger.reassignAccount(OWNER, "user-uuid-1");

        assertThat(ledger.sumOf(OWNER)).isZero();
        assertThat(ledger.sumOf("user-uuid-1")).isEqualTo(1000);
        assertThat(ledger.findByAccount("user-uuid-1", 10)).hasSize(1);
    }
}
