package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.ai.client.OpenRouterClient;
import com.delaynomore.backend.domain.ai.client.OpenRouterClient.Completion;
import com.delaynomore.backend.domain.ai.client.OpenRouterClient.ToolCall;
import com.delaynomore.backend.domain.ai.usage.AiCallSite;
import com.delaynomore.backend.domain.ai.usage.AiRateLimiter;
import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.domain.plan.service.TodayDashboardService;
import com.delaynomore.backend.domain.slack.repository.SlackRepository;
import com.delaynomore.backend.domain.slack.repository.SlackRepository.SlackLink;
import com.delaynomore.backend.domain.slack.service.SlackMessageComposer.NumberedTask;
import com.delaynomore.backend.domain.slack.support.SlackIntentPrompt;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import com.delaynomore.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/**
 * 연결된 사용자의 슬랙 자연어 메시지 처리(v0.27.0) — 단일 비스트리밍 LLM 호출로 의도를
 * 해석해(도구 2종: complete_task·no_action) 완료 체크를 실행하거나 답장을 만든다.
 *
 * <p>웹 에이전트 루프(AgentRunner)를 태우지 않는 이유: 그 루프는 SSE·단일 계획의
 * PlanStatus·대화 히스토리에 결박돼 있는데 슬랙 메시지는 계획을 특정하지 않은 한 건의
 * 명령이다. 대신 변이의 권한 판정은 기존 서비스가 그대로 소유한다 —
 * {@code PlanService.updateTaskCompletion}이 소유자·상태(PLAN_LOCKED)·지난 날짜
 * (PAST_TASK_LOCKED)를 판정하고, 이 클래스는 그 결과(예외 메시지)를 답장으로 옮길 뿐이다.
 * 날짜도 서버가 taskId로 역추적하므로 모델이 만든 인자로는 잠금을 우회할 수 없다.
 *
 * <p>프롬프트·도구 정의는 {@link SlackIntentPrompt}가 소유한다 — 평가(SlackIntentEvalTest)가
 * 같은 정의로 실측하기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackCommandService {

    static final String SESSION_LABEL = "slack"; // 변경 이력의 X-Session-Id 자리 — 채널 표기
    private static final int MAX_TOKENS = 300;
    private static final String FALLBACK_REPLY =
            "무슨 뜻인지 잘 모르겠어요. \"1번 완료했어\"처럼 오늘 체크리스트의 작업을 알려주시면 체크해 드릴게요.";
    private static final String AI_OFF_REPLY =
            "지금은 자연어 명령을 처리할 수 없는 설정이에요(AI 미연결). 완료 체크는 웹 화면에서 부탁드려요.";
    private static final String AI_ERROR_REPLY =
            "지금은 메시지를 이해하지 못했어요. 잠시 후 다시 말씀해 주세요.";
    private static final String AI_LIMIT_REPLY =
            "오늘 AI 사용량 한도를 모두 썼어요. 완료 체크는 웹 화면에서 하실 수 있고, 내일이면 다시 대화할 수 있어요.";

    private final OpenRouterProperties openRouterProperties;
    private final OpenRouterClient openRouterClient;
    private final TodayDashboardService todayDashboardService;
    private final SlackMessageComposer composer;
    private final PlanService planService;
    private final SlackRepository slackRepository;
    private final AiRateLimiter rateLimiter;
    private final JsonMapper jsonMapper;

    /** 메시지 한 건을 처리하고 사용자에게 보낼 답장을 돌려준다(항상 non-null). */
    public String handle(SlackLink link, String rawText) {
        String owner = link.owner();
        String text = stripMentions(rawText);
        if (text.isBlank()) {
            return FALLBACK_REPLY;
        }
        if (!openRouterProperties.isKeyConfigured() || !openRouterProperties.isToolCallingEnabled()) {
            return AI_OFF_REPLY;
        }
        // 소유자 일일 상한(v0.30.0) — 슬랙에도 웹과 같은 지갑을 쓰므로 같은 한도를 적용한다.
        // 전역 상한은 OpenRouterClient가 별도로 덮는다(그때는 AI_ERROR_REPLY로 떨어진다).
        if (!rateLimiter.tryAcquireOwner(owner)) {
            return AI_LIMIT_REPLY;
        }
        List<NumberedTask> tasks = composer.numberTasks(todayDashboardService.get(owner));
        Completion completion;
        try {
            completion = openRouterClient.completeWithTools(AiCallSite.SLACK_INTENT,
                    SlackIntentPrompt.messages(tasks, link.activeStartMin(), link.activeEndMin(), text),
                    MAX_TOKENS, SlackIntentPrompt.tools());
        } catch (BusinessException e) {
            return AI_ERROR_REPLY; // AI_UPSTREAM_ERROR — 슬랙에는 오류 코드 대신 사람 말로
        }

        if (completion.hasToolCalls()) {
            ToolCall call = completion.toolCalls().get(0); // 루프 없는 단일 의도 — 첫 호출만 취한다
            JsonNode args = parseArgs(call.argumentsJson());
            return switch (call.name()) {
                case "complete_task" -> completeTask(owner, tasks, args);
                case "set_active_hours" -> setActiveHours(link, args);
                case "no_action" -> replyOf(args);
                default -> FALLBACK_REPLY;
            };
        }
        // 도구 미지원 모델 등으로 산문만 온 경우 — 그대로 답장으로 쓴다(변이는 없으니 안전).
        String content = completion.content();
        return content == null || content.isBlank() ? FALLBACK_REPLY : content.trim();
    }

    private String completeTask(String owner, List<NumberedTask> tasks, JsonNode args) {
        int number;
        try {
            number = Integer.parseInt(args.path("number").asString("").trim());
        } catch (NumberFormatException e) {
            return FALLBACK_REPLY;
        }
        Optional<NumberedTask> found = tasks.stream().filter(t -> t.number() == number).findFirst();
        if (found.isEmpty()) {
            return number + "번 작업을 오늘 체크리스트에서 찾지 못했어요. 아침에 보내드린 목록의 번호로 말씀해 주세요.";
        }
        NumberedTask task = found.get();
        boolean completed = !"false".equalsIgnoreCase(args.path("completed").asString("true").trim());
        if (task.completed() == completed) {
            return "'" + task.content() + "'" + (completed ? "은(는) 이미 완료돼 있어요. ✅" : "은(는) 아직 완료 체크되지 않은 상태예요.");
        }
        try {
            // 권한·잠금 판정은 전부 이 호출 안에 있다 — 소유자 불일치는 404, 지난 날짜는 409.
            planService.updateTaskCompletion(task.planId(), task.taskId(), completed, owner, SESSION_LABEL);
        } catch (BusinessException e) {
            return e.getMessage(); // PAST_TASK_LOCKED 등 — 서버 판정 문구를 그대로 전달
        }
        TodayDashboardResponse after = todayDashboardService.get(owner);
        if (completed) {
            String progress = "오늘 진행: " + after.done() + "/" + after.total();
            return after.done() == after.total()
                    ? "🎉 '" + task.content() + "' 완료! 오늘 할 일을 전부 끝냈어요 (" + progress + ")"
                    : "✅ '" + task.content() + "' 완료 처리했어요. " + progress;
        }
        return "↩️ '" + task.content() + "' 완료 체크를 해제했어요. 오늘 진행: " + after.done() + "/" + after.total();
    }

    /**
     * 활동시간 변경(v0.28.0) — 언급된 쪽만 바꾸고 나머지는 유지한다. 검증(형식·start&lt;end)은
     * 여기서 하고, 저장은 저장소가 한다. 다음 발송·회고부터 새 시각이 적용된다.
     */
    private String setActiveHours(SlackLink link, JsonNode args) {
        Integer start = parseHhmm(args.path("start").asString(""));
        Integer end = parseHhmm(args.path("end").asString(""));
        if (start == null && end == null) {
            return "바꿀 시각을 \"09:00\"처럼 알려주세요 — 예: \"활동시간 9시부터 21시까지로 바꿔줘\"";
        }
        int newStart = start != null ? start : link.activeStartMin();
        int newEnd = end != null ? end : link.activeEndMin();
        if (newStart >= newEnd) {
            return "시작 시각(" + formatMin(newStart) + ")이 종료 시각(" + formatMin(newEnd)
                    + ")보다 빨라야 해요. 다시 알려주시겠어요?";
        }
        if (!slackRepository.updateActiveHours(link.owner(), newStart, newEnd)) {
            return "연결 정보를 찾지 못했어요. 마이페이지에서 연결 상태를 확인해주세요.";
        }
        return "⏰ 활동시간을 " + formatMin(newStart) + " ~ " + formatMin(newEnd) + "로 바꿨어요. "
                + "내일부터 " + formatMin(newStart) +"에 체크리스트, " + formatMin(newEnd) + "에 회고 질문을 보내드릴게요.";
    }

    /** "HH:mm" → 분. 형식이 아니면 null(빈 문자열 포함). */
    static Integer parseHhmm(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (!v.matches("([01]?\\d|2[0-3]):[0-5]\\d")) {
            return null;
        }
        String[] parts = v.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    static String formatMin(int minutesOfDay) {
        return "%02d:%02d".formatted(minutesOfDay / 60, minutesOfDay % 60);
    }

    private String replyOf(JsonNode args) {
        String reply = args.path("reply").asString("").trim();
        return reply.isEmpty() ? FALLBACK_REPLY : reply;
    }

    private JsonNode parseArgs(String argumentsJson) {
        try {
            return jsonMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            return jsonMapper.createObjectNode();
        }
    }

    /** 멘션 토큰(<@U123ABC>)을 지운다 — app_mention 이벤트의 텍스트에 항상 섞여 온다. */
    static String stripMentions(String text) {
        return text == null ? "" : text.replaceAll("<@[A-Z0-9]+>", " ").trim();
    }
}
