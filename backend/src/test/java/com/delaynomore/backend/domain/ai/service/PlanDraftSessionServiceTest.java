package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.dto.PlanDraftSessionResponse;
import com.delaynomore.backend.domain.plan.service.PlanService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlanDraftSessionServiceTest {

    @Test
    void server_owns_question_order_and_rejects_invalid_duration_without_advancing() {
        PlanDraftSessionService service = new PlanDraftSessionService(mock(AiService.class), mock(PlanService.class));

        PlanDraftSessionResponse started = service.create("guest-12345678");
        PlanDraftSessionResponse goalSaved = service.accept(started.sessionId(), "guest-12345678", "Spring Boot 학습", "browser-session");
        PlanDraftSessionResponse invalidDuration = service.accept(started.sessionId(), "guest-12345678", "20일", "browser-session");

        assertThat(started.nextInput()).isEqualTo("goalName");
        assertThat(goalSaved.nextInput()).isEqualTo("duration");
        assertThat(goalSaved.slots()).containsEntry("goalName", "Spring Boot 학습");
        assertThat(invalidDuration.nextInput()).isEqualTo("duration");
        assertThat(invalidDuration.slots()).containsEntry("duration", 0);
    }
}
