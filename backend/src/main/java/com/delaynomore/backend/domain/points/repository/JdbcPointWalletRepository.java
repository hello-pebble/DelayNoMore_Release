package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 지갑 JDBC 구현 — postgres 프로필에서만 활성화된다.
 *
 * <p>차감의 판정은 조건부 UPDATE 하나가 단독으로 한다:
 * <pre>UPDATE point_wallets SET balance = balance - :amount WHERE owner = :owner AND balance >= :amount</pre>
 * 0행이면 잔액 부족이다. "SELECT balance → 자바 if → UPDATE"는 두 문장 사이가 열려 동시 차감이
 * 잔액을 음수로 만든다(docs/CONCURRENCY.md 2절과 같은 원칙).
 *
 * <p>기표는 이 클래스 안에서, 잔액을 바꾼 직후 같은 트랜잭션으로 한다 — 호출자가 잊을 수 없도록
 * 시그니처가 {@link PointTransfer}를 요구한다.
 */
@Repository
@Profile("postgres")
public class JdbcPointWalletRepository implements PointWalletRepository {

    /** 데모 초기 잔액 — 인메모리 구현과 같은 값이어야 한다. */
    public static final int INITIAL_BALANCE = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final PointLedgerRepository ledger;

    public JdbcPointWalletRepository(NamedParameterJdbcTemplate jdbc, PointLedgerRepository ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @Override
    public int balanceOf(String owner) {
        ensureWallet(owner);
        Integer balance = jdbc.queryForObject("SELECT balance FROM point_wallets WHERE owner = :owner",
                new MapSqlParameterSource("owner", owner), Integer.class);
        return balance == null ? 0 : balance;
    }

    // [순서가 규칙이다] 기표를 먼저 하고, 실제로 기표된 경우에만 잔액을 바꾼다 — 같은 txKey의
    // 재시도는 원장에서 막히고 잔액도 따라서 움직이지 않는다(원장만 흡수하고 잔액을 또 줄이면
    // 불변식 2가 깨진다). 잔액 부족으로 던지면 트랜잭션이 롤백돼 앞선 기표도 함께 사라진다 —
    // 인메모리 구현이 검사를 앞에 두는 자리를, JDBC에서는 롤백이 대신한다.
    @Override
    public void debit(String owner, int amount, PointTransfer transfer) {
        ensureWallet(owner);
        if (!ledger.post(transfer)) {
            return; // 이미 기표된 거래 — 잔액도 그때 이미 반영됐다
        }
        int debited = jdbc.update("""
                UPDATE point_wallets SET balance = balance - :amount
                 WHERE owner = :owner AND balance >= :amount
                """, new MapSqlParameterSource().addValue("owner", owner).addValue("amount", amount));
        if (debited == 0) {
            throw new BusinessException(ErrorCode.POINTS_INSUFFICIENT);
        }
    }

    @Override
    public void credit(String owner, int amount, PointTransfer transfer) {
        ensureWallet(owner);
        if (!ledger.post(transfer)) {
            return;
        }
        jdbc.update("UPDATE point_wallets SET balance = balance + :amount WHERE owner = :owner",
                new MapSqlParameterSource().addValue("owner", owner).addValue("amount", amount));
    }

    // 지갑을 실제로 만든 호출만 발행을 기표한다 — ON CONFLICT DO NOTHING이 0행을 돌려주면
    // 이미 있던 지갑이므로 발행도 없다. 목록 조회마다 불리는 경로라 이 구분이 핵심이다.
    private void ensureWallet(String owner) {
        int created = jdbc.update("""
                INSERT INTO point_wallets (owner, balance) VALUES (:owner, :initial)
                ON CONFLICT (owner) DO NOTHING
                """, new MapSqlParameterSource().addValue("owner", owner).addValue("initial", INITIAL_BALANCE));
        if (created == 1) {
            ledger.post(new PointTransfer("signup:" + owner, PointAccounts.ISSUANCE, owner,
                    INITIAL_BALANCE, PointTxKind.SIGNUP_BONUS, null, null, Instant.now().toString()));
        }
    }
}
