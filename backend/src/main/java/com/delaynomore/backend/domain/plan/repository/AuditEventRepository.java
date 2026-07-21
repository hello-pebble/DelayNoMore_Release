package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.AuditEvent;
import org.springframework.stereotype.Repository;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// 계획 변경 이력 저장소 — 계획 보관함과 같은 인메모리(휘발성) 체제. 서버 재시작 시 초기화된다.
// 조회는 (planId, ownerId) 짝으로만 열려 있어 소유자별로 격리된다 — 이벤트에 소유자를 박아 두어
// 계획이 삭제된 뒤에도 소유자가 자신의 이력을 조회할 수 있다.
// 의도적으로 deleteAllByPlanId가 없다 — 감사 이력은 계획 삭제(PLAN_DELETED)를 살아남아야
// "언제 삭제됐는가"에 답할 수 있고, 메모리는 전역 상한(링버퍼)만으로 관리한다.
// 추후 DB 도입 시 내부 구현만 교체할 수 있게 시그니처는 DB 관례(append=INSERT, findAllBy…)를 따른다.
@Repository
public class AuditEventRepository {

    // 공유 데모 저장소 폭주 방지 — MAX_PLANS=50과 같은 취지의 전역 상한(계획당 평균 ~20건 감각).
    static final int MAX_EVENTS = 1000;

    private final ArrayDeque<AuditEvent> store = new ArrayDeque<>(); // 삽입순 = 시간순 링버퍼
    private final AtomicLong idSequence = new AtomicLong(0);

    // synchronized: 추가와 상한 축출을 원자로 묶는다 — 트래픽이 낮은 데모라 가장 단순한 정합 수단을 쓴다.
    public synchronized AuditEvent append(AuditEvent event) {
        AuditEvent saved = event.withId(idSequence.incrementAndGet());
        store.addLast(saved);
        while (store.size() > MAX_EVENTS) {
            store.pollFirst(); // 가장 오래된 것부터 축출
        }
        return saved;
    }

    // (planId, ownerId) 짝으로 조회, id 내림차순(최신 이벤트가 앞) — 남의 계획은 빈 목록이 된다.
    // DB 전환 시 WHERE plan_id = ? AND owner_id = ? ORDER BY id DESC 로 대체된다.
    public synchronized List<AuditEvent> findAllByPlanIdAndOwner(long planId, String ownerId) {
        return store.stream()
                .filter(e -> e.planId() == planId && ownerId.equals(e.ownerId()))
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .toList();
    }

    public synchronized int count() {
        return store.size();
    }
}
