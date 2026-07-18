package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Plan;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// 계획 보관함 저장소 — 아직 DB가 없어 인메모리(휘발성)로 보관한다. 서버 재시작 시 초기화되고
// 사용자 구분 없이 모든 방문자가 공용으로 쓴다(원격 기능 테스트용 데모 체제).
// 추후 로그인+PostgreSQL 도입 시 내부 구현만 JdbcClient로 교체할 수 있게
// 메서드 시그니처는 DB 관례(save/findAll/findById/update/deleteById)를 유지한다.
@Repository
public class PlanRepository {

    private final ConcurrentHashMap<Long, Plan> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);

    public Plan save(Plan plan) {
        Plan saved = plan.withId(idSequence.incrementAndGet());
        store.put(saved.id(), saved);
        return saved;
    }

    // savedAt 내림차순(최근 저장이 앞) — DB 전환 시 ORDER BY savedAt DESC 로 대체된다.
    public List<Plan> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparingLong(Plan::savedAt).reversed()
                        .thenComparing(Comparator.comparingLong(Plan::id).reversed()))
                .toList();
    }

    public Optional<Plan> findById(long id) {
        return Optional.ofNullable(store.get(id));
    }

    // 존재할 때만 통째로 교체(last-write-wins — 공유 데모 저장소라 동시 수정 경합은 허용)하고
    // "교체 전 값"을 돌려준다(없으면 null) — 변경 이력이 이전 상태와 diff하는 데 쓴다.
    // computeIfPresent는 키 단위로 원자 실행되므로 교체와 이전 값 캡처 사이에 다른 쓰기가 끼어들 수 없다.
    public Plan update(Plan plan) {
        Plan[] previous = new Plan[1];
        Plan replaced = store.computeIfPresent(plan.id(), (id, current) -> {
            previous[0] = current;
            return plan;
        });
        return replaced == null ? null : previous[0];
    }

    // 제거된 값을 돌려준다(없으면 null) — 변경 이력의 PLAN_DELETED detail(goalName)에 쓴다.
    public Plan deleteById(long id) {
        return store.remove(id);
    }

    public int count() {
        return store.size();
    }
}
