package com.delaynomore.backend.global.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 계획 보관함 응답에 Cache-Control: no-store를 강제한다. 응답은 소유자(X-Guest-Id)별로 갈리는
// 개인 데이터라, 프록시·브라우저 캐시에 남아 다른 소유자에게 재사용되면 안 된다(guestId는 인증이
// 아니라 데이터를 여는 bearer 성격이라 특히 주의). CORS는 컨트롤러 @CrossOrigin(origins="*",
// 기본 allowedHeaders="*")가 X-Guest-Id 프리플라이트까지 허용하므로 별도 설정을 두지 않는다.
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NoStoreInterceptor()).addPathPatterns("/api/v1/plans/**", "/api/v1/plans");
    }

    public static class NoStoreInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            return true;
        }
    }
}
