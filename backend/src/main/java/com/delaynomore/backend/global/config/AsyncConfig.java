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

    // 슬랙 이벤트 비동기 처리 전용 풀. Slack Events는 3초 안에 200을 요구하므로 컨트롤러는
    // 즉시 반환하고 실제 처리는 여기서 잇는다. SSE 풀과 분리해 스트리밍 릴레이에 간섭하지 않는다.
    @Bean(destroyMethod = "shutdown")
    public ExecutorService slackExecutor() {
        return Executors.newCachedThreadPool();
    }
}
