package com.delaynomore.backend.domain.plan.repository;

import com.delaynomore.backend.domain.plan.entity.Reflection;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

// 회고 저장소 계약 — 구현은 프로필로 선택된다:
//   기본(!postgres) = InMemoryReflectionRepository(휘발성, 롤백·단위 테스트용)
//   postgres        = JdbcReflectionRepository(PostgreSQL 영속화, v0.12.0)
// (planId, date)당 1건만 유지(업서트). upsert의 remapper는 원자 구간 안에서 기존 값(없으면 null)을
// 받아 새 값을 만든다 — createdAt 보존은 remapper의 책임이다(저장소는 형식만 제공).
public interface ReflectionRepository {

    Reflection upsert(long planId, String date, UnaryOperator<Reflection> remapper);

    Optional<Reflection> findByPlanIdAndDate(long planId, String date);

    // 날짜 내림차순(최근 회고가 앞).
    List<Reflection> findAllByPlanId(long planId);

    // 계획 삭제 캐스케이드용 — 해당 계획의 회고를 모두 제거한다.
    void deleteAllByPlanId(long planId);
}
