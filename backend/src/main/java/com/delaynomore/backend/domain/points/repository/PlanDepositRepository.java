package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.points.entity.PlanDeposit;

import java.util.Optional;

/**
 * 목표 예치 저장소 계약(v0.32.0) — 구현은 프로필로 선택된다(다른 저장소와 같은 관례).
 *
 * <p>두 불변식의 판정 주체가 모두 저장소에 있다:
 * <ul>
 *   <li><b>계획당 예치는 최대 하나</b> — {@code plan_id} PK(인메모리는 맵 키)가 판정한다.
 *       {@link #create}가 false를 돌려주면 이미 있는 것이다(예외 없이 흡수한다).</li>
 *   <li><b>정산은 최대 한 번</b> — {@link #claimSettlement}의 조건부 UPDATE가 판정한다.
 *       true를 받은 호출만 환급·소각을 기표한다(챌린지 정산 claim과 같은 계약).</li>
 * </ul>
 */
public interface PlanDepositRepository {

    /** 예치 등록. 이미 그 계획에 예치가 있으면 아무것도 쓰지 않고 false. */
    boolean create(PlanDeposit deposit);

    Optional<PlanDeposit> findByPlanId(long planId);

    /**
     * 정산권 청구 — "미정산"을 쓰기 안에서 판정한다. true를 받은 호출만 지급을 진행한다.
     * false = 이미 정산됐거나 예치가 없다(no-op으로 물러난다).
     */
    boolean claimSettlement(long planId, String settledAt, int refunded);

    /** 계획 삭제 캐스케이드 — JDBC는 FK CASCADE가 있지만 인메모리 계약을 맞추기 위해 둔다. */
    void deleteByPlanId(long planId);
}
