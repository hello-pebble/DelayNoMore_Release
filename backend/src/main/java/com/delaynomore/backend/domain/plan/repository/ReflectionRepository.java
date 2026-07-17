package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Reflection;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

// 회고 저장소 — 계획 보관함과 같은 인메모리(휘발성) 체제. 서버 재시작 시 초기화되고
// 사용자 구분 없이 모든 방문자가 공용으로 쓴다(원격 기능 테스트용 데모 체제).
// (planId, date)당 1건만 유지하므로 키를 "planId:date" 복합 문자열로 쓴다.
// 추후 로그인+PostgreSQL 도입 시 내부 구현만 JdbcClient(UPSERT)로 교체할 수 있게
// 메서드 시그니처는 DB 관례(upsert/findBy…/deleteAllBy…)를 유지한다.
@Repository
public class ReflectionRepository {

    private final ConcurrentHashMap<String, Reflection> store = new ConcurrentHashMap<>();

    private static String key(long planId, String date) {
        return planId + ":" + date;
    }

    // 같은 (planId, date)에 대한 동시 저장이 겹쳐도 compute 한 번으로 원자적으로 처리된다 —
    // 기존 건이 있으면 remapper가 기존 값을 받아 createdAt을 보존하고, 없으면 새로 만든다.
    public Reflection upsert(long planId, String date, UnaryOperator<Reflection> remapper) {
        return store.compute(key(planId, date), (k, existing) -> remapper.apply(existing));
    }

    public Optional<Reflection> findByPlanIdAndDate(long planId, String date) {
        return Optional.ofNullable(store.get(key(planId, date)));
    }

    // 날짜 내림차순(최근 회고가 앞) — DB 전환 시 ORDER BY date DESC 로 대체된다.
    public List<Reflection> findAllByPlanId(long planId) {
        return store.values().stream()
                .filter(r -> r.planId() == planId)
                .sorted(Comparator.comparing(Reflection::date).reversed())
                .toList();
    }

    // 계획 삭제 캐스케이드용 — 해당 계획의 회고를 모두 제거한다.
    public void deleteAllByPlanId(long planId) {
        store.entrySet().removeIf(e -> e.getValue().planId() == planId);
    }
}
