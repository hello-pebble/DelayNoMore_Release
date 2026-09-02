package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.dto.PlanDraftSessionResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.service.PlanService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void 초안이_판정한_카테고리가_계획_저장까지_전달된다() {
        // 카테고리는 초안 생성 LLM 호출이 함께 돌려준다(추가 호출 없음). 그 값이 저장 경로까지
        // 이어지지 않으면 챌린지 조건이 목표명 키워드 폴백으로만 정해진다.
        AiService aiService = mock(AiService.class);
        PlanService planService = mock(PlanService.class);
        when(aiService.createDraft(any())).thenReturn(new AiService.DraftResult(
                Map.of("2026-08-22", List.of("단어 암기")), "어학"));
        PlanDraftSessionService service = new PlanDraftSessionService(aiService, planService);

        String owner = "guest-12345678";
        String id = service.create(owner).sessionId();
        service.accept(id, owner, "토익 900점", "browser-session");
        service.accept(id, owner, "3일", "browser-session");
        service.accept(id, owner, "2시간", "browser-session");
        service.accept(id, owner, "초급", "browser-session");

        ArgumentCaptor<String> category = ArgumentCaptor.forClass(String.class);
        verify(planService).create(any(PlanSaveRequest.class), eq(owner), eq("browser-session"),
                category.capture());
        assertThat(category.getValue()).isEqualTo("어학");
    }
}
