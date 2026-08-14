package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.dto.AiDraftRequest;
import com.delaynomore.backend.domain.ai.dto.PlanDraftSessionResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 계획 작성 대화의 질문 순서, 입력 해석, 초안 생성 및 최초 저장을 서버에서 처리한다.
 * 세션은 브라우저 식별자별 임시 상태이므로 서버 재시작 후에는 새로 시작한다. 확정된 계획은 PlanService가 저장한다.
 */
@Service
@RequiredArgsConstructor
public class PlanDraftSessionService {

    private static final String GOAL_NAME = "goalName";
    private static final String DURATION = "duration";
    private static final String DAILY_HOURS = "dailyHours";
    private static final String CURRENT_LEVEL = "currentLevel";

    private final Map<String, DraftSession> sessions = new ConcurrentHashMap<>();
    private final AiService aiService;
    private final PlanService planService;

    public PlanDraftSessionResponse create(String owner) {
        DraftSession session = new DraftSession(owner);
        sessions.put(session.id, session);
        return session.response(question(GOAL_NAME));
    }

    public PlanDraftSessionResponse accept(String id, String owner, String rawMessage, String sessionId) {
        DraftSession session = sessions.get(id);
        if (session == null || !session.owner.equals(owner)) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        synchronized (session) {
            if (session.plan != null) {
                return session.response("계획 초안이 이미 저장되었습니다. 체크리스트에서 계속 진행해 주세요.");
            }
            String message = rawMessage.trim();
            String slot = nextInput(session.slots);
            Object value = parse(slot, message);
            if (value == null) {
                return session.response(validationMessage(slot));
            }
            session.slots.put(slot, value);
            String next = nextInput(session.slots);
            if (next != null) {
                return session.response(question(next));
            }

            session.plan = createPlan(session, owner, sessionId);
            return session.response("계획 초안을 만들고 보관했습니다. 체크리스트를 확인하거나 채팅으로 조정해 주세요.");
        }
    }

    private PlanResponse createPlan(DraftSession session, String owner, String sessionId) {
        String goalName = (String) session.slots.get(GOAL_NAME);
        int duration = (Integer) session.slots.get(DURATION);
        int dailyHours = (Integer) session.slots.get(DAILY_HOURS);
        String currentLevel = (String) session.slots.get(CURRENT_LEVEL);
        Object generated = aiService.createDraft(new AiDraftRequest(goalName, duration, dailyHours, currentLevel,
                null, null, null));
        if (!(generated instanceof Map<?, ?> generatedTasks)) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
        }
        Map<String, Object> tasks = taskObjects(generatedTasks);
        String endDate = KstDates.today().plusDays(duration - 1L).toString();
        PlanSaveRequest request = new PlanSaveRequest(goalName, duration, dailyHours, currentLevel, tasks,
                "DRAFT", null, null, endDate, Instant.now().toString());
        return planService.create(request, owner, sessionId);
    }

    private static Map<String, Object> taskObjects(Map<?, ?> generatedTasks) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : generatedTasks.entrySet()) {
            String date = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof List<?> values)) continue;
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                String content = String.valueOf(values.get(index)).trim();
                if (!content.isBlank()) {
                    tasks.add(Map.of("id", "t-" + date + "-" + index, "content", content, "completed", false));
                }
            }
            if (!tasks.isEmpty()) result.put(date, tasks);
        }
        if (result.isEmpty()) throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
        return result;
    }

    private static Object parse(String slot, String message) {
        return switch (slot) {
            case GOAL_NAME, CURRENT_LEVEL -> message.length() >= 2 ? message : null;
            case DURATION -> parseNumber(message, 1, 14);
            case DAILY_HOURS -> parseNumber(message, 1, 24);
            default -> null;
        };
    }

    private static Integer parseNumber(String message, int min, int max) {
        String digits = message.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            int value = Integer.parseInt(digits);
            return value >= min && value <= max ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String nextInput(Map<String, Object> slots) {
        for (String slot : List.of(GOAL_NAME, DURATION, DAILY_HOURS, CURRENT_LEVEL)) {
            Object value = slots.get(slot);
            if (value == null || (value instanceof String text && text.isBlank()) || Integer.valueOf(0).equals(value)) return slot;
        }
        return null;
    }

    private static String question(String slot) {
        return switch (slot) {
            case GOAL_NAME -> "어떤 목표를 이루고 싶으신가요?";
            case DURATION -> "며칠 동안 진행할 계획인가요? (1~14일)";
            case DAILY_HOURS -> "하루에 몇 시간 투자할 수 있나요? (1~24시간)";
            case CURRENT_LEVEL -> "현재 수준이나 경험을 알려 주세요.";
            default -> "계획을 준비하고 있습니다.";
        };
    }

    private static String validationMessage(String slot) {
        return switch (slot) {
            case GOAL_NAME -> "목표를 두 글자 이상 입력해 주세요.";
            case DURATION -> "기간은 1일부터 14일 사이의 숫자로 입력해 주세요.";
            case DAILY_HOURS -> "하루 시간은 1부터 24 사이의 숫자로 입력해 주세요.";
            case CURRENT_LEVEL -> "현재 수준을 두 글자 이상 입력해 주세요.";
            default -> "입력을 다시 확인해 주세요.";
        };
    }

    private static final class DraftSession {
        private final String id = UUID.randomUUID().toString();
        private final String owner;
        private final Map<String, Object> slots = new LinkedHashMap<>();
        private PlanResponse plan;

        private DraftSession(String owner) {
            this.owner = owner;
            slots.put(GOAL_NAME, "");
            slots.put(DURATION, 0);
            slots.put(DAILY_HOURS, 0);
            slots.put(CURRENT_LEVEL, "");
        }

        private PlanDraftSessionResponse response(String reply) {
            return new PlanDraftSessionResponse(id, reply, Map.copyOf(slots), nextInput(slots), plan);
        }
    }
}
