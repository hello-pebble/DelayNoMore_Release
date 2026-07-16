package com.delaynomore.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    // SSE 스트리밍 응답을 업스트림(OpenRouter)에서 읽어 emitter로 밀어내는 작업을 돌릴 스레드 풀.
    // SseEmitter는 컨트롤러가 즉시 반환하고 별도 스레드에서 스트림을 이어받아야 하므로 필요하다.
    // destroyMethod로 앱 종료 시 정리한다.
    @Bean(destroyMethod = "shutdown")
    public ExecutorService sseExecutor() {
        return Executors.newCachedThreadPool();
    }
}
