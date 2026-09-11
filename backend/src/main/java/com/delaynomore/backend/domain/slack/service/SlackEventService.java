package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.slack.client.SlackApiClient;
import com.delaynomore.backend.domain.slack.repository.SlackRepository.SlackLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 슬랙 인바운드 이벤트 처리(비동기 본체). v0.26.0 범위는 LLM 없는 두 갈래다:
 * ① DM의 연결 코드 인식(정규식) → 연결, ② 그 외 메시지·멘션 → 고정 안내 문구.
 * 자연어 완료 체크(의도 파서)는 v0.27.0에서 이 분기 뒤에 붙는다 — 프롬프트·도구 변경은
 * 평가 하네스 실측을 동반해야 하므로(CLAUDE.md) 릴리스를 분리했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackEventService {

    // 연결 코드 후보: 대문자·숫자 8자 단어. 코드 알파벳(SlackLinkService)의 상위집합이라
    // 오탐은 consumeLinkCode가 걸러낸다(없는 코드 = 그냥 일반 메시지).
    private static final Pattern CODE_PATTERN = Pattern.compile("\\b[A-Z0-9]{8}\\b");

    private final SlackLinkService linkService;
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

        if ("message".equals(type) && "im".equals(event.path("channel_type").asString(""))) {
            handleDm(teamId, slackUserId, channel, text);
        } else if ("app_mention".equals(type)) {
            apiClient.postMessage(channel, guidanceFor(teamId, slackUserId));
        }
    }

    private void handleDm(String teamId, String slackUserId, String channel, String text) {
        // 코드 후보가 있으면 연결부터 시도한다 — 연결 여부와 무관하게(재연결 허용).
        Matcher matcher = CODE_PATTERN.matcher(text.toUpperCase());
        while (matcher.find()) {
            Optional<SlackLink> linked = linkService.linkByCode(matcher.group(), teamId, slackUserId, channel);
            if (linked.isPresent()) {
                apiClient.postMessage(channel, """
                        ✅ 연결되었습니다! 매일 활동 시작 시각(%s)에 오늘 할 일 체크리스트를 보내드릴게요.
                        활동시간은 웹 마이페이지에서 확인할 수 있어요.""".formatted(formatMin(linked.get().activeStartMin())));
                return;
            }
        }
        apiClient.postMessage(channel, guidanceFor(teamId, slackUserId));
    }

    private String guidanceFor(String teamId, String slackUserId) {
        if (linkService.findBySlackUser(teamId, slackUserId).isPresent()) {
            return "이미 연결된 계정이에요. 매일 활동 시작 시각에 오늘 할 일 체크리스트를 보내드립니다. "
                    + "(대화로 완료 체크하는 기능은 준비 중이에요)";
        }
        return "아직 연결된 계정이 없어요. 웹 마이페이지에서 [슬랙 연결 코드]를 발급받아 이 채팅에 붙여넣어 주세요. "
                + "(코드는 발급 후 " + SlackLinkService.CODE_TTL_MINUTES + "분간 유효)";
    }

    static String formatMin(int minutesOfDay) {
        return "%02d:%02d".formatted(minutesOfDay / 60, minutesOfDay % 60);
    }
}
