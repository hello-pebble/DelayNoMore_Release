package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.challenge.dto.ChallengeParticipantResponse;
import com.delaynomore.backend.domain.challenge.dto.ChallengeResponse;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 내 챌린지 참가 현황(v0.25.0의 정산·리더보드)을 도구로 노출한다. "챌린지에서 나 몇 등이야?"
 * 같은 질문에서 모델이 순위를 지어내지 않고 서버가 계산한 값을 인용하게 하는 것이 목적이다 —
 * 순위·완료율·정산의 소유권은 계속 서버(ChallengeService)다.
 *
 * 조회 전에 settleDue를 부르는 이유: 정산 트리거가 목록 조회 lazy라, 여기서도 같은 관례를
 * 따라야 만기 챌린지의 정산 결과가 낡지 않은 채 답변에 실린다(컨트롤러 list와 같은 순서).
 */
@Component
@RequiredArgsConstructor
public class GetChallengeStatusTool implements AgentTool {

    private final ChallengeService challengeService;

    @Override
    public String name() {
        return "get_challenge_status";
    }

    @Override
    public String description() {
        return "Read the user's Goal Challenge status: each joined challenge with its phase "
                + "(RECRUITING/ACTIVE/CLOSED), end date, the user's completion-rate rank among "
                + "participants, and the settlement result (payout). Use this when the user asks "
                + "about their challenge, their ranking, or how other participants are doing.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        challengeService.settleDue();
        List<Map<String, Object>> joined = new ArrayList<>();
        for (ChallengeResponse challenge : challengeService.list(context.owner()).challenges()) {
            if (!challenge.joined()) {
                continue;
            }
            List<ChallengeParticipantResponse> board =
                    challengeService.participants(challenge.id(), context.owner());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", challenge.title());
            entry.put("status", challenge.status());
            if (challenge.endsAt() != null) {
                entry.put("endsAt", challenge.endsAt());
            }
            entry.put("participantCount", board.size());
            for (int i = 0; i < board.size(); i++) {
                if (board.get(i).me()) {
                    entry.put("myRank", i + 1);
                    entry.put("myRatePercent", board.get(i).ratePercent());
                }
            }
            if (!board.isEmpty()) {
                entry.put("topRatePercent", board.get(0).ratePercent());
            }
            if (challenge.myPayout() != null) {
                entry.put("myPayout", challenge.myPayout());
            }
            joined.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("joinedCount", joined.size());
        payload.put("challenges", joined);
        if (joined.isEmpty()) {
            payload.put("note", "참가 중인 챌린지가 없습니다. 챌린지 탭에서 같은 조건의 챌린지에 참가할 수 있어요.");
        }
        return ToolResult.ok(payload);
    }
}
