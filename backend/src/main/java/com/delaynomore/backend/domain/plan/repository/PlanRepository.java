package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Plan;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

// 계획 보관함 저장소 계약 — 구현은 프로필로 선택된다:
//   기본(!postgres) = InMemoryPlanRepository(휘발성, 롤백·단위 테스트용)
//   postgres        = JdbcPlanRepository(PostgreSQL 영속화, v0.12.0)
// 목록 조회는 소유자(게스트 ID)별로만 열려 있다(findAllByOwner) — 스코프 없는 findAll()은 두지
// 않는다. update/mutate/deleteById의 가드·뮤테이터 람다는 "현재 상태를 원자적으로 읽어 검사·변형
// 하는 구간" 안에서 실행된다는 것이 계약이다 — 인메모리는 ConcurrentHashMap.computeIfPresent,
// JDBC는 트랜잭션 + SELECT ... FOR UPDATE가 그 구간을 제공한다. 람다가 예외를 던지면 저장소는
// 변경되지 않은 채 예외가 전파된다(인메모리 = 맵 불변, JDBC = 트랜잭션 롤백).
public interface PlanRepository {

    // 새 계획을 저장하고 저장소가 발급한 ID가 실린 사본을 돌려준다.
    Plan save(Plan plan);

    // 소유자별 목록, savedAt 내림차순(동률은 id 내림차순) — 최근 저장이 앞.
    List<Plan> findAllByOwner(String owner);

    Optional<Plan> findById(long id);

    // 소유자별 보유 개수 — 소유자당 한도(create 가드)용.
    long countByOwner(String owner);

    // 존재할 때만 통째로 교체하고 "교체 전 값"을 돌려준다(없으면 null) — 변경 이력 diff용.
    // currentStateGuard는 원자 구간 안에서 "현재 상태 기준으로 이 교체가 허용되는가"를 검사한다
    // (소유자·고정 계획 가드). 가드는 순수 검사만 해야 한다(부수효과 금지).
    Plan update(Plan plan, Consumer<Plan> currentStateGuard);

    // read-modify-write(예: 이월)를 원자 구간에서 실행한다 — mutator가 현재 상태를 읽어 새 상태를
    // 만든다. 현재 상태를 그대로 반환하면 no-op. 갱신 후 상태를 돌려준다(키 부재 시 null).
    Plan mutate(long id, UnaryOperator<Plan> mutator);

    // 가드를 통과할 때만 원자적으로 제거하고 제거된 값을 돌려준다(없으면 null) — PLAN_DELETED
    // 이력의 detail(goalName)용.
    Plan deleteById(long id, Consumer<Plan> currentStateGuard);

    // 전역 보유 개수 — 전역 상한(create 가드)용.
    int count();
}
