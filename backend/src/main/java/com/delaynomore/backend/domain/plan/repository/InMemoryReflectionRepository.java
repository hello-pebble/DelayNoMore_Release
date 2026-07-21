package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Reflection;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

// 회고 인메모리 구현 — 계획 보관함과 같은 휘발성 체제(서버 재시작 시 초기화). postgres 프로필이
// 아닐 때만 활성화된다. (planId, date)당 1건만 유지하므로 키를 "planId:date" 복합 문자열로 쓴다.
@Repository
@Profile("!postgres")
public class InMemoryReflectionRepository implements ReflectionRepository {

    private final ConcurrentHashMap<String, Reflection> store = new ConcurrentHashMap<>();

    private static String key(long planId, String date) {
        return planId + ":" + date;
    }

    // 같은 (planId, date)에 대한 동시 저장이 겹쳐도 compute 한 번으로 원자적으로 처리된다 —
    // 기존 건이 있으면 remapper가 기존 값을 받아 createdAt을 보존하고, 없으면 새로 만든다.
    @Override
    public Reflection upsert(long planId, String date, UnaryOperator<Reflection> remapper) {
        return store.compute(key(planId, date), (k, existing) -> remapper.apply(existing));
    }

    @Override
    public Optional<Reflection> findByPlanIdAndDate(long planId, String date) {
        return Optional.ofNullable(store.get(key(planId, date)));
    }

    @Override
    public List<Reflection> findAllByPlanId(long planId) {
        return store.values().stream()
                .filter(r -> r.planId() == planId)
                .sorted(Comparator.comparing(Reflection::date).reversed())
                .toList();
    }

    @Override
    public void deleteAllByPlanId(long planId) {
        store.entrySet().removeIf(e -> e.getValue().planId() == planId);
    }
}
