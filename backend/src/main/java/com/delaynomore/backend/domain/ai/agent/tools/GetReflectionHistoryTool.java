package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.ReflectionResponse;
import com.delaynomore.backend.domain.plan.entity.ReflectionDifficulty;
import com.delaynomore.backend.domain.plan.entity.ReflectionReason;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계획별 일일 회고 이력을 읽는다. 지금까지 코치 대화는 회고를 전혀 보지 못했다 — 회고는
 * 추천 규칙 엔진에만 들어갔고 프롬프트에는 목표·현재 계획·최근 6턴만 실렸다. 이 도구로
 * "왜 자꾸 못 끝내지?" 같은 질문에 실제 난이도·이유 기록을 근거로 답할 수 있게 된다.
 *
 * 코드(EASY/TOO_MUCH_WORK 등)와 함께 한글 라벨을 실어 보낸다 — enum 라벨이 소스오브트루스라
 * 모델이 코드를 제멋대로 번역해 화면 용어와 어긋나는 것을 막는다.
 */
@Component
@RequiredArgsConstructor
public class GetReflectionHistoryTool implements AgentTool {

    // 프롬프트에 실리는 양을 제한한다 — 회고는 하루 1건이라 최근 14건이면 2주치로 충분하고,
    // 계획이 길어져도 입력 토큰이 선형으로 늘지 않는다(v0.3.0부터의 토큰 절약 기조).
    private static final int MAX_ENTRIES = 14;

    private final ReflectionService reflectionService;

    @Override
    public String name() {
        return "get_reflection_history";
    }

    @Override
    public String description() {
        return "Read the user's daily retrospectives for the current plan (date, completed/total, "
                + "perceived difficulty, and the reason they picked). Use this when the user asks why "
                + "they are falling behind, or when you need evidence about how hard the plan feels.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        if (!context.hasPlanId()) {
            return ToolResult.fail("아직 보관되지 않은 초안이라 회고 기록이 없습니다.");
        }
        List<ReflectionResponse> reflections;
        try {
            reflections = reflectionService.getAll(context.planId(), context.owner());
        } catch (BusinessException e) {
            return ToolResult.fail("계획을 찾을 수 없습니다.");
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (ReflectionResponse r : reflections.stream().limit(MAX_ENTRIES).toList()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", r.date());
            entry.put("done", r.completedCount());
            entry.put("total", r.totalCount());
            entry.put("difficulty", r.difficulty());
            entry.put("difficultyLabel", difficultyLabel(r.difficulty()));
            entry.put("reason", r.reason());
            entry.put("reasonLabel", reasonLabel(r.reason()));
            entries.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", entries.size());
        payload.put("truncated", reflections.size() > MAX_ENTRIES);
        payload.put("reflections", entries);
        return ToolResult.ok(payload);
    }

    // 라벨은 서버 enum이 소유한다(메타 API와 같은 출처). 알 수 없는 코드는 코드 그대로 —
    // 저장된 값이 enum에서 사라진 경우에도 도구가 깨지지 않게.
    private static String difficultyLabel(String code) {
        try {
            return ReflectionDifficulty.valueOf(code).getLabel();
        } catch (IllegalArgumentException | NullPointerException e) {
            return code;
        }
    }

    private static String reasonLabel(String code) {
        try {
            return ReflectionReason.valueOf(code).getLabel();
        } catch (IllegalArgumentException | NullPointerException e) {
            return code;
        }
    }
}
