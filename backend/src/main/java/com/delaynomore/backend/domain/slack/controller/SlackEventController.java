package com.delaynomore.backend.domain.slack.controller;

import com.delaynomore.backend.domain.slack.repository.SlackRepository;
import com.delaynomore.backend.domain.slack.service.SlackEventService;
import com.delaynomore.backend.domain.slack.support.SlackSignatureVerifier;
import com.delaynomore.backend.global.config.SlackProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.concurrent.ExecutorService;

/**
 * Slack Events API 수신 엔드포인트. 이 컨트롤러만 ApiResponse 래핑을 쓰지 않는다 —
 * 응답 형식(challenge 평문, 3초 내 2xx)이 Slack 쪽 계약이기 때문이다.
 *
 * <p>처리 순서: 서명 검증(원문 바이트 기준이라 body를 String으로 받는다) → url_verification
 * 즉답 → event_id 중복 클레임(Slack은 3초 내 무응답이면 같은 이벤트를 재전송한다) →
 * 즉시 200 반환하고 실제 처리는 slackExecutor에서 비동기로 잇는다.
 */
@Tag(name = "slack")
@Slf4j
@RestController
@RequestMapping("/api/v1/slack")
public class SlackEventController {

    private final SlackProperties properties;
    private final SlackRepository slackRepository;
    private final SlackEventService eventService;
    private final JsonMapper jsonMapper;
    private final ExecutorService slackExecutor;

    public SlackEventController(SlackProperties properties, SlackRepository slackRepository,
                                SlackEventService eventService, JsonMapper jsonMapper,
                                @Qualifier("slackExecutor") ExecutorService slackExecutor) {
        this.properties = properties;
        this.slackRepository = slackRepository;
        this.eventService = eventService;
        this.jsonMapper = jsonMapper;
        this.slackExecutor = slackExecutor;
    }

    @Operation(summary = "Slack Events 수신 — 서명 검증 후 비동기 처리(3초 ACK)")
    @PostMapping("/events")
    public ResponseEntity<String> events(
            @RequestBody String body,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp) {
        if (!properties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("slack disabled");
        }
        if (!SlackSignatureVerifier.verify(properties.signingSecret(), timestamp, body,
                signature, Instant.now().getEpochSecond())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }

        JsonNode payload;
        try {
            payload = jsonMapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("invalid payload");
        }

        if ("url_verification".equals(payload.path("type").asString(""))) {
            return ResponseEntity.ok(payload.path("challenge").asString(""));
        }

        // event_id가 없거나 이미 클레임된 이벤트(재전송)는 처리 없이 200 — 재전송을 멈추게 한다.
        String eventId = payload.path("event_id").asString("");
        if (!eventId.isEmpty() && slackRepository.claimEvent(eventId)) {
            slackExecutor.submit(() -> {
                try {
                    eventService.handle(payload);
                } catch (Exception e) {
                    // 비동기 처리 실패는 응답에 실을 수 없다 — 로그가 유일한 관측 지점.
                    log.warn("slack event handling failed eventId={}", eventId, e);
                }
            });
        }
        return ResponseEntity.ok("ok");
    }
}
