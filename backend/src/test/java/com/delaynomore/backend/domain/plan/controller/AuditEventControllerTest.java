package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryAuditEventRepository;
import com.delaynomore.backend.domain.plan.service.AuditEventService;
import com.delaynomore.backend.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 변경 이력 조회 헤더 계약 — 누락 400, 유효 헤더는 모르는 planId여도 200 빈 목록(존재 은닉).
class AuditEventControllerTest {

    private static final String VALID_GUEST_ID = "11111111-2222-3333-4444-555555555555";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuditEventService auditEventService = new AuditEventService(new InMemoryAuditEventRepository());
        mvc = MockMvcBuilders.standaloneSetup(new AuditEventController(auditEventService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getAll_헤더없음_400_GUEST_ID_REQUIRED() throws Exception {
        mvc.perform(get("/api/v1/plans/1/audit-events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GUEST_ID_REQUIRED"));
    }

    @Test
    void getAll_유효헤더_모르는planId_200_빈목록() throws Exception {
        mvc.perform(get("/api/v1/plans/999/audit-events").header("X-Guest-Id", VALID_GUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
