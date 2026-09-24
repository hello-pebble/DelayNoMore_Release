package com.delaynomore.backend.domain.points.repository;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.jdbc.AbstractPostgresIntegrationTest;
import com.delaynomore.backend.domain.points.entity.PlanDeposit;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.service.PlanDepositService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목표 예치 JDBC 통합 테스트 — V13 스키마와 두 판정(계획당 1건 PK · 정산 1회 조건부 UPDATE)이
 * 실제 PostgreSQL에서 동작하는지, 그리고 예치·정산을 거친 뒤에도 원장 불변식이 성립하는지.
 * Docker가 없으면 클래스가 통째로 스킵된다(AbstractPostgresIntegrationTest).
 */
class JdbcPlanDepositIT extends AbstractPostgresIntegrationTest {

    private static final String OWNER = "guest-dep-it-01";

    @Autowired
    private PlanDepositRepository depositRepository;

    @Autowired
    private PlanDepositService depositService;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PointLedgerRepository ledger;

    @Autowired
    private PointWalletRepository wallets;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private long confirmedPlan(int done, int total) {
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            tasks.add(Map.of("id", "t" + i, "content", "할 일", "completed", i < done));
        }
        String now = Instant.now().toString();
        return planRepository.save(new Plan(null, OWNER, "정보처리기사", 14, 2, "초급",
                Map.of("2026-09-01", tasks), "CONFIRMED", now, null,
                "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증")).id();
    }

    @Test
    void 계획당_예치는_PK가_막는다() {
        long planId = confirmedPlan(0, 4);
        String now = Instant.now().toString();

        assertThat(depositRepository.create(new PlanDeposit(planId, OWNER, 100, now, null, null))).isTrue();
        assertThat(depositRepository.create(new PlanDeposit(planId, OWNER, 300, now, null, null))).isFalse();

        Integer rows = jdbc.queryForObject("SELECT count(*) FROM plan_deposits WHERE plan_id = :id",
                new MapSqlParameterSource("id", planId), Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void 정산권은_한_호출만_얻는다() {
        long planId = confirmedPlan(0, 4);
        depositRepository.create(new PlanDeposit(planId, OWNER, 100, Instant.now().toString(), null, null));

        assertThat(depositRepository.claimSettlement(planId, Instant.now().toString(), 50)).isTrue();
        assertThat(depositRepository.claimSettlement(planId, Instant.now().toString(), 50)).isFalse();

        assertThat(depositRepository.findByPlanId(planId).orElseThrow().refunded()).isEqualTo(50);
    }

    @Test
    void 예치와_정산_뒤에도_불변식이_성립한다() {
        long planId = confirmedPlan(0, 4);
        depositService.deposit(planId, OWNER, 200);
        assertThat(wallets.balanceOf(OWNER)).isEqualTo(800);
        assertThat(ledger.sumOf(PointAccounts.escrowOfPlan(planId))).isEqualTo(200);

        // 4개 중 3개 완료 상태로 종결 — 150 환급, 50 소각.
        Plan atTerminal = planRepository.findById(planId).orElseThrow();
        planRepository.mutate(planId, current -> completedCopy(current, 3));
        depositService.settleOnTerminal(planRepository.findById(planId).orElseThrow());

        assertThat(wallets.balanceOf(OWNER)).isEqualTo(950);
        assertThat(ledger.sumOf(OWNER)).isEqualTo(950);            // 불변식 2
        assertThat(ledger.sumOf(PointAccounts.BURN)).isEqualTo(50);
        assertThat(ledger.sumOf(PointAccounts.escrowOfPlan(planId))).isZero(); // 예치 계정이 닫힌다
        assertThat(ledger.totalSum()).isZero();                    // 불변식 1
        assertThat(atTerminal.id()).isEqualTo(planId);
    }

    private static Plan completedCopy(Plan plan, int done) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> current = (List<Map<String, Object>>) plan.tasks().get("2026-09-01");
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();
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
