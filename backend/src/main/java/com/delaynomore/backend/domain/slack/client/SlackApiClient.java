package com.delaynomore.backend.domain.slack.client;

import com.delaynomore.backend.global.config.SlackProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

/**
 * Slack Web API 클라이언트 — chat.postMessage(전송)와 conversations.open(DM 채널 확보)만 쓴다.
 *
 * <p>Slack은 실패도 HTTP 200 + {@code ok:false}로 내려주므로 상태 코드가 아니라 응답 본문의
 * ok 필드가 판정 기준이다. 전송 실패는 예외가 아니라 boolean으로 돌려준다 — 발송 루프의
 * 재시도 클레임(slack_daily_sends)이 소비할 신호이지 요청 스레드를 끊을 일이 아니다.
 * 봇 토큰 미설정이면 드라이런: 호출 없이 로그만 남기고 성공으로 처리한다(로컬 검증용).
 */
@Slf4j
@Component
public class SlackApiClient {

    private final RestClient restClient;
    private final SlackProperties properties;

    public SlackApiClient(@Qualifier("slackRestClient") RestClient restClient,
                          SlackProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /** 채널(또는 DM 채널 ID)로 텍스트 전송. true = Slack이 ok로 응답. */
    public boolean postMessage(String channel, String text) {
        if (!properties.isBotTokenConfigured()) {
            log.info("slack dry-run postMessage channel={} text={}", channel, text.replace('\n', ' '));
            return true;
        }
        try {
            JsonNode response = restClient.post()
                    .uri("/chat.postMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("channel", channel, "text", text))
                    .retrieve()
                    .body(JsonNode.class);
            boolean ok = response != null && response.path("ok").asBoolean(false);
            if (!ok) {
                log.warn("slack postMessage failed channel={} error={}", channel,
                        response == null ? "no-body" : response.path("error").asString(""));
            }
            return ok;
        } catch (Exception e) {
            log.warn("slack postMessage error channel={}", channel, e);
            return false;
        }
    }

    /** 슬랙 사용자와의 DM 채널을 연다(이미 있으면 기존 채널 반환). */
    public Optional<String> openDm(String slackUserId) {
        if (!properties.isBotTokenConfigured()) {
            log.info("slack dry-run openDm user={}", slackUserId);
            return Optional.empty();
        }
        try {
            JsonNode response = restClient.post()
                    .uri("/conversations.open")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("users", slackUserId))
                    .retrieve()
                    .body(JsonNode.class);
            if (response != null && response.path("ok").asBoolean(false)) {
                String channelId = response.path("channel").path("id").asString("");
                return channelId.isEmpty() ? Optional.empty() : Optional.of(channelId);
            }
            log.warn("slack conversations.open failed user={} error={}", slackUserId,
                    response == null ? "no-body" : response.path("error").asString(""));
            return Optional.empty();
        } catch (Exception e) {
            log.warn("slack conversations.open error user={}", slackUserId, e);
            return Optional.empty();
        }
    }
}
