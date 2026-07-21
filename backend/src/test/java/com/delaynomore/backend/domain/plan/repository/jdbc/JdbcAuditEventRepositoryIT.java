package com.delaynomore.backend.domain.plan.repository.jdbc;

import com.delaynomore.backend.domain.plan.entity.AuditEvent;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// JdbcAuditEventRepository 통합 테스트 — id 증가·소유자 스코프·id 내림차순, 계획 삭제 후에도 이력
// 생존, 인메모리와 달리 상한 없이 누적됨.
class JdbcAuditEventRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private PlanRepository planRepository;

    private static AuditEvent event(long planId, String owner, String type) {
        return new AuditEvent(0, planId, owner, type, null, "sess-1", "2026-07-21T10:00:00Z");
    }

    @Test
    void append_assignsIncreasingId_findScopedByOwner_orderedIdDesc() {
        AuditEvent e1 = auditEventRepository.append(event(1L, "guest-1", "PLAN_CREATED"));
        AuditEvent e2 = auditEventRepository.append(event(1L, "guest-1", "TASK_COMPLETED"));
        auditEventRepository.append(event(1L, "guest-2", "PLAN_CREATED")); // 남의 이벤트

        assertThat(e2.id()).isGreaterThan(e1.id());

        List<AuditEvent> mine = auditEventRepository.findAllByPlanIdAndOwner(1L, "guest-1");
        assertThat(mine).extracting(AuditEvent::id).containsExactly(e2.id(), e1.id()); // 최신 먼저
        assertThat(mine).extracting(AuditEvent::type)
                .containsExactly("TASK_COMPLETED", "PLAN_CREATED");
    }

    @Test
    void findByOtherOwner_returnsEmpty() {
        auditEventRepository.append(event(1L, "guest-1", "PLAN_CREATED"));
        assertThat(auditEventRepository.findAllByPlanIdAndOwner(1L, "누군가")).isEmpty();
    }

    @Test
    void auditSurvivesPlanDeletion() {
        Plan plan = planRepository.save(new Plan(null, "guest-1", "삭제될 계획", 1, 1, "초급",
                Map.of(), "DRAFT", null, "2026-07-21", "2026-07-21", "t", 1L));
        auditEventRepository.append(event(plan.id(), "guest-1", "PLAN_CREATED"));
        auditEventRepository.append(event(plan.id(), "guest-1", "PLAN_DELETED"));

        planRepository.deleteById(plan.id(), current -> { });

        // 계획 행은 사라졌지만(FK 없음) 감사 이력은 소유자 스코프로 여전히 조회된다.
        assertThat(planRepository.findById(plan.id())).isEmpty();
        assertThat(auditEventRepository.findAllByPlanIdAndOwner(plan.id(), "guest-1"))
                .extracting(AuditEvent::type)
                .containsExactly("PLAN_DELETED", "PLAN_CREATED");
    }

    @Test
    void accumulatesBeyondInMemoryCap_noEviction() {
        // 인메모리는 MAX_EVENTS=1000에서 축출하지만, JDBC는 누적이 목적이라 상한이 없다.
        for (int i = 0; i < 1050; i++) {
            auditEventRepository.append(event(7L, "guest-1", "TASK_COMPLETED"));
        }
        assertThat(auditEventRepository.count()).isEqualTo(1050);
        assertThat(auditEventRepository.findAllByPlanIdAndOwner(7L, "guest-1")).hasSize(1050);
    }
}
