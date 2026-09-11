package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.plan.dto.ReflectionSaveRequest;
import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import com.delaynomore.backend.domain.plan.entity.ReflectionDifficulty;
import com.delaynomore.backend.domain.plan.entity.ReflectionReason;
import com.delaynomore.backend.domain.plan.service.ReflectionService;
import com.delaynomore.backend.domain.plan.service.TodayDashboardService;
import com.delaynomore.backend.domain.slack.repository.SlackRepository;
import com.delaynomore.backend.domain.slack.repository.SlackRepository.ReflectionSession;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 슬랙 문답 회고(v0.28.0) — 활동 종료 시각에 완료율 요약과 난이도 질문을 보내고, 답장을
 * 해석해(규칙 기반 — 숫자·키워드) 기존 {@link ReflectionService}로 저장한다. 계획이 여러 개면
 * 한 번에 하나씩 순차 진행한다(slack_reflection_sessions 상태 기계, V10).
 *
 * <p>회고의 규칙("오늘(KST)만 저장", 완료 수치는 서버 재계산)은 전부 ReflectionService가
 * 소유한다 — 자정을 넘긴 답장은 서버의 409(REFLECTION_DATE_NOT_TODAY)를 우회하지 않고
 * 그대로 안내하며 세션을 EXPIRED로 닫는다. 답변 해석이 LLM이 아니라 규칙인 이유:
 * 선택지가 3택·5택의 닫힌 집합이라 숫자·키워드 매칭으로 충분하고, 해석 실패는 재질문으로
 * 수렴한다 — 여기에 모델을 태우면 비용과 오해석 위험만 는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackReflectionFlowService {

    static final String STATE_PENDING = "PENDING";
    static final String STATE_AWAITING_DIFFICULTY = "AWAITING_DIFFICULTY";
    static final String STATE_AWAITING_REASON = "AWAITING_REASON";
    static final String STATE_DONE = "DONE";
    static final String STATE_EXPIRED = "EXPIRED";

    private static final Pattern LEADING_CHOICE = Pattern.compile("^\\s*([1-5])\\b");

    private final SlackRepository slackRepository;
    private final TodayDashboardService todayDashboardService;
    private final ReflectionService reflectionService;

    /**
     * 활동 종료 프롬프트를 시작한다 — 오늘 작업이 있고 회고가 아직 없는 계획마다 세션을 만들고
     * 첫 질문 텍스트를 돌려준다. 회고할 계획이 없으면 empty(발송 생략).
     */
    public Optional<String> startFlow(String owner, LocalDate today) {
        List<TodayDashboardResponse.PlanItem> targets = reflectionTargets(owner);
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < targets.size(); i++) {
            slackRepository.createReflectionSession(owner, today, targets.get(i).plan().id(),
                    i == 0 ? STATE_AWAITING_DIFFICULTY : STATE_PENDING);
        }
        TodayDashboardResponse dashboard = todayDashboardService.get(owner);
        return Optional.of("🌙 오늘 활동시간이 끝났어요 — 하루를 닫아볼까요? 오늘 진행: "
                + dashboard.done() + "/" + dashboard.total() + "\n\n"
                + difficultyQuestion(targets.getFirst()));
    }

    /** 답변을 기다리는 세션이 있는가 — 있으면 메시지는 명령 파서가 아니라 이 플로우가 받는다. */
    public boolean hasAwaitingSession(String owner, LocalDate today) {
        return slackRepository.findAwaitingReflectionSession(owner, today).isPresent();
    }

    /** 회고 답장 한 건을 처리하고 다음 안내를 돌려준다. */
    public String handleAnswer(String owner, LocalDate today, String text) {
        ReflectionSession session = slackRepository.findAwaitingReflectionSession(owner, today).orElse(null);
        if (session == null) {
            return "진행 중인 회고가 없어요.";
        }
        if (STATE_AWAITING_DIFFICULTY.equals(session.state())) {
            ReflectionDifficulty difficulty = parseDifficulty(text);
            if (difficulty == null) {
                return "숫자로 답해주시면 돼요 — 1. 여유로웠어요 / 2. 적당했어요 / 3. 벅찼어요";
            }
            slackRepository.updateReflectionSession(owner, today, session.planId(),
                    STATE_AWAITING_REASON, difficulty.name());
            return reasonQuestion();
        }
        // AWAITING_REASON
        ReflectionReason reason = parseReason(text);
        if (reason == null) {
            return "숫자로 답해주시면 돼요 — " + reasonChoices();
        }
        try {
            // 완료 수치는 서버가 재계산하고, "오늘만 저장"도 이 호출 안의 가드가 판정한다.
            reflectionService.save(session.planId(), today.toString(),
                    new ReflectionSaveRequest(session.difficulty(), reason.name()),
                    owner, SlackCommandService.SESSION_LABEL);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.REFLECTION_DATE_NOT_TODAY) {
                // 자정 경과 — 서버 가드를 우회하지 않는다. 남은 세션 전부 닫고 종료.
                slackRepository.closeReflectionSessions(owner, today, STATE_EXPIRED);
                return "자정이 지나 오늘 회고로 저장할 수 없어요. 내일의 회고는 내일 활동이 끝나면 다시 여쭤볼게요. 🌙";
            }
            if (e.getErrorCode() == ErrorCode.PLAN_NOT_FOUND) {
                // 계획이 그 사이 삭제됨 — 이 세션만 건너뛰고 다음으로.
                slackRepository.updateReflectionSession(owner, today, session.planId(), STATE_DONE, null);
                return advance(owner, today, "해당 계획이 삭제되어 건너뛰었어요.");
            }
            return e.getMessage();
        }
        slackRepository.updateReflectionSession(owner, today, session.planId(), STATE_DONE, null);
        return advance(owner, today, "저장했어요 ✅");
    }

    /** 다음 PENDING 세션을 활성화하고 질문을 잇는다. 없으면 마무리 인사. */
    private String advance(String owner, LocalDate today, String prefix) {
        Optional<ReflectionSession> next = slackRepository.findPendingReflectionSession(owner, today);
        if (next.isEmpty()) {
            return prefix + " 오늘 회고를 모두 마쳤어요. 수고 많으셨어요! 🎉";
        }
        slackRepository.updateReflectionSession(owner, today, next.get().planId(),
                STATE_AWAITING_DIFFICULTY, null);
        Optional<TodayDashboardResponse.PlanItem> item = todayDashboardService.get(owner).plans().stream()
                .filter(p -> p.plan().id() == next.get().planId())
                .findFirst();
        if (item.isEmpty()) {
            // 대시보드에서 사라짐(작업 변경 등) — 이 세션도 닫고 재귀적으로 다음을 찾는다.
            slackRepository.updateReflectionSession(owner, today, next.get().planId(), STATE_DONE, null);
            return advance(owner, today, prefix);
        }
        return prefix + " 다음 계획이에요.\n\n" + difficultyQuestion(item.get());
    }

    private List<TodayDashboardResponse.PlanItem> reflectionTargets(String owner) {
        return todayDashboardService.get(owner).plans().stream()
                .filter(item -> item.total() > 0 && item.reflection() == null)
                .sorted(Comparator.comparingLong(item -> item.plan().id()))
                .toList();
    }

    private static String difficultyQuestion(TodayDashboardResponse.PlanItem item) {
        return "*" + item.plan().goalName() + "* — 오늘 " + item.total() + "개 중 " + item.done()
                + "개 완료했어요. 체감 난이도는 어땠나요?\n"
                + "1. 여유로웠어요  2. 적당했어요  3. 벅찼어요";
    }

    private static String reasonQuestion() {
        return "그렇게 느낀 이유는 무엇인가요?\n" + reasonChoices();
    }

    private static String reasonChoices() {
        return "1. 계획대로 진행됐어요  2. 시간이 부족했어요  3. 분량이 많았어요  "
                + "4. 집중이 잘 안 됐어요  5. 생각보다 어려웠어요";
    }

    // 답변 해석 — 숫자 우선, 다음 라벨 키워드. 못 알아들으면 null(재질문).
    static ReflectionDifficulty parseDifficulty(String text) {
        String t = text == null ? "" : text.trim();
        Matcher m = LEADING_CHOICE.matcher(t);
        if (m.find()) {
            return switch (m.group(1)) {
                case "1" -> ReflectionDifficulty.EASY;
                case "2" -> ReflectionDifficulty.NORMAL;
                case "3" -> ReflectionDifficulty.HARD;
                default -> null;
            };
        }
        if (t.contains("여유")) return ReflectionDifficulty.EASY;
        if (t.contains("적당") || t.contains("보통")) return ReflectionDifficulty.NORMAL;
        if (t.contains("벅차") || t.contains("벅찼") || t.contains("힘들") || t.contains("힘듦")) return ReflectionDifficulty.HARD;
        return null;
    }

    static ReflectionReason parseReason(String text) {
        String t = text == null ? "" : text.trim();
        Matcher m = LEADING_CHOICE.matcher(t);
        if (m.find()) {
            return switch (m.group(1)) {
                case "1" -> ReflectionReason.AS_PLANNED;
                case "2" -> ReflectionReason.NOT_ENOUGH_TIME;
                case "3" -> ReflectionReason.TOO_MUCH_WORK;
                case "4" -> ReflectionReason.HARD_TO_FOCUS;
                case "5" -> ReflectionReason.HARDER_THAN_EXPECTED;
                default -> null;
            };
        }
        if (t.contains("계획대로")) return ReflectionReason.AS_PLANNED;
        if (t.contains("시간")) return ReflectionReason.NOT_ENOUGH_TIME;
        if (t.contains("분량") || t.contains("많았")) return ReflectionReason.TOO_MUCH_WORK;
        if (t.contains("집중")) return ReflectionReason.HARD_TO_FOCUS;
        if (t.contains("어려")) return ReflectionReason.HARDER_THAN_EXPECTED;
        return null;
    }
}
