package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.InMemoryAuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryReflectionRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanTaskCompletionServiceTest {

    @Test
    void changes_only_the_target_task_without_requiring_a_full_plan_payload() {
        PlanRepository repository = new InMemoryPlanRepository();
        AuditEventService auditEvents = new AuditEventService(new InMemoryAuditEventRepository());
        PlanService service = new PlanService(repository, new InMemoryReflectionRepository(), auditEvents);
        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("2026-08-14", List.of(Map.of("id", "task-1", "content", "Read", "completed", false)));
        Plan saved = repository.save(new Plan(null, "guest-12345678", "Java study", 1, 1, "Beginner", tasks,
                "DRAFT", null, null, "2026-08-14", "2026-08-14", "2026-08-14T00:00:00Z", 1L));

        PlanResponse response = service.updateTaskCompletion(saved.id(), "task-1", true, "guest-12345678", "session-a");

        List<?> updatedTasks = (List<?>) response.tasks().get("2026-08-14");
        Map<?, ?> updatedTask = (Map<?, ?>) updatedTasks.getFirst();
        assertThat(updatedTask.get("completed")).isEqualTo(true);
        assertThat(response.progress().done()).isEqualTo(1);
    }
}
