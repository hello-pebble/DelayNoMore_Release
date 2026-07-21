package com.delaynomore.backend.domain.plan.repository.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

// PostgreSQL 통합 테스트 베이스 — 실제 PG 컨테이너에 Flyway 마이그레이션을 적용하고 postgres
// 프로필(=JDBC 구현체)로 스프링 컨텍스트를 띄운다. Supabase가 PG17이므로 이미지도 17로 맞춘다.
// 컨테이너는 정적 싱글턴으로 한 번만 띄워 하위 테스트 클래스들이 공유한다(테스트마다 재기동 방지).
// Docker가 없는 환경에서는 @Testcontainers(disabledWithoutDocker=true)로 클래스를 통째로 건너뛴다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start(); // 정적 싱글턴 — @DynamicPropertySource가 읽기 전에 기동돼 있어야 한다.
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 각 테스트 사이 격리 — 세 테이블을 비우고 IDENTITY 시퀀스를 초기화한다(감사/계획 id 예측 가능).
    @BeforeEach
    void truncateAll() {
        jdbcTemplate.execute("TRUNCATE plans, reflections, audit_events RESTART IDENTITY CASCADE");
    }
}
