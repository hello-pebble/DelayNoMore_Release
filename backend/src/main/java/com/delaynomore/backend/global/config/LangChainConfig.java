package com.delaynomore.backend.global.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * OpenRouter를 향한 LangChain4j 모델 빈. 요청 바디 조립·SSE 파싱은 이 모델들이 담당하고,
 * 호출 라벨링·사용량 계측·에이전트 루프는 기존대로 {@code OpenRouterClient} 쪽에 남는다.
 *
 * <p>정적 팩토리로 분리한 이유: 테스트가 로컬 가짜 서버를 향한 <b>운영과 같은 구성</b>의 모델을
 * 만들어 "요청 바디에 reasoning off가 실리는가" 같은 전송 계약을 검증해야 하기 때문이다.
 */
@Configuration
public class LangChainConfig {

    // OpenRouter 관례 헤더 — 기존 RestClient 구성과 동일한 값.
    private static final Map<String, String> OPENROUTER_HEADERS = Map.of(
            "HTTP-Referer", "http://localhost:5173",
            "X-Title", "DelayNoMore");

    // 추론(thinking) 계열 모델의 사고를 끈다 — 이 용도엔 불필요하고, 켜두면 응답이 수십 초 걸리고
    // 사고 텍스트가 섞여 파싱을 방해한다. OpenAI 표준 필드가 아니라 customParameters로 실어야 한다.
    private static final Map<String, Object> REASONING_OFF = Map.of("reasoning", Map.of("enabled", false));

    // 업스트림 응답 대기 상한 — SSE 에미터 타임아웃(120초)과 같은 기준.
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Bean
    public ChatModel openRouterChatModel(OpenRouterProperties properties) {
        return chatModel(properties);
    }

    @Bean
    public StreamingChatModel openRouterStreamingChatModel(OpenRouterProperties properties) {
        return streamingChatModel(properties);
    }

    public static ChatModel chatModel(OpenRouterProperties properties) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.url())
                .apiKey(properties.key())
                .modelName(properties.model())
                .customHeaders(OPENROUTER_HEADERS)
                .customParameters(REASONING_OFF)
                .timeout(TIMEOUT)
                .build();
    }

    public static StreamingChatModel streamingChatModel(OpenRouterProperties properties) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(properties.url())
                .apiKey(properties.key())
                .modelName(properties.model())
                .customHeaders(OPENROUTER_HEADERS)
                .customParameters(REASONING_OFF)
                .timeout(TIMEOUT)
                .build();
    }
}
