package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryAuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryReflectionRepository;
import com.delaynomore.backend.domain.plan.service.AuditEventService;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회고 조회 헤더 계약 — 누락 400, 유효 헤더는 계획이 없으면 404(회고는 계획 소유권 상속).
class ReflectionControllerTest {

    private static final String VALID_GUEST_ID = "abcdef01-2345-6789-abcd-ef0123456789";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PlanRepository planRepository = new InMemoryPlanRepository();
        AuditEventService auditEventService = new AuditEventService(new InMemoryAuditEventRepository());
        ReflectionService reflectionService =
                new ReflectionService(planRepository, new InMemoryReflectionRepository(), auditEventService);
        mvc = MockMvcBuilders.standaloneSetup(new ReflectionController(reflectionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getAll_헤더없음_400_GUEST_ID_REQUIRED() throws Exception {
        mvc.perform(get("/api/v1/plans/1/reflections"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GUEST_ID_REQUIRED"));
    }

    @Test
    void getAll_형식위반헤더_400_GUEST_ID_INVALID() throws Exception {
        mvc.perform(get("/api/v1/plans/1/reflections").header("X-Guest-Id", "bad!id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GUEST_ID_INVALID"));
    }

    @Test
    void getAll_유효헤더_없는계획_404_PLAN_NOT_FOUND() throws Exception {
        mvc.perform(get("/api/v1/plans/999/reflections").header("X-Guest-Id", VALID_GUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PLAN_NOT_FOUND"));
    }
}
