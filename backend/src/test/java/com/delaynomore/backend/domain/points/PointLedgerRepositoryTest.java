package com.delaynomore.backend.domain.points;

import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointLedgerEntry;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.domain.points.repository.InMemoryPointLedgerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 원장 저장소의 계약 검증 — 복식부기(두 줄·합 0)와 멱등(tx_key)이 지켜지는가.
// JDBC 구현의 같은 계약은 JdbcPointLedgerIT가 실제 PostgreSQL에서 잰다.
class PointLedgerRepositoryTest {

    private final InMemoryPointLedgerRepository ledger = new InMemoryPointLedgerRepository();

    private static PointTransfer transfer(String txKey, String from, String to, int amount) {
        return new PointTransfer(txKey, from, to, amount, PointTxKind.CHALLENGE_ENTRY,
                "challenge", "7", "2026-09-24T00:00:00Z");
    }

    @Test
    void 기표는_두_줄로_남고_합은_0이다() {
        assertThat(ledger.post(transfer("join:7:a", "guest-a", PointAccounts.escrowOfChallenge(7), 100))).isTrue();

        assertThat(ledger.sumOf("guest-a")).isEqualTo(-100);
        assertThat(ledger.sumOf(PointAccounts.escrowOfChallenge(7))).isEqualTo(100);
        // 이 한 줄이 원장의 존재 이유다 — 포인트는 생기지도 사라지지도 않는다.
        assertThat(ledger.totalSum()).isZero();
    }

    @Test
    void 같은_txKey로_다시_기표하면_아무것도_쓰지_않고_false다() {
        ledger.post(transfer("join:7:a", "guest-a", PointAccounts.escrowOfChallenge(7), 100));

        assertThat(ledger.post(transfer("join:7:a", "guest-a", PointAccounts.escrowOfChallenge(7), 100))).isFalse();

        // 재시도가 잔액을 두 번 옮기지 않는다 — 멱등 키가 도메인에서 파생되는 이유.
        assertThat(ledger.sumOf("guest-a")).isEqualTo(-100);
        assertThat(ledger.findByAccount("guest-a", 50)).hasSize(1);
    }

    @Test
    void 금액이_0이하거나_같은_계정끼리면_기표할_수_없다() {
        // 0원 이동은 기록할 것이 없고, 음수는 방향을 뒤집는 우회로가 된다.
        assertThatThrownBy(() -> transfer("x", "guest-a", "guest-b", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer("x", "guest-a", "guest-b", -10))
                .isInstanceOf(IllegalArgumentException.class);
        // 자기 자신에게 보내면 두 줄이 같은 계정에 남아 합계만 부풀고 잔액은 그대로다.
        assertThatThrownBy(() -> transfer("x", "guest-a", "guest-a", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거래_내역은_최신순이고_상한이_적용된다() {
        for (int i = 1; i <= 5; i++) {
            ledger.post(transfer("tx-" + i, "guest-a", "system:x", i * 10));
        }

        List<PointLedgerEntry> recent = ledger.findByAccount("guest-a", 3);

        assertThat(recent).extracting(PointLedgerEntry::txKey).containsExactly("tx-5", "tx-4", "tx-3");
    }

    @Test
    void 계정_이관은_과거_내역을_통째로_옮기고_합계를_보존한다() {
        ledger.post(transfer("signup:guest-a", PointAccounts.ISSUANCE, "guest-a", 1000));
        ledger.post(transfer("join:7:guest-a", "guest-a", PointAccounts.escrowOfChallenge(7), 100));

        ledger.reassignAccount("guest-a", "user-1");

        assertThat(ledger.sumOf("guest-a")).isZero();           // 떠난 계정은 0
        assertThat(ledger.sumOf("user-1")).isEqualTo(900);      // 잔액과 같아야 한다
        assertThat(ledger.findByAccount("user-1", 50)).hasSize(2); // 게스트 시절 내역까지 이어 본다
        assertThat(ledger.totalSum()).isZero();
    }

    @Test
    void 같은_txKey를_동시에_기표해도_정확히_한_번만_기록된다() throws Exception {
        int threads = 32;
        AtomicInteger posted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (ledger.post(transfer("join:7:a", "guest-a", PointAccounts.escrowOfChallenge(7), 100))) {
                            posted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        // "있나 확인 후 넣는다"였다면 여기서 여러 번 통과한다 — 삽입 자체가 판정이라 한 번이다.
        assertThat(posted.get()).isEqualTo(1);
        assertThat(ledger.sumOf("guest-a")).isEqualTo(-100);
    }
}
