package com.delaynomore.backend.domain.plan.repository.jdbc;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.JdbcPlanRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// 서버 재시작 후 복원 검증 — 한 저장소 인스턴스로 저장한 뒤, 같은 DataSource 위에 새로 만든
// JdbcPlanRepository(인메모리 상태 없음)로 읽어 데이터가 JVM이 아니라 PostgreSQL에 있음을 증명한다.
// 새 인스턴스는 재시작된 서버의 새 빈을 흉내낸다.
class PersistenceRestartIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void dataWrittenByOneInstanceIsReadableByAFreshInstance() {
        Map<String, Object> tasks = Map.of(
                "2026-07-21", List.of(Map.of("id", "t1", "content", "복원 확인", "completed", true)));
        Plan saved = planRepository.save(new Plan(null, "guest-restart", "재시작 후 복원", 1, 2, "초급",
                tasks, "CONFIRMED", "2026-07-21T09:00:00Z", "2026-07-21", "2026-07-21",
                "2026-07-21T09:00:00Z", 42L));

        // "재시작" — 상태를 전혀 담지 않은 새 저장소 인스턴스(새 빈 상당).
        PlanRepository afterRestart = new JdbcPlanRepository(jdbc, objectMapper);

        Optional<Plan> restored = afterRestart.findById(saved.id());
        assertThat(restored).isPresent();
        assertThat(restored.get().goalName()).isEqualTo("재시작 후 복원");
        assertThat(restored.get().status()).isEqualTo("CONFIRMED");
        assertThat(restored.get().tasks()).containsKey("2026-07-21");
        assertThat(afterRestart.findAllByOwner("guest-restart")).extracting(Plan::id)
                .containsExactly(saved.id());
    }
}
