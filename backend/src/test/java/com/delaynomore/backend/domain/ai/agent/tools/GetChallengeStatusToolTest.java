package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// get_challenge_status payload 계약 — 모델이 이 값(순위·완료율·정산)을 그대로 인용하므로
// 순위 계산이 리더보드 정렬과 어긋나면 답이 틀린다. 목·스텁 없이 실제 서비스에 위임한다.
class GetChallengeStatusToolTest {

    private final InMemoryChallengeRepository challengeRepository = new InMemoryChallengeRepository();
    private final InMemoryPlanRepository planRepository = new InMemoryPlanRepository();
    private final ChallengeService challengeService = new ChallengeService(challengeRepository, planRepository);
    private final GetChallengeStatusTool tool = new GetChallengeStatusTool(challengeService);

    private void joinWithRate(long challengeId, String owner, boolean allDone) {
        String now = Instant.now().toString();
        planRepository.save(new Plan(null, owner, "정보처리기사 실기", 14, 2, "초급",
                Map.of("2026-09-01", List.of(
                        Map.of("id", "t1", "content", "공부", "completed", true),
                        Map.of("id", "t2", "content", "복습", "completed", allDone))),
                "CONFIRMED", now, null, "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증"));
        challengeService.join(challengeId, owner);
    }

    private AgentContext contextOf(String owner) {
        return new AgentContext(owner, "session-1", 1L, PlanStatus.CONFIRMED, "정보처리기사 실기", 2, Map.of());
    }

    @Test
    void 참가중이면_순위와_완료율이_리더보드와_같은_기준으로_실린다() {
        long id = challengeRepository.save(new Challenge(null, "system", "자격증 14일 챌린지", 14, 5,
                100, 0, Instant.now().toString(), "자격증:14", null, null)).id();
        joinWithRate(id, "guest-top-0001", true);   // 100%
        joinWithRate(id, "guest-me-00001", false);  // 50%

        ToolResult result = tool.execute(null, contextOf("guest-me-00001"));

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.payload();
        assertThat(payload.get("joinedCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = ((List<Map<String, Object>>) payload.get("challenges")).get(0);
        assertThat(entry.get("title")).isEqualTo("자격증 14일 챌린지");
        assertThat(entry.get("status")).isEqualTo("RECRUITING");
        assertThat(entry.get("participantCount")).isEqualTo(2);
        assertThat(entry.get("myRank")).isEqualTo(2);
        assertThat(entry.get("myRatePercent")).isEqualTo(50);
        assertThat(entry.get("topRatePercent")).isEqualTo(100);
    }

    @Test
    void 참가한_챌린지가_없으면_빈_목록과_안내를_돌려준다() {
        ToolResult result = tool.execute(null, contextOf("guest-nobody-01"));

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.payload();
        assertThat(payload.get("joinedCount")).isEqualTo(0);
        assertThat(payload).containsKey("note");
    }
}
