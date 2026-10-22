package com.delaynomore.backend.domain.points.controller;

import com.delaynomore.backend.domain.auth.repository.AuthRepository;
import com.delaynomore.backend.domain.auth.support.SessionToken;
import com.delaynomore.backend.domain.points.service.PlanDepositService;
import com.delaynomore.backend.global.auth.OwnerArgumentResolver;
import com.delaynomore.backend.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanDepositControllerTest {

    private static final String MEMBER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String TOKEN = "member-session-token";

    private PlanDepositService deposits;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        deposits = mock(PlanDepositService.class);
        AuthRepository auth = mock(AuthRepository.class);
        when(auth.findUserIdByToken(SessionToken.hash(TOKEN))).thenReturn(Optional.of(MEMBER_ID));
        mvc = MockMvcBuilders.standaloneSetup(new PlanDepositController(deposits))
                .setCustomArgumentResolvers(new OwnerArgumentResolver(auth))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void 게스트는_회원ID를_보내도_예치를_조회하거나_등록할수없다(String method) throws Exception {
        mvc.perform(depositRequest(method).header("X-Guest-Id", MEMBER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_LOGIN_REQUIRED"));
        verifyNoInteractions(deposits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void 헤더가_없으면_로그인이_필요하다고_안내한다(String method) throws Exception {
        mvc.perform(depositRequest(method))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_LOGIN_REQUIRED"));
        verifyNoInteractions(deposits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void 가짜_인증헤더만으로_로그인_제한을_우회할수없다(String method) throws Exception {
        mvc.perform(depositRequest(method)
                        .header("X-Guest-Id", MEMBER_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Basic invalid"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_LOGIN_REQUIRED"));
        verifyNoInteractions(deposits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void 무효_세션은_게스트로_전환하지_않고_거부한다(String method) throws Exception {
        mvc.perform(depositRequest(method)
                        .header("X-Guest-Id", MEMBER_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
        verifyNoInteractions(deposits);
    }

    @Test
    void 로그인한_회원은_자신의_계정으로_예치를_등록한다() throws Exception {
        mvc.perform(depositRequest("POST")
                        .header("X-Guest-Id", "other-guest-id")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk());
        verify(deposits).deposit(1L, MEMBER_ID, 100);
    }

    @Test
    void 로그인한_회원은_게스트헤더_없이도_예치를_조회한다() throws Exception {
        mvc.perform(depositRequest("GET")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk());
        verify(deposits).get(1L, MEMBER_ID);
    }

    private static MockHttpServletRequestBuilder depositRequest(String method) {
        return request(HttpMethod.valueOf(method), "/api/v1/plans/1/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100}");
    }
}
