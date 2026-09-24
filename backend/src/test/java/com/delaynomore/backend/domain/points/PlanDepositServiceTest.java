package com.delaynomore.backend.domain.points;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.points.dto.PlanDepositResponse;
import com.delaynomore.backend.domain.points.entity.PlanDeposit;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.service.PlanDepositService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.support.PointsFixture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 목표 예치(v0.32.0)의 규칙 검증 — 걸기(상태·금액·중복)와 정산(달성률 비례 환급·소각·멱등).
 *
 * <p>모든 단언 끝에 원장 불변식을 다시 확인한다: 예치는 돈을 움직이는 새 경로이므로,
 * "계정별 합계 = 잔액"과 "전체 합계 = 0"이 여기서도 성립해야 v0.31.0의 보증이 유지된다.
 */
class PlanDepositServiceTest {

    private static final int INITIAL = 1000;
    private static final String OWNER = "guest-dep-0001";

    private final PointsFixture points = new PointsFixture();
    private final InMemoryPlanRepository planRepository = new InMemoryPlanRepository();
    private final PlanDepositService service = points.depositService(planRepository);

    /** 할 일 total개 중 done개가 완료된 계획. */
    private long plan(PlanStatus status, int done, int total) {
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            tasks.add(Map.of("id", "t" + i, "content", "할 일 " + i, "completed", i < done));
        }
        String now = Instant.now().toString();
        return planRepository.save(new Plan(null, OWNER, "정보처리기사", 14, 2, "초급",
                Map.of("2026-09-01", tasks), status.name(), now, null,
                "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증")).id();
    }

    private void assertInvariants() {
        assertThat(points.ledger().sumOf(OWNER))
                .as("계정별 원장 합계 = 지갑 잔액")
                .isEqualTo(points.wallets().balanceOf(OWNER));
        assertThat(points.ledger().totalSum()).as("전체 원장 합계 = 0").isZero();
    }

    // === 걸기 ===

    @Test
    void 예치하면_잔액이_줄고_예치_계정으로_들어간다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);

        PlanDepositResponse response = service.deposit(id, OWNER, 200);

        assertThat(response.amount()).isEqualTo(200);
        assertThat(response.balance()).isEqualTo(INITIAL - 200);
        assertThat(points.ledger().sumOf(PointAccounts.escrowOfPlan(id))).isEqualTo(200);
        assertInvariants();
    }

    @Test
    void 고정되지_않은_계획에는_걸_수_없다() {
        long draft = plan(PlanStatus.DRAFT, 0, 4);

        assertThatThrownBy(() -> service.deposit(draft, OWNER, 100))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPOSIT_NOT_ALLOWED);

        // 거절당했으면 돈도 움직이지 않는다.
        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL);
        assertInvariants();
    }

    @Test
    void 계획당_한_번만_걸_수_있고_두_번째는_차감되지_않는다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);
        service.deposit(id, OWNER, 100);

        assertThatThrownBy(() -> service.deposit(id, OWNER, 100))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPOSIT_ALREADY_EXISTS);

        // 중복 검사가 차감보다 앞에 있으므로 되돌릴 것이 없다(인메모리에는 롤백이 없다).
        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL - 100);
        assertInvariants();
    }

    @Test
    void 금액이_범위_밖이면_거절한다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);

        assertThatThrownBy(() -> service.deposit(id, OWNER, 9))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPOSIT_AMOUNT_INVALID);
        assertThatThrownBy(() -> service.deposit(id, OWNER, 501))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPOSIT_AMOUNT_INVALID);
    }

    @Test
    void 남의_계획에는_걸_수_없고_존재도_숨긴다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);

        assertThatThrownBy(() -> service.deposit(id, "guest-other-01", 100))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void 잔액보다_큰_금액은_걸_수_없다() {
        long first = plan(PlanStatus.CONFIRMED, 0, 4);
        long second = plan(PlanStatus.CONFIRMED, 0, 4);
        for (int i = 0; i < 2; i++) { // 500 × 2 = 1000 소진
            service.deposit(i == 0 ? first : second, OWNER, 500);
        }
        long third = plan(PlanStatus.CONFIRMED, 0, 4);

        assertThatThrownBy(() -> service.deposit(third, OWNER, 100))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.POINTS_INSUFFICIENT);

        // 거절당한 계획에 예치 행이 남으면 안 된다 — 남으면 나중에 정산되면서 낸 적 없는 돈이
        // 환급된다(인메모리에는 롤백이 없으므로 검사가 등록보다 앞에 있어야 한다).
        assertThatThrownBy(() -> service.get(third, OWNER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPOSIT_NOT_FOUND);
        service.settleOnTerminal(planRepository.findById(third).orElseThrow());
        assertInvariants();
    }

    // === 정산 ===

    @Test
    void 종결_시_달성률만큼_돌려받고_나머지는_소각된다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);
        service.deposit(id, OWNER, 200);
        Plan progressed = planRepository.findById(id).orElseThrow();
        // 4개 중 3개 완료 상태로 종결 — 200 × 3/4 = 150 환급, 50 소각.
        Plan atTerminal = withCompleted(progressed, 3);

        service.settleOnTerminal(atTerminal);

        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL - 200 + 150);
        assertThat(points.ledger().sumOf(PointAccounts.BURN)).isEqualTo(50);
        // 환급과 소각이 모두 빠져나가 예치 계정은 정확히 0으로 닫힌다.
        assertThat(points.ledger().sumOf(PointAccounts.escrowOfPlan(id))).isZero();
        assertInvariants();
    }

    @Test
    void 완주하면_전액_환급되고_소각이_없다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);
        service.deposit(id, OWNER, 200);
        Plan done = withCompleted(planRepository.findById(id).orElseThrow(), 4);

        service.settleOnTerminal(done);

        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL); // 내림이 손해를 만들지 않는다
        assertThat(points.ledger().sumOf(PointAccounts.BURN)).isZero();
        assertInvariants();
    }

    @Test
    void 하나도_못_했으면_전액_소각된다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);
        service.deposit(id, OWNER, 200);

        service.settleOnTerminal(planRepository.findById(id).orElseThrow());

        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL - 200);
        assertThat(points.ledger().sumOf(PointAccounts.BURN)).isEqualTo(200);
        assertInvariants();
    }

    @Test
    void 정산은_두_번_일어나지_않는다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);
        service.deposit(id, OWNER, 200);
        Plan done = withCompleted(planRepository.findById(id).orElseThrow(), 4);

        service.settleOnTerminal(done);
        service.settleOnTerminal(done); // 종결 전이와 삭제가 겹치는 경로를 흉내
        service.onPlanDeleted(done);

        // 두 번 환급됐다면 잔액이 1200이 된다 — 판정이 조건부 UPDATE(claim) 안에 있어야 한다.
        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL);
        // 소각도 한 번뿐이어야 한다(완주라 0이지만, 중복 기표가 있었다면 예치 계정이 안 닫힌다).
        assertThat(points.ledger().sumOf(PointAccounts.escrowOfPlan(id))).isZero();
        assertInvariants();
    }

    @Test
    void 계획_삭제_시에도_정산된다_예치금이_갇히지_않게() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);
        service.deposit(id, OWNER, 200);
        Plan half = withCompleted(planRepository.findById(id).orElseThrow(), 2);

        service.onPlanDeleted(half);

        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL - 200 + 100);
        // 예치 계정이 0으로 닫혀야 한다 — 닫히지 않으면 그 포인트는 회수 경로가 없다.
        assertThat(points.ledger().sumOf(PointAccounts.escrowOfPlan(id))).isZero();
        assertThat(points.ledger().sumOf(PointAccounts.BURN)).isEqualTo(100);
        assertInvariants();
    }

    @Test
    void 예치가_없는_계획의_종결은_아무_일도_하지_않는다() {
        long id = plan(PlanStatus.CONFIRMED, 2, 4);

        service.settleOnTerminal(planRepository.findById(id).orElseThrow());

        assertThat(points.ledger().findByAccount(OWNER, 50)).isEmpty(); // 지갑조차 만들지 않는다
        assertThat(points.ledger().totalSum()).isZero();
    }

    @Test
    void 조회는_지금_종결하면_받을_금액을_함께_알려준다() {
        long id = plan(PlanStatus.CONFIRMED, 0, 4);
        service.deposit(id, OWNER, 200);
        planRepository.mutate(id, current -> withCompleted(current, 1));

        PlanDepositResponse response = service.get(id, OWNER);

        assertThat(response.donePercent()).isEqualTo(25);
        assertThat(response.projectedRefund()).isEqualTo(50); // 200 × 1/4
        assertThat(response.refunded()).isNull();             // 아직 정산 전
        assertThat(response.settledAt()).isNull();
    }

    @Test
    void 환급_규칙은_내림이라_예치금을_넘지_않는다() {
        // 3으로 나눠떨어지지 않는 금액 — 100 × 2/3 = 66(내림), 34 소각.
        assertThat(PlanDeposit.refundFor(100, 2, 3)).isEqualTo(66);
        assertThat(PlanDeposit.refundFor(100, 3, 3)).isEqualTo(100);
        assertThat(PlanDeposit.refundFor(100, 0, 3)).isZero();
        // 할 일이 없는 계획은 약속의 대상이 없었으므로 전액 환급.
        assertThat(PlanDeposit.refundFor(100, 0, 0)).isEqualTo(100);
    }

    /** 첫 날짜의 앞 done개를 완료로 바꾼 사본. */
    private static Plan withCompleted(Plan plan, int done) {
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> current = (List<Map<String, Object>>) plan.tasks().get("2026-09-01");
        for (int i = 0; i < current.size(); i++) {
            Map<String, Object> task = new java.util.LinkedHashMap<>(current.get(i));
            task.put("completed", i < done);
            tasks.add(task);
        }
        return new Plan(plan.id(), plan.owner(), plan.goalName(), plan.duration(), plan.dailyHours(),
                plan.currentLevel(), Map.of("2026-09-01", tasks), plan.status(), plan.confirmedAt(),
                plan.completedAt(), plan.startDate(), plan.endDate(), plan.createdAt(),
                System.currentTimeMillis(), plan.category());
    }
}
