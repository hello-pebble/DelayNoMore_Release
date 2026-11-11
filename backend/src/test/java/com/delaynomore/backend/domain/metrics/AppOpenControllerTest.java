package com.delaynomore.backend.domain.metrics;

import com.delaynomore.backend.domain.auth.repository.InMemoryAuthRepository;
import com.delaynomore.backend.global.auth.OwnerArgumentResolver;
import com.delaynomore.backend.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 앱 오픈 기록 계약(v0.37.0) — 방문당 1행(멱등), 방문 ID 없으면 400, 소유자 스코프(X-Guest-Id) 필수.
class AppOpenControllerTest {

    private static final String GUEST = "550e8400-e29b-41d4-a716-446655440000";

    private final InMemoryAppOpenRepository repository = new InMemoryAppOpenRepository();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AppOpenController(repository))
                .setCustomArgumentResolvers(new OwnerArgumentResolver(new InMemoryAuthRepository()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 같은_방문ID로_두_번_호출해도_1행이다() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/app-opens").header("X-Guest-Id", GUEST).header("X-Visit-Id", "visit-0001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 다른_방문ID는_각각_기록된다() throws Exception {
        mvc.perform(post("/api/v1/app-opens").header("X-Guest-Id", GUEST).header("X-Visit-Id", "visit-0001"));
        mvc.perform(post("/api/v1/app-opens").header("X-Guest-Id", GUEST).header("X-Visit-Id", "visit-0002"));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void 방문ID가_없으면_400_VISIT_ID_REQUIRED_이고_기록하지_않는다() throws Exception {
        mvc.perform(post("/api/v1/app-opens").header("X-Guest-Id", GUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VISIT_ID_REQUIRED"));
        assertThat(repository.count()).isZero();
    }

    @Test
    void 게스트ID가_없으면_400_GUEST_ID_REQUIRED() throws Exception {
        mvc.perform(post("/api/v1/app-opens").header("X-Visit-Id", "visit-0001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GUEST_ID_REQUIRED"));
    }
}
