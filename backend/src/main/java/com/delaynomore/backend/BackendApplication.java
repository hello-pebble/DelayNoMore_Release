package com.delaynomore.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

	// OpenRouter 호출 대행용 HTTP 클라이언트. AiController가 주입받아 사용한다.
	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

	// SSE 스트리밍 응답을 업스트림(OpenRouter)에서 읽어 emitter로 밀어내는 작업을 돌릴 스레드 풀.
	// SseEmitter는 컨트롤러가 즉시 반환하고 별도 스레드에서 스트림을 이어받아야 하므로 필요하다.
	// destroyMethod로 앱 종료 시 정리한다.
	@Bean(destroyMethod = "shutdown")
	public ExecutorService sseExecutor() {
		return Executors.newCachedThreadPool();
	}

}
