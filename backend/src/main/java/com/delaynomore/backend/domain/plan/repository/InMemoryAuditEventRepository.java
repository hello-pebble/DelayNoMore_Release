package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.AuditEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// 계획 변경 이력 인메모리 구현 — 휘발성(서버 재시작 시 초기화). postgres 프로필이 아닐 때만
// 활성화된다. 메모리 폭주는 전역 상한(링버퍼)만으로 관리한다 — JDBC 구현은 누적이 목적이라
// 상한을 두지 않는다(보존 정리는 추후 배치, v0.12.0 범위 제외).
@Repository
@Profile("!postgres")
public class InMemoryAuditEventRepository implements AuditEventRepository {

    // 공유 데모 저장소 폭주 방지 — 전역 상한(계획당 평균 ~20건 감각).
    static final int MAX_EVENTS = 1000;

    private final ArrayDeque<AuditEvent> store = new ArrayDeque<>(); // 삽입순 = 시간순 링버퍼
    private final AtomicLong idSequence = new AtomicLong(0);

    // synchronized: 추가와 상한 축출을 원자로 묶는다 — 트래픽이 낮은 데모라 가장 단순한 정합 수단을 쓴다.
    @Override
    public synchronized AuditEvent append(AuditEvent event) {
        AuditEvent saved = event.withId(idSequence.incrementAndGet());
        store.addLast(saved);
        while (store.size() > MAX_EVENTS) {
            store.pollFirst(); // 가장 오래된 것부터 축출
        }
        return saved;
    }

    @Override
    public synchronized List<AuditEvent> findAllByPlanIdAndOwner(long planId, String ownerId) {
        return store.stream()
                .filter(e -> e.planId() == planId && ownerId.equals(e.ownerId()))
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .toList();
    }

    @Override
    public synchronized int count() {
        return store.size();
    }
}
