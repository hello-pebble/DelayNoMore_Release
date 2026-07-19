package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.client.OpenRouterClient;
import com.delaynomore.backend.domain.ai.dto.AiChatRequest;
import com.delaynomore.backend.domain.ai.dto.AiChatResponse;
import com.delaynomore.backend.domain.ai.dto.AiDraftRequest;
import com.delaynomore.backend.domain.ai.dto.AiHealthResponse;
import com.delaynomore.backend.global.config.OpenRouterProperties;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OpenRouterClient openRouterClient = mock(OpenRouterClient.class);

    private AiService serviceWithKey(String key) {
        OpenRouterProperties properties = new OpenRouterProperties("https://openrouter.example", key, "test-model");
        return new AiService(openRouterClient, new AiPromptBuilder(jsonMapper), new AiResponseParser(jsonMapper),
                properties, Executors.newSingleThreadExecutor(), jsonMapper);
    }

    @Test
    void getHealth_키미설정_미연결과사유반환() {
        // given
        AiService aiService = serviceWithKey("YOUR_OPENROUTER_API_KEY_HERE");

        // when
        AiHealthResponse health = aiService.getHealth();

        // then
        assertThat(health.connected()).isFalse();
        assertThat(health.reason()).isEqualTo("API Key 미설정");
        verifyNoInteractions(openRouterClient);
    }

    @Test
    void getHealth_키유효_연결반환() {
        // given
        AiService aiService = serviceWithKey("sk-live-key");
        when(openRouterClient.checkKey()).thenReturn(new OpenRouterClient.KeyCheck(true, null));

        // when
        AiHealthResponse health = aiService.getHealth();

        // then
        assertThat(health.connected()).isTrue();
        assertThat(health.reason()).isNull();
    }

    @Test
    void createDraft_정상업스트림응답_정제된계획반환() {
        // given
        AiService aiService = serviceWithKey("sk-live-key");
        when(openRouterClient.complete(anyList(), anyInt()))
                .thenReturn("```json\n{\"2026-07-16\": [\"重點 핵심 개념 정리\"]}\n```");
        AiDraftRequest request = new AiDraftRequest("토익 900점", 3, 2, "600점대", null, null);

        // when
        Object plan = aiService.createDraft(request);

        // then — 코드펜스 제거 + 비한국어 CJK(한자) 제거까지 끝난 계획이 돌아온다.
        assertThat(plan).isEqualTo(Map.of("2026-07-16", List.of("핵심 개념 정리")));
    }

    @Test
    void createDraft_날짜없는업스트림응답_KST오늘부터날짜합성() {
        // given — 배포 컨테이너 JVM은 UTC라, 자정~오전 9시(KST) 사이 UTC 오늘을 쓰면 하루 어긋난다.
        AiService aiService = serviceWithKey("sk-live-key");
        when(openRouterClient.complete(anyList(), anyInt()))
                .thenReturn("{\"Day 1\": [\"개념 정리\"], \"Day 2\": [\"기출 풀기\"]}");
        AiDraftRequest request = new AiDraftRequest("토익 900점", 2, 2, "600점대", null, null);

        // when
        Object plan = aiService.createDraft(request);

        // then — 합성 날짜도, 프롬프트의 대상 날짜도 모두 KST 오늘부터 시작한다.
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString();
        assertThat(plan).isEqualTo(Map.of(
                today, List.of("개념 정리"),
                tomorrow, List.of("기출 풀기")));
        verify(openRouterClient).complete(argThat(messages -> messages.toString().contains(today)), anyInt());
    }

    @Test
    void createDraft_해석불가능한업스트림응답_예외발생() {
        // given
        AiService aiService = serviceWithKey("sk-live-key");
        when(openRouterClient.complete(anyList(), anyInt())).thenReturn("계획을 만들 수 없습니다.");
        AiDraftRequest request = new AiDraftRequest("토익 900점", 3, 2, "600점대", null, null);

        // when
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> aiService.createDraft(request));

        // then
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
    }

    @Test
    void chat_구분자포함응답_계획변경응답반환() {
        // given
        AiService aiService = serviceWithKey("sk-live-key");
        when(openRouterClient.complete(anyList(), anyInt()))
                .thenReturn("1일차를 더 쉽게 바꿨어요.\n===PLAN===\n{\"2026-07-16\": [\"기초 단어 20개 암기\"]}");
        AiChatRequest request = new AiChatRequest("토익 900점", 3, 2, "600점대",
                "1일차 너무 어려워요", Map.of(), List.of());

        // when
        AiChatResponse response = aiService.chat(request);

        // then
        assertThat(response.planUpdated()).isTrue();
        assertThat(response.reply()).isEqualTo("1일차를 더 쉽게 바꿨어요.");
        assertThat(response.patch()).containsKey("2026-07-16");
    }
}
