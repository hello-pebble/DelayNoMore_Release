package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PlanDeposit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 목표 예치 인메모리 구현 — 휘발성(재시작 시 초기화). JDBC의 롤백 경로이자 단위 테스트의 저장소다.
 *
 * <p>두 판정을 맵의 원자 연산으로 얻는다: 중복 예치는 {@code putIfAbsent}(null 반환 = 획득),
 * 이중 정산은 {@code computeIfPresent} 안의 검사+교체가 키 단위로 직렬화되는 성질이다.
 */
@Repository
@Profile("!postgres")
public class InMemoryPlanDepositRepository implements PlanDepositRepository {

    private final ConcurrentHashMap<Long, PlanDeposit> deposits = new ConcurrentHashMap<>();

    @Override
    public boolean create(PlanDeposit deposit) {
        return deposits.putIfAbsent(deposit.planId(), deposit) == null;
    }

    @Override
    public Optional<PlanDeposit> findByPlanId(long planId) {
        return Optional.ofNullable(deposits.get(planId));
    }

    @Override
    public boolean claimSettlement(long planId, String settledAt, int refunded) {
        AtomicBoolean claimed = new AtomicBoolean(false);
        deposits.computeIfPresent(planId, (id, current) -> {
            if (current.settled()) {
                return current; // 이미 정산됨 — 물러난다
            }
            claimed.set(true);
            return new PlanDeposit(current.planId(), current.owner(), current.amount(),
                    current.createdAt(), settledAt, refunded);
        });
        return claimed.get();
    }

    @Override
    public void deleteByPlanId(long planId) {
        deposits.remove(planId);
    }
}
