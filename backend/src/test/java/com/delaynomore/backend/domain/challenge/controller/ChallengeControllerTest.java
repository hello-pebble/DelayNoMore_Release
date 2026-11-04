package com.delaynomore.backend.domain.challenge.controller;

import com.delaynomore.backend.domain.auth.repository.InMemoryAuthRepository;
import com.delaynomore.backend.domain.auth.support.SessionToken;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.global.auth.OwnerArgumentResolver;
import com.delaynomore.backend.global.error.GlobalExceptionHandler;
import com.delaynomore.backend.support.PointsFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 참가 엔드포인트의 로그인 전용 계약(v0.36.0) — 게스트(X-Guest-Id만)는 403으로 거부되고,
// 그때 참가 행·인원·잔액이 전혀 변하지 않는지를 컨트롤러 계층(standalone MockMvc + 실제
// 서비스/인메모리 저장소)에서 확인한다. 정원·중복·잔액 규칙은 ChallengeServiceTest가 맡는다.
class ChallengeControllerTest {

    private static final int INITIAL_BALANCE = 1000;
    private static final String GUEST = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CONDITION = "자격증:14";

    private final PointsFixture points = new PointsFixture();
    private final ChallengeRepository challengeRepository = new InMemoryChallengeRepository(points.wallets());
    private final InMemoryPlanRepository planRepository = new InMemoryPlanRepository();
    private final InMemoryAuthRepository authRepository = new InMemoryAuthRepository();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChallengeService service = new ChallengeService(challengeRepository, planRepository);
        mvc = MockMvcBuilders.standaloneSetup(new ChallengeController(service))
                .setCustomArgumentResolvers(new OwnerArgumentResolver(authRepository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private long open() {
        return challengeRepository.save(new Challenge(null, "host-0001", "자격증 공부 14일", 14, 5,
                100, 0, Instant.now().toString(), CONDITION, null, null)).id();
    }

    // 참가 자격이 되는 고정 계획(ChallengeServiceTest와 같은 픽스처).
    private void confirmedPlan(String owner) {
        Map<String, Object> tasks = Map.of(
                "2026-09-01", List.of(Map.of("id", "t1", "content", "기출 풀기", "completed", false)));
        String now = Instant.now().toString();
        planRepository.save(new Plan(null, owner, "정보처리기사 실기", 14, 2, "초급", tasks,
                "CONFIRMED", now, null, "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증"));
    }

    // 로그인 회원 세션 — 리졸버는 Bearer 해시로 userId를 찾는다.
    private String memberToken(String userId) {
        String token = SessionToken.generate();
        authRepository.createSession(SessionToken.hash(token), userId, 30);
        return token;
    }

    private void assertUntouched(long challengeId, String owner) {
        assertThat(challengeRepository.findById(challengeId).orElseThrow().participantCount()).isZero();
        assertThat(challengeRepository.findParticipants(challengeId)).isEmpty();
        assertThat(challengeRepository.balanceOf(owner)).isEqualTo(INITIAL_BALANCE);
    }

    @Test
    void join_게스트는_403_CHALLENGE_LOGIN_REQUIRED_이고_참가_인원_잔액이_변하지_않는다() throws Exception {
        long id = open();
        confirmedPlan(GUEST); // 자격은 갖췄어도 게스트라는 이유만으로 거부돼야 한다

        mvc.perform(post("/api/v1/challenges/{id}/join", id).header("X-Guest-Id", GUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CHALLENGE_LOGIN_REQUIRED"));

        assertUntouched(id, GUEST);
    }

    @Test
    void join_회원은_200_이고_참가비가_차감된다() throws Exception {
        long id = open();
        String userId = authRepository.upsertUser("google", "sub-0001", "a@example.com", "회원").id();
        confirmedPlan(userId);

        mvc.perform(post("/api/v1/challenges/{id}/join", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken(userId))
                        .header("X-Guest-Id", GUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(INITIAL_BALANCE - 100))
                .andExpect(jsonPath("$.data.challenge.participantCount").value(1));

        assertThat(challengeRepository.findParticipants(id)).extracting(p -> p.owner()).containsExactly(userId);
    }

    @Test
    void join_무효_Bearer는_401_AUTH_TOKEN_INVALID_이고_참가되지_않는다() throws Exception {
        long id = open();
        confirmedPlan(GUEST);

        mvc.perform(post("/api/v1/challenges/{id}/join", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                        .header("X-Guest-Id", GUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));

        assertUntouched(id, GUEST);
    }
}
