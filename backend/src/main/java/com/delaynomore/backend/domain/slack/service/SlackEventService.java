package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.slack.client.SlackApiClient;
import com.delaynomore.backend.domain.slack.repository.SlackRepository.SlackLink;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 슬랙 인바운드 이벤트 처리(비동기 본체). 분기 순서:
 * ① DM의 연결 코드 인식(정규식 — LLM 불요) → 연결,
 * ② 연결된 사용자의 메시지·멘션 → 자연어 명령({@link SlackCommandService}, v0.27.0),
 * ③ 미연결 사용자 → 연결 안내 고정 문구.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackEventService {

    // 연결 코드 후보: 대문자·숫자 8자 단어. 코드 알파벳(SlackLinkService)의 상위집합이라
    // 오탐은 consumeLinkCode가 걸러낸다(없는 코드 = 그냥 일반 메시지 → 명령 처리로 넘어간다).
    private static final Pattern CODE_PATTERN = Pattern.compile("\\b[A-Z0-9]{8}\\b");

    private final SlackLinkService linkService;
    private final SlackCommandService commandService;
    private final SlackReflectionFlowService reflectionFlow;
    private final SlackApiClient apiClient;

    public void handle(JsonNode payload) {
        JsonNode event = payload.path("event");
        String type = event.path("type").asString("");
        // 봇 자신의 메시지·수정/삭제 등 subtype 이벤트는 처리하지 않는다 — 응답이 다시 이벤트가
        // 되어 봇이 자기 자신과 대화하는 루프를 막는 최소 가드.
        if (!event.path("bot_id").asString("").isEmpty() || !event.path("subtype").asString("").isEmpty()) {
            return;
        }
        String teamId = payload.path("team_id").asString("");
        String slackUserId = event.path("user").asString("");
        String channel = event.path("channel").asString("");
        String text = event.path("text").asString("");
        if (slackUserId.isEmpty() || channel.isEmpty()) {
            return;
        }

        boolean isDm = "message".equals(type) && "im".equals(event.path("channel_type").asString(""));
        boolean isMention = "app_mention".equals(type);
        if (!isDm && !isMention) {
            return;
        }

        // ① DM에서는 연결 코드부터 시도한다 — 연결 여부와 무관(재연결 허용). 코드가 실제로
        //    소비됐을 때만 여기서 끝난다.
        if (isDm && tryLinkByCode(teamId, slackUserId, channel, text)) {
            return;
        }
        // ② 연결된 사용자 → 진행 중 회고 문답이 있으면 그 답변으로(v0.28.0), 아니면 자연어 명령.
        //    ③ 미연결 → 안내.
        Optional<SlackLink> link = linkService.findBySlackUser(teamId, slackUserId);
        if (link.isPresent()) {
            String owner = link.get().owner();
            java.time.LocalDate today = KstDates.today();
            String reply = reflectionFlow.hasAwaitingSession(owner, today)
                    ? reflectionFlow.handleAnswer(owner, today, SlackCommandService.stripMentions(text))
                    : commandService.handle(link.get(), text);
            apiClient.postMessage(channel, reply);
        } else {
            apiClient.postMessage(channel, "아직 연결된 계정이 없어요. 웹 마이페이지에서 [슬랙 연결 코드]를 발급받아 "
                    + "이 채팅에 붙여넣어 주세요. (코드는 발급 후 " + SlackLinkService.CODE_TTL_MINUTES + "분간 유효)");
        }
    }

    private boolean tryLinkByCode(String teamId, String slackUserId, String channel, String text) {
        Matcher matcher = CODE_PATTERN.matcher(text.toUpperCase());
        while (matcher.find()) {
            Optional<SlackLink> linked = linkService.linkByCode(matcher.group(), teamId, slackUserId, channel);
            if (linked.isPresent()) {
                apiClient.postMessage(channel, """
                        ✅ 연결되었습니다! 매일 활동 시작 시각(%s)에 오늘 할 일 체크리스트를 보내드릴게요.
                        "1번 완료했어"처럼 말씀하시면 완료 체크도 해드립니다.""".formatted(formatMin(linked.get().activeStartMin())));
                return true;
            }
        }
        return false;
    }

    static String formatMin(int minutesOfDay) {
        return "%02d:%02d".formatted(minutesOfDay / 60, minutesOfDay % 60);
    }
}
