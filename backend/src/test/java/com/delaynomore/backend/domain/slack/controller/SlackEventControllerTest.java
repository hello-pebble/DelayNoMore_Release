package com.delaynomore.backend.domain.slack.controller;

import com.delaynomore.backend.domain.slack.repository.InMemorySlackRepository;
import com.delaynomore.backend.domain.slack.service.SlackEventService;
import com.delaynomore.backend.global.config.SlackProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SlackEventControllerTest {

    private static final String SECRET = "test-secret";

    private SlackEventService eventService;
    private ExecutorService executor;
    private SlackEventController controller;

    @BeforeEach
    void setUp() {
        eventService = mock(SlackEventService.class);
        executor = Executors.newSingleThreadExecutor();
        controller = new SlackEventController(new SlackProperties(null, SECRET, true),
                new InMemorySlackRepository(), eventService, JsonMapper.builder().build(), executor);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void url_verification은_challenge를_평문으로_돌려준다() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";
        ResponseEntity<String> response = post(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("abc123");
    }

    @Test
    void 서명이_틀리면_401이다() {
        String body = "{\"type\":\"event_callback\"}";
        ResponseEntity<String> response = controller.events(body, "v0=wrong",
                String.valueOf(Instant.now().getEpochSecond()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 같은_event_id_재전송은_한_번만_처리된다() {
        String body = "{\"type\":\"event_callback\",\"event_id\":\"Ev1\",\"event\":{\"type\":\"message\"}}";

        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.OK); // 재전송도 200(재시도 중단 신호)

        verify(eventService, timeout(1000).times(1)).handle(any());
    }

    @Test
    void 기능이_꺼져_있으면_503이다() {
        SlackEventController off = new SlackEventController(
                new SlackProperties(null, SECRET, false), new InMemorySlackRepository(),
                eventService, JsonMapper.builder().build(), executor);

        assertThat(off.events("{}", "v0=x", "0").getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void 처리_예외는_응답에_영향을_주지_않는다() {
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(eventService).handle(any());
        String body = "{\"type\":\"event_callback\",\"event_id\":\"Ev2\",\"event\":{\"type\":\"message\"}}";

        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(eventService, timeout(1000).times(1)).handle(any());
    }

    private ResponseEntity<String> post(String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return controller.events(body, sign(timestamp, body), timestamp);
    }

    // 테스트 쪽에서 서명을 별도 구현으로 계산한다(검증기 자체는 openssl 참조 벡터 테스트가 커버).
    private static String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
