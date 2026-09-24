package com.delaynomore.backend.domain.points;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.domain.points.service.PlanRewardService;
import com.delaynomore.backend.support.PointsFixture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완주 보상(v0.32.0) — 포인트 경제의 유일한 새 유입 경로. 규칙(100% 여부·금액)과 멱등성을 잰다.
 */
class PlanRewardServiceTest {

    private static final int INITIAL = 1000;
    private static final String OWNER = "guest-rew-0001";

    private final PointsFixture points = new PointsFixture();
    private final PlanRewardService service = points.rewardService();

    private static Plan plan(long id, int duration, int done, int total) {
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            tasks.add(Map.of("id", "t" + i, "content", "할 일", "completed", i < done));
        }
        String now = Instant.now().toString();
        return new Plan(id, OWNER, "정보처리기사", duration, 2, "초급",
                Map.of("2026-09-01", tasks), PlanStatus.COMPLETED.name(), now, now,
                "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증");
    }

    @Test
    void 보상은_기간에_비례하되_하한과_상한이_있다() {
        assertThat(PlanRewardService.rewardFor(plan(1L, 1, 2, 2))).isEqualTo(50);    // 하한
        assertThat(PlanRewardService.rewardFor(plan(1L, 14, 2, 2))).isEqualTo(140);  // 14 × 10
        assertThat(PlanRewardService.rewardFor(plan(1L, 365, 2, 2))).isEqualTo(200); // 상한
    }

    @Test
    void 하나라도_남았으면_보상이_없다() {
        assertThat(PlanRewardService.rewardFor(plan(1L, 14, 1, 2))).isZero();
    }

    @Test
    void 할_일이_없는_계획은_완주가_아니다() {
        // 돌려받는 것(예치 환급)과 달리, 새 포인트를 발행하는 데는 근거가 필요하다.
        assertThat(PlanRewardService.rewardFor(plan(1L, 14, 0, 0))).isZero();
    }

    @Test
    void 지급은_발행_계정에서_나오고_원장_합계는_0이다() {
        service.rewardIfCompleted(plan(7L, 14, 2, 2));

        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL + 140);
        assertThat(points.ledger().sumOf(PointAccounts.ISSUANCE)).isEqualTo(-(INITIAL + 140));
        assertThat(points.ledger().sumOf(OWNER)).isEqualTo(points.wallets().balanceOf(OWNER));
        assertThat(points.ledger().totalSum()).isZero();
        assertThat(points.ledger().findByAccount(OWNER, 10))
                .extracting(e -> e.kind().name())
                .containsExactly(PointTxKind.PLAN_COMPLETION_REWARD.name(), PointTxKind.SIGNUP_BONUS.name());
    }

    @Test
    void 같은_계획은_몇_번_불려도_한_번만_보상된다() {
        Plan completed = plan(7L, 14, 2, 2);

        service.rewardIfCompleted(completed);
        service.rewardIfCompleted(completed);
        service.rewardIfCompleted(completed);

        // 멱등 키가 earn:plan:<id>라 원장이 두 번째부터 no-op으로 흡수한다.
        assertThat(points.ledger().sumOf(OWNER)).isEqualTo(INITIAL + 140);
        // 여기가 핵심이다: 원장만 흡수하고 잔액을 또 늘리면 세 번 보상된 지갑이 남는다.
        // 멱등의 단위는 "잔액 + 기표" 한 쌍이어야 한다.
        assertThat(points.wallets().balanceOf(OWNER)).isEqualTo(INITIAL + 140);
        assertThat(points.ledger().totalSum()).isZero();
    }
}
