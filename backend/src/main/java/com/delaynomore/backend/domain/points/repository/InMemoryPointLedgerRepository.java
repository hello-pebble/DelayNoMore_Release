package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PointLedgerEntry;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 원장 인메모리 구현 — DB 없이 휘발성으로 보관한다(재시작 시 초기화). JDBC의 롤백 경로이자
 * 단위·동시성 테스트의 실측 저장소다.
 *
 * <p>멱등 판정은 JDBC의 UNIQUE 인덱스에 대응해 {@code postedKeys}의 원자 삽입
 * ({@link java.util.Set#add})이 단독으로 한다 — "있나 확인 후 넣는다"가 아니라 "넣어 보고
 * 실패하면 이미 있는 것"이다. 같은 키의 동시 기표에서도 정확히 한 번만 기록된다.
 */
@Repository
@Profile("!postgres")
public class InMemoryPointLedgerRepository implements PointLedgerRepository {

    private final List<PointLedgerEntry> entries = new ArrayList<>();
    private final java.util.Set<String> postedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong idSequence = new AtomicLong(0);

    @Override
    public boolean post(PointTransfer transfer) {
        if (!postedKeys.add(transfer.txKey())) {
            return false; // 이미 기표된 거래 — 조용히 물러난다(재시도 흡수)
        }
        // 두 줄은 반드시 함께 들어간다. 리스트 갱신을 동기화하는 이유는 순서(= id 순)가 거래
        // 내역의 정렬 근거이고, 한 거래의 두 줄이 갈라지면 안 되기 때문이다.
        synchronized (entries) {
            entries.add(line(transfer, transfer.from(), -transfer.amount()));
            entries.add(line(transfer, transfer.to(), transfer.amount()));
        }
        return true;
    }

    private PointLedgerEntry line(PointTransfer transfer, String account, int amount) {
        return new PointLedgerEntry(idSequence.incrementAndGet(), transfer.txKey(), account, amount,
                transfer.kind(), transfer.refType(), transfer.refId(), transfer.createdAt());
    }

    @Override
    public List<PointLedgerEntry> findByAccount(String account, int limit) {
        synchronized (entries) {
            return entries.stream()
                    .filter(e -> e.account().equals(account))
                    .sorted(Comparator.comparingLong(PointLedgerEntry::id).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public int sumOf(String account) {
        synchronized (entries) {
            return entries.stream().filter(e -> e.account().equals(account)).mapToInt(PointLedgerEntry::amount).sum();
        }
    }

    @Override
    public int totalSum() {
        synchronized (entries) {
            return entries.stream().mapToInt(PointLedgerEntry::amount).sum();
        }
    }

    @Override
    public void reassignAccount(String fromAccount, String toAccount) {
        synchronized (entries) {
            entries.replaceAll(e -> e.account().equals(fromAccount)
                    ? new PointLedgerEntry(e.id(), e.txKey(), toAccount, e.amount(), e.kind(),
                            e.refType(), e.refId(), e.createdAt())
                    : e);
        }
    }
}
