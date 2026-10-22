package com.delaynomore.backend.domain.points.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V14(개인 목표 예치 제거)가 미정산 예치를 전액 환급하고 테이블을 지우는지 — V13까지 적용한
 * 상태에 예치를 심어 두고 V14를 적용해 확인한다. 스프링 컨텍스트가 이미 최신 스키마로 뜨는
 * 베이스 클래스로는 "이전 스키마의 데이터"를 만들 수 없어 Flyway를 직접 단계적으로 돌린다.
 */
@Testcontainers(disabledWithoutDocker = true)
class V14DropPlanDepositsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void 미정산_예치는_전액_환급되고_원장_불변식이_유지되며_테이블이_사라진다() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).target("13").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 사용자 A: 예치 2건(미정산) — 잔액은 두 건을 합쳐 한 번에 올라야 한다. 사용자 B: 정산 완료 1건.
        jdbc.update("INSERT INTO point_wallets (owner, balance) VALUES ('guest-a-0001', 700), ('guest-b-0001', 900)");
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO plans (owner, goal_name, status, saved_at) VALUES (?, 'g', 'CONFIRMED', 1)",
                    i <= 2 ? "guest-a-0001" : "guest-b-0001");
        }
        jdbc.update("INSERT INTO plan_deposits (plan_id, owner, amount, created_at) VALUES "
                + "(1, 'guest-a-0001', 100, 't'), (2, 'guest-a-0001', 200, 't')");
        jdbc.update("INSERT INTO plan_deposits (plan_id, owner, amount, created_at, settled_at, refunded) "
                + "VALUES (3, 'guest-b-0001', 100, 't', 't', 100)");

        Flyway.configure().dataSource(ds).load().migrate();

        assertThat(jdbc.queryForObject("SELECT balance FROM point_wallets WHERE owner = 'guest-a-0001'", Integer.class))
                .isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT balance FROM point_wallets WHERE owner = 'guest-b-0001'", Integer.class))
                .isEqualTo(900); // 이미 정산된 예치는 건드리지 않는다
        // 복식: 환급 2건 × 두 줄, 합계 0, 소유자 원장 합계 = 환급분
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE kind = 'PLAN_DEPOSIT_REFUND'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM point_ledger WHERE kind = 'PLAN_DEPOSIT_REFUND'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM point_ledger WHERE account = 'guest-a-0001'", Integer.class))
                .isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT to_regclass('plan_deposits')", String.class)).isNull();
    }
}
