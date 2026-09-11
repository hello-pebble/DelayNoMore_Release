package com.delaynomore.backend.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {

    private final OpenRouterProperties properties;

    // OpenRouter 호출 전용 RestClient. 키는 서버에만 두고 모든 요청에 Bearer 헤더로 실어 보낸다.
    @Bean
    public RestClient openRouterRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(properties.url())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.key())
                .defaultHeader("HTTP-Referer", "http://localhost:5173")
                .defaultHeader("X-Title", "DelayNoMore")
                .build();
    }

    // Google ID 토큰 검증(tokeninfo) 전용 RestClient — 인증 헤더가 필요 없는 공개 엔드포인트다.
    @Bean
    public RestClient googleTokenInfoRestClient(RestClient.Builder builder) {
        return builder.baseUrl("https://oauth2.googleapis.com").build();
    }

    // Slack Web API 전용 RestClient. 봇 토큰은 서버에만 두고 Bearer 헤더로 실어 보낸다 —
    // 토큰 미설정 시에도 빈은 만들어지되 SlackApiClient가 호출 전에 드라이런으로 분기한다.
    @Bean
    public RestClient slackRestClient(RestClient.Builder builder, SlackProperties slackProperties) {
        return builder
                .baseUrl("https://slack.com/api")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + slackProperties.botToken())
                .build();
    }
}
