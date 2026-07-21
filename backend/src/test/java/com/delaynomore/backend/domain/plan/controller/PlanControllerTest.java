package com.delaynomore.backend.domain.plan.controller;

import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryAuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryReflectionRepository;
import com.delaynomore.backend.domain.plan.service.AuditEventService;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.config.WebConfig;
import com.delaynomore.backend.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// X-Guest-Id 헤더 계약 검증 — 누락/형식 위반 시 우리 ApiResponse 형식의 한국어 오류가 나가는지,
// 유효 헤더는 통과하는지를 컨트롤러 계층(standalone MockMvc + GlobalExceptionHandler)에서 확인한다.
class PlanControllerTest {

    private static final String VALID_GUEST_ID = "550e8400-e29b-41d4-a716-446655440000";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PlanRepository planRepository = new InMemoryPlanRepository();
        AuditEventService auditEventService = new AuditEventService(new InMemoryAuditEventRepository());
        PlanService planService = new PlanService(planRepository, new InMemoryReflectionRepository(), auditEventService);
        mvc = MockMvcBuilders.standaloneSetup(new PlanController(planService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new WebConfig.NoStoreInterceptor())
                .build();
    }

    @Test
    void getPlans_헤더없음_400_GUEST_ID_REQUIRED_ApiResponse형식() throws Exception {
        mvc.perform(get("/api/v1/plans"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("GUEST_ID_REQUIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getPlans_형식위반헤더_400_GUEST_ID_INVALID() throws Exception {
        // 한글 + 8자 미만 — 두 조건 모두 규칙 위반
        mvc.perform(get("/api/v1/plans").header("X-Guest-Id", "테스터"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("GUEST_ID_INVALID"));
    }

    @Test
    void getPlans_유효헤더_200_빈배열_no_store헤더() throws Exception {
        mvc.perform(get("/api/v1/plans").header("X-Guest-Id", VALID_GUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                // 개인 데이터라 캐시 금지 — 프록시·브라우저 캐시에 남아 재사용되면 안 된다.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    // CORS 프리플라이트(OPTIONS)는 standalone MockMvc가 지원하지 않아(PreFlight 핸들러 어댑터 없음)
    // 여기서 테스트하지 않는다 — @CrossOrigin(origins="*", 기본 allowedHeaders="*")로 X-Guest-Id가
    // 허용되는지는 QA_CHECKLIST의 curl 프리플라이트 시나리오로 확인한다.

    // 유효한 저장 요청 본문 — @Valid를 통과해야 헤더 해석 단계까지 도달한다.
    private static final String VALID_BODY = """
            {
              "goalName": "토익 900",
              "duration": 3,
              "dailyHours": 2,
              "currentLevel": "완전 초보",
              "tasks": { "2026-07-16": [ { "id": "t-1", "content": "단어 암기", "completed": false } ] },
              "endDate": "2026-07-18",
              "createdAt": "2026-07-16T00:00:00Z"
            }
            """;

    @Test
    void createPlan_유효헤더_200_소유자에게보임() throws Exception {
        mvc.perform(post("/api/v1/plans")
                        .header("X-Guest-Id", VALID_GUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.goalName").value("토익 900"));

        // 같은 게스트 ID로 목록 조회 시 방금 만든 계획이 보인다
        mvc.perform(get("/api/v1/plans").header("X-Guest-Id", VALID_GUEST_ID))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void createPlan_유효본문_헤더없음_400_GUEST_ID_REQUIRED() throws Exception {
        // 본문은 @Valid를 통과하므로, 실패 원인은 헤더 누락이어야 한다(검증이 헤더 해석보다 먼저 도는 순서 확인).
        mvc.perform(post("/api/v1/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GUEST_ID_REQUIRED"));
    }
}
