package com.delaynomore.backend.domain.points.service;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.points.dto.PlanDepositResponse;
import com.delaynomore.backend.domain.points.entity.PlanDeposit;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.domain.points.repository.PlanDepositRepository;
import com.delaynomore.backend.domain.points.repository.PointLedgerRepository;
import com.delaynomore.backend.domain.points.repository.PointWalletRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 목표 예치(v0.32.0) — 1인 에스크로. 고정한 계획에 자기 포인트를 걸고, 계획이 종결되는 순간
 * <b>달성률만큼 돌려받고 나머지는 소각</b>한다.
 *
 * <p>챌린지(v0.21.0~)와의 차이: 저쪽은 여럿이 걸고 완주자끼리 나누는 판이고, 이쪽은 혼자 자기
 * 자신과 하는 약속이라 <b>다른 사람의 돈이 걸리지 않는다</b>. 그래서 정원·마감 같은 경쟁 규칙이
 * 없고, 대신 "언제 정산되는가"가 계획의 종결 전이 하나로 고정된다.
 *
 * <p>중복 예치·이중 정산을 여기서 검사하지 않는 이유는 이 저장소의 오랜 관례 그대로다 —
 * 미리 조회해 판정하면 그 사이가 열린다. 판정은 저장소의 PK와 조건부 UPDATE가 단독으로 한다.
 *
 * <p>서비스가 아니라 {@link PlanRepository}를 주입하는 이유: PlanService → 이 서비스 방향이
 * 이미 있어(종결 훅) PlanService를 물면 순환이 된다. 필요한 건 조회뿐이라 저장소로 충분하다
 * (ChallengeService와 같은 선례).
 */
@Service
@RequiredArgsConstructor
public class PlanDepositService {

    /** 예치 금액 범위 — 너무 작으면 약속이 아니고, 초기 잔액(1,000P) 절반을 넘기면 한 판에 건다. */
    static final int MIN_AMOUNT = 10;
    static final int MAX_AMOUNT = 500;

    private final PlanDepositRepository depositRepository;
    private final PlanRepository planRepository;
    private final PointWalletRepository wallets;
    // 소각 기표 전용 — 예치 계정과 소각 계정은 둘 다 시스템 계정이라 지갑 행이 없다(사용자
    // 잔액이 움직이지 않는 이동). 지갑을 거치지 않고 원장에만 남기는 유일한 자리다.
    private final PointLedgerRepository ledger;

    /**
     * 예치 — 고정된 내 계획에 포인트를 건다. 차감과 기표는 지갑 저장소가 한 호출로 하고,
     * 예치 행 등록이 실패하면(이미 있음) 그 차감이 롤백되도록 같은 트랜잭션 안에 둔다.
     *
     * <p>등록을 차감보다 <b>먼저</b> 하는 이유: 중복 예치는 차감 전에 걸러야 인메모리 프로필에서도
     * 되돌릴 것이 없다(트랜잭션이 없는 쪽의 "검사를 변경 앞에" 규칙).
     */
    @Transactional
    public PlanDepositResponse deposit(long planId, String owner, Integer rawAmount) {
        int amount = rawAmount == null ? 0 : rawAmount;
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            throw new BusinessException(ErrorCode.DEPOSIT_AMOUNT_INVALID);
        }
        Plan plan = requireOwnedPlan(planId, owner);
        if (!PlanStatus.fromStored(plan.status()).allowsDeposit()) {
            throw new BusinessException(ErrorCode.DEPOSIT_NOT_ALLOWED);
        }
        // 잔액 검사를 예치 행 등록보다 **앞에** 둔다. JDBC라면 차감 실패가 트랜잭션을 롤백해
        // 등록도 함께 사라지지만, 인메모리 프로필에는 롤백이 없어 "돈은 안 냈는데 예치는 걸린"
        // 행이 남는다 — 그 행은 나중에 정산되면서 낸 적 없는 돈을 돌려준다.
        // (이 저장소의 "검사를 모든 변경 앞에" 규칙 — CONCURRENCY.md 5절.)
        if (wallets.balanceOf(owner) < amount) {
            throw new BusinessException(ErrorCode.POINTS_INSUFFICIENT);
        }
        String now = Instant.now().toString();
        if (!depositRepository.create(new PlanDeposit(planId, owner, amount, now, null, null))) {
            throw new BusinessException(ErrorCode.DEPOSIT_ALREADY_EXISTS);
        }
        wallets.debit(owner, amount, new PointTransfer("deposit:" + planId,
                owner, PointAccounts.escrowOfPlan(planId), amount,
                PointTxKind.PLAN_DEPOSIT, "plan", String.valueOf(planId), now));
        return PlanDepositResponse.of(new PlanDeposit(planId, owner, amount, now, null, null),
                plan, wallets.balanceOf(owner));
    }

    /**
     * 내 예치 조회 — 없으면 404(계획이 남의 것이어도 404, 존재를 숨기는 기존 기준).
     *
     * <p>{@code readOnly = true}가 아닌 이유: 조회지만 {@code balanceOf}가 지갑을 지연 생성하며
     * 발행을 기표할 수 있다(신규 사용자의 첫 조회). 읽기 전용 트랜잭션이면 그 INSERT가
     * PostgreSQL에서 거부된다 — ChallengeService.list·PointLedgerService.myLedger가 같은 이유로
     * 일반 트랜잭션이다.
     */
    @Transactional
    public PlanDepositResponse get(long planId, String owner) {
        Plan plan = requireOwnedPlan(planId, owner);
        PlanDeposit deposit = depositRepository.findByPlanId(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPOSIT_NOT_FOUND));
        return PlanDepositResponse.of(deposit, plan, wallets.balanceOf(owner));
    }

    /**
     * 종결 정산 — 계획이 COMPLETED·CANCELLED로 전이하거나 삭제될 때 호출된다(PlanService 훅).
     * 예치가 없거나 이미 정산됐으면 아무 일도 하지 않는다.
     *
     * <p>정산권 판정은 {@code claimSettlement}의 조건부 UPDATE가 단독으로 한다 — 동시에 두
     * 경로(예: 종결 전이와 삭제)가 들어와도 정확히 하나만 환급을 기표한다.
     *
     * <p>달성률은 <b>정산 시점의 계획</b>에서 서버가 다시 센다(Plan.countAllTasks) — 클라이언트가
     * 보낸 수치를 믿지 않는 기존 관례이고, 지난 날짜 소급 체크는 PAST_TASK_LOCKED가 이미 막고
     * 있어 근거로 쓸 만하다.
     */
    @Transactional
    public void settleOnTerminal(Plan plan) {
        PlanDeposit deposit = depositRepository.findByPlanId(plan.id()).orElse(null);
        if (deposit == null || deposit.settled()) {
            return;
        }
        Plan.TaskCounts counts = plan.countAllTasks();
        int refund = PlanDeposit.refundFor(deposit.amount(), counts.completed(), counts.total());
        String now = Instant.now().toString();
        if (!depositRepository.claimSettlement(plan.id(), now, refund)) {
            return; // 다른 경로가 먼저 정산권을 땄다 — 물러난다
        }
        String escrow = PointAccounts.escrowOfPlan(plan.id());
        if (refund > 0) {
            wallets.credit(deposit.owner(), refund, new PointTransfer("deposit-refund:" + plan.id(),
                    escrow, deposit.owner(), refund,
                    PointTxKind.PLAN_DEPOSIT_REFUND, "plan", String.valueOf(plan.id()), now));
        }
        int forfeited = deposit.amount() - refund;
        if (forfeited > 0) {
            // 소각 계정으로 옮긴다 — 포인트는 사라지지 않고 계정을 바꾼다(원장 합계 0 유지).
            // 이 이동까지 끝나야 escrow:plan:<id>가 정확히 0으로 닫힌다. 양쪽 모두 시스템
            // 계정이라 지갑은 관여하지 않는다(지갑 행을 만들면 불변식 2의 대상이 되어버린다).
            ledger.post(new PointTransfer("deposit-forfeit:" + plan.id(),
                    escrow, PointAccounts.BURN, forfeited,
                    PointTxKind.PLAN_DEPOSIT_FORFEIT, "plan", String.valueOf(plan.id()), now));
        }
    }

    /**
     * 계획 삭제 훅 — 먼저 정산하고(예치금이 예치 계정에 갇히지 않게) 예치 행을 지운다.
     * 순서가 규칙이다: 행을 먼저 지우면 정산할 근거가 사라진다. 원장은 계획이 사라져도 남으므로
     * 그 계정을 0으로 닫아 두는 것이 이 호출의 목적이다.
     */
    @Transactional
    public void onPlanDeleted(Plan plan) {
        settleOnTerminal(plan);
        depositRepository.deleteByPlanId(plan.id());
    }

    private Plan requireOwnedPlan(long planId, String owner) {
        return planRepository.findById(planId)
                .filter(p -> owner.equals(p.owner()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }
}
