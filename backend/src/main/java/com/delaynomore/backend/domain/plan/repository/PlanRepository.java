package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Plan;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

// 계획 보관함 저장소 — 아직 DB가 없어 인메모리(휘발성)로 보관한다. 서버 재시작 시 초기화된다.
// 목록 조회는 소유자(닉네임)별로만 열려 있다(findAllByOwner) — 로그인 전 간이 격리.
// 추후 로그인+PostgreSQL 도입 시 내부 구현만 JdbcClient로 교체할 수 있게
// 메서드 시그니처는 DB 관례(save/findAllByOwner/findById/update/deleteById)를 유지한다.
@Repository
public class PlanRepository {

    private final ConcurrentHashMap<Long, Plan> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);

    public Plan save(Plan plan) {
        Plan saved = plan.withId(idSequence.incrementAndGet());
        store.put(saved.id(), saved);
        return saved;
    }

    // 소유자별 목록, savedAt 내림차순(최근 저장이 앞) — DB 전환 시
    // WHERE owner = ? ORDER BY savedAt DESC 로 대체된다. 스코프 없는 findAll()은 두지 않는다 —
    // 어떤 조회도 실수로 전체 목록을 내보낼 수 없게. owner가 없는(레거시) 계획은 아무에게도 안 보인다.
    public List<Plan> findAllByOwner(String owner) {
        return store.values().stream()
                .filter(p -> owner.equals(p.owner()))
                .sorted(Comparator.comparingLong(Plan::savedAt).reversed()
                        .thenComparing(Comparator.comparingLong(Plan::id).reversed()))
                .toList();
    }

    public Optional<Plan> findById(long id) {
        return Optional.ofNullable(store.get(id));
    }

    // 소유자별 보유 개수 — 소유자당 한도(create 가드)용. DB 전환 시 SELECT COUNT(*) WHERE owner = ?.
    public long countByOwner(String owner) {
        return store.values().stream().filter(p -> owner.equals(p.owner())).count();
    }

    // 존재할 때만 통째로 교체(last-write-wins — 공유 데모 저장소라 동시 수정 경합은 허용)하고
    // "교체 전 값"을 돌려준다(없으면 null) — 변경 이력이 이전 상태와 diff하는 데 쓴다.
    // computeIfPresent는 키 단위로 원자 실행되므로 교체와 이전 값 캡처 사이에 다른 쓰기가 끼어들 수 없다.
    // currentStateGuard는 같은 원자 구간 안에서 "현재 상태 기준으로 이 교체가 허용되는가"를 검사한다
    // (고정 계획 가드) — 람다가 예외를 던지면 ConcurrentHashMap 계약상 맵은 변경되지 않은 채
    // 예외가 전파되므로 check-then-act 레이스가 없다. 가드는 순수 검사만 해야 한다(부수효과 금지).
    // DB 전환 시 이 메서드는 "트랜잭션 + SELECT ... FOR UPDATE 후 검사"로 대체된다.
    public Plan update(Plan plan, Consumer<Plan> currentStateGuard) {
        Plan[] previous = new Plan[1];
        Plan replaced = store.computeIfPresent(plan.id(), (id, current) -> {
            currentStateGuard.accept(current);
            previous[0] = current;
            return plan;
        });
        return replaced == null ? null : previous[0];
    }

    // read-modify-write(예: 이월)를 키 단위 원자 구간에서 실행한다 — update()가 "완성된 새 상태"를
    // 받는 것과 달리, mutator가 현재 상태를 읽어 새 상태를 만든다. mutator가 예외를 던지면(가드)
    // ConcurrentHashMap 계약상 맵은 변경되지 않은 채 예외가 전파되고, 현재 상태를 그대로 반환하면
    // no-op이 된다. 갱신 후 상태를 돌려준다(키 부재 시 null).
    // DB 전환 시 이 메서드는 "트랜잭션 + SELECT ... FOR UPDATE 후 갱신"으로 대체된다.
    public Plan mutate(long id, UnaryOperator<Plan> mutator) {
        return store.computeIfPresent(id, (key, current) -> mutator.apply(current));
    }

    // 가드를 통과할 때만 원자적으로 제거하고 제거된 값을 돌려준다(없으면 null) — 변경 이력의
    // PLAN_DELETED detail(goalName)에 쓴다. currentStateGuard는 update()와 같은 관례: 키 단위
    // 원자 구간 안에서 "현재 상태 기준으로 삭제가 허용되는가"(소유자 일치)를 검사하고, 예외를
    // 던지면 맵은 변경되지 않은 채 전파된다. computeIfPresent가 null을 반환하면 매핑이 제거된다.
    public Plan deleteById(long id, Consumer<Plan> currentStateGuard) {
        Plan[] removed = new Plan[1];
        store.computeIfPresent(id, (key, current) -> {
            currentStateGuard.accept(current);
            removed[0] = current;
            return null;
        });
        return removed[0];
    }

    public int count() {
        return store.size();
    }
}
