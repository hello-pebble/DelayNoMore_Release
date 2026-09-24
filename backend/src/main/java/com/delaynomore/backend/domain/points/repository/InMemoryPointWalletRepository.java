package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지갑 인메모리 구현 — 휘발성(재시작 시 초기화). JDBC의 롤백 경로이자 단위·동시성 테스트의
 * 실측 저장소다. 원자성은 지갑 맵의 키 단위 구간({@code compute})으로 얻는다.
 */
@Repository
@Profile("!postgres")
public class InMemoryPointWalletRepository implements PointWalletRepository {

    /** 데모 초기 잔액 — JDBC 구현과 같은 값이어야 한다(프로필이 바뀌어도 화면 숫자가 같도록). */
    public static final int INITIAL_BALANCE = 1000;

    private final ConcurrentHashMap<String, Integer> wallets = new ConcurrentHashMap<>();
    private final PointLedgerRepository ledger;

    public InMemoryPointWalletRepository(PointLedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Override
    public int balanceOf(String owner) {
        return wallets.computeIfAbsent(owner, key -> {
            // 실제로 만든 호출만 기표한다. 멱등 키도 signup:<owner> 하나라, 만에 하나 두 번
            // 불려도 두 번 발행되지 않는다(원장 쪽 UNIQUE와 같은 계약).
            ledger.post(new PointTransfer("signup:" + key, PointAccounts.ISSUANCE, key,
                    INITIAL_BALANCE, PointTxKind.SIGNUP_BONUS, null, null, Instant.now().toString()));
            return INITIAL_BALANCE;
        });
    }

    // [순서가 규칙이다] 인메모리에는 롤백이 없으므로 "검사 → 기표 → 변경" 순으로 간다.
    //   ① 잔액 검사를 변경보다 앞에 둔다(이 저장소의 오랜 규칙 — 호출자가 잡은 원자 구간 안이라
    //      검사와 변경 사이에 다른 요청이 끼어들지 않는다).
    //   ② 기표가 실제로 일어났을 때만 잔액을 바꾼다. 같은 txKey의 재시도는 여기서 멈춘다 —
    //      원장만 흡수하고 잔액을 또 줄이면 불변식 2가 깨진다.
    @Override
    public void debit(String owner, int amount, PointTransfer transfer) {
        if (balanceOf(owner) < amount) { // balanceOf가 지연 생성 + 발행 기표를 함께 끝낸다
            throw new BusinessException(ErrorCode.POINTS_INSUFFICIENT);
        }
        if (!ledger.post(transfer)) {
            return; // 이미 기표된 거래 — 잔액도 그때 이미 반영됐다
        }
        wallets.computeIfPresent(owner, (key, balance) -> balance - amount);
    }

    @Override
    public void credit(String owner, int amount, PointTransfer transfer) {
        balanceOf(owner);
        if (!ledger.post(transfer)) {
            return;
        }
        wallets.computeIfPresent(owner, (key, balance) -> balance + amount);
    }
}
