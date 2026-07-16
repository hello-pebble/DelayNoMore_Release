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
}
