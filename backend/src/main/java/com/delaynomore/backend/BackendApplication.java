package com.delaynomore.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

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

}
