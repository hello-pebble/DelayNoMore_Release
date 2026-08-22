package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.InMemoryAuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryReflectionRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.global.time.KstDates;
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
        PlanService service = new PlanService(repository, new InMemoryReflectionRepository(), auditEvents,
                new ChallengeService(new InMemoryChallengeRepository()));
        // 날짜는 오늘(KST)로 동적 생성 — 하드코딩하면 그 날짜가 지나는 순간
        // PAST_TASK_LOCKED 가드(지난 날짜 토글 거부)에 걸려 테스트가 영구 실패한다.
        String today = KstDates.today().toString();
        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put(today, List.of(Map.of("id", "task-1", "content", "Read", "completed", false)));
        Plan saved = repository.save(new Plan(null, "guest-12345678", "Java study", 1, 1, "Beginner", tasks,
                "DRAFT", null, null, today, today, today + "T00:00:00Z", 1L, null));

        PlanResponse response = service.updateTaskCompletion(saved.id(), "task-1", true, "guest-12345678", "session-a");

        List<?> updatedTasks = (List<?>) response.tasks().get(today);
        Map<?, ?> updatedTask = (Map<?, ?>) updatedTasks.getFirst();
        assertThat(updatedTask.get("completed")).isEqualTo(true);
        assertThat(response.progress().done()).isEqualTo(1);
    }
}
