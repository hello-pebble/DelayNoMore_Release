package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Plan;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

// 계획 보관함 인메모리 구현 — DB 없이 휘발성으로 보관한다(서버 재시작 시 초기화). postgres 프로필이
// 아닐 때만 활성화되며, JDBC 영속화의 롤백 경로이자 단위 테스트의 실측 저장소로 남는다.
// 원자성은 ConcurrentHashMap의 키 단위 원자 구간(computeIfPresent/compute)으로 얻는다.
@Repository
@Profile("!postgres")
public class InMemoryPlanRepository implements PlanRepository {

    private final ConcurrentHashMap<Long, Plan> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);

    @Override
    public Plan save(Plan plan) {
        Plan saved = plan.withId(idSequence.incrementAndGet());
        store.put(saved.id(), saved);
        return saved;
    }

    // 소유자별 목록, savedAt 내림차순(최근 저장이 앞). 스코프 없는 findAll()은 두지 않는다 —
    // 어떤 조회도 실수로 전체 목록을 내보낼 수 없게. owner가 없는(레거시) 계획은 아무에게도 안 보인다.
    @Override
    public List<Plan> findAllByOwner(String owner) {
        return store.values().stream()
                .filter(p -> owner.equals(p.owner()))
                .sorted(Comparator.comparingLong(Plan::savedAt).reversed()
                        .thenComparing(Comparator.comparingLong(Plan::id).reversed()))
                .toList();
    }

    @Override
    public Optional<Plan> findById(long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public long countByOwner(String owner) {
        return store.values().stream().filter(p -> owner.equals(p.owner())).count();
    }

    // 존재할 때만 통째로 교체(last-write-wins)하고 "교체 전 값"을 돌려준다(없으면 null).
    // computeIfPresent는 키 단위로 원자 실행되므로 교체와 이전 값 캡처 사이에 다른 쓰기가 끼어들 수 없다.
    // currentStateGuard가 예외를 던지면 맵은 변경되지 않은 채 예외가 전파된다(check-then-act 레이스 없음).
    @Override
    public Plan update(Plan plan, Consumer<Plan> currentStateGuard) {
        Plan[] previous = new Plan[1];
        Plan replaced = store.computeIfPresent(plan.id(), (id, current) -> {
            currentStateGuard.accept(current);
            previous[0] = current;
            return plan;
        });
        return replaced == null ? null : previous[0];
    }

    // read-modify-write(예: 이월)를 키 단위 원자 구간에서 실행한다. mutator가 예외를 던지면 맵은
    // 변경되지 않은 채 예외가 전파되고, 현재 상태를 그대로 반환하면 no-op이 된다.
    @Override
    public Plan mutate(long id, UnaryOperator<Plan> mutator) {
        return store.computeIfPresent(id, (key, current) -> mutator.apply(current));
    }

    // 가드를 통과할 때만 원자적으로 제거하고 제거된 값을 돌려준다(없으면 null).
    @Override
    public Plan deleteById(long id, Consumer<Plan> currentStateGuard) {
        Plan[] removed = new Plan[1];
        store.computeIfPresent(id, (key, current) -> {
            currentStateGuard.accept(current);
            removed[0] = current;
            return null;
        });
        return removed[0];
    }

    @Override
    public int count() {
        return store.size();
    }
}
