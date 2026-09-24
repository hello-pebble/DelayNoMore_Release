package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PointLedgerEntry;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 원장 JDBC 구현 — postgres 프로필에서만 활성화된다.
 *
 * <p><b>이중 기표를 막는 것은 애플리케이션 검사가 아니라 UNIQUE (tx_key, account)다.</b>
 * "이미 기표했나?"를 SELECT로 확인하고 INSERT하면 그 사이가 열려 동시 재시도가 두 번 적는다
 * (정원·자동 개설과 같은 TOCTOU). 그래서 그냥 넣어 보고, 충돌하면 0행으로 돌아온 것을 읽는다.
 *
 * <p>두 줄은 한 문장(다중 VALUES)으로 넣는다 — 부분 삽입이 불가능해지고, 충돌 시에도 둘 다
 * 들어가지 않는다. 기표는 언제나 호출자의 트랜잭션 안에서 일어나므로(지갑을 바꾸는 그 구간),
 * 잔액만 바뀌고 원장이 비는 상태는 생기지 않는다.
 */
@Repository
@Profile("postgres")
public class JdbcPointLedgerRepository implements PointLedgerRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<PointLedgerEntry> entryMapper = this::mapEntry;

    public JdbcPointLedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean post(PointTransfer transfer) {
        int inserted = jdbc.update("""
                INSERT INTO point_ledger (tx_key, account, amount, kind, ref_type, ref_id, created_at)
                VALUES (:txKey, :from, :negative, :kind, :refType, :refId, :createdAt),
                       (:txKey, :to,   :amount,   :kind, :refType, :refId, :createdAt)
                ON CONFLICT (tx_key, account) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("txKey", transfer.txKey())
                .addValue("from", transfer.from())
                .addValue("to", transfer.to())
                .addValue("amount", transfer.amount())
                .addValue("negative", -transfer.amount())
                .addValue("kind", transfer.kind().name())
                .addValue("refType", transfer.refType())
                .addValue("refId", transfer.refId())
                .addValue("createdAt", transfer.createdAt()));
        // 2행 = 새로 기표됨, 0행 = 같은 txKey가 이미 있음.
        // 1행은 한쪽만 들어갔다는 뜻이고 그 순간 원장의 합이 0이 아니게 된다 — 조용히 넘기면
        // 불변식이 깨진 채로 남으므로, 트랜잭션을 되돌리도록 던진다.
        if (inserted == 1) {
            throw new IllegalStateException("원장 기표가 한쪽만 들어갔습니다: txKey=" + transfer.txKey());
        }
        return inserted == 2;
    }

    @Override
    public List<PointLedgerEntry> findByAccount(String account, int limit) {
        return jdbc.query("""
                SELECT * FROM point_ledger WHERE account = :account ORDER BY id DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("account", account).addValue("limit", limit),
                entryMapper);
    }

    @Override
    public int sumOf(String account) {
        Integer sum = jdbc.queryForObject(
                "SELECT COALESCE(sum(amount), 0) FROM point_ledger WHERE account = :account",
                new MapSqlParameterSource("account", account), Integer.class);
        return sum == null ? 0 : sum;
    }

    @Override
    public int totalSum() {
        Integer sum = jdbc.queryForObject("SELECT COALESCE(sum(amount), 0) FROM point_ledger",
                new MapSqlParameterSource(), Integer.class);
        return sum == null ? 0 : sum;
    }

    // 게스트 흡수 — 과거 내역이 계정을 따라간다. tx_key는 그대로 둔다(그 거래가 일어난 시점의
    // 신원이 키에 남아 있는 편이 추적에 유리하고, 키를 바꾸면 UNIQUE 충돌 가능성만 는다).
    @Override
    public void reassignAccount(String fromAccount, String toAccount) {
        jdbc.update("UPDATE point_ledger SET account = :to WHERE account = :from",
                new MapSqlParameterSource().addValue("from", fromAccount).addValue("to", toAccount));
    }

    private PointLedgerEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new PointLedgerEntry(
                rs.getLong("id"),
                rs.getString("tx_key"),
                rs.getString("account"),
                rs.getInt("amount"),
                PointTxKind.valueOf(rs.getString("kind")),
                rs.getString("ref_type"),
                rs.getString("ref_id"),
                rs.getString("created_at"));
    }
}
