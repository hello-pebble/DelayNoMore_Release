package com.delaynomore.backend.domain.auth.client;

import com.delaynomore.backend.global.config.GoogleOauthProperties;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google ID 토큰(GIS credential) 검증기 — tokeninfo 엔드포인트에 위임한다.
 *
 * <p>서명·만료 검증을 Google 서버가 대신 해 주므로(유효하지 않으면 4xx) 이쪽은 JOSE 라이브러리
 * 없이 HTTP 한 번이면 된다. 단 aud(토큰이 발급된 클라이언트 ID)는 tokeninfo가 판정해 주지
 * 않으므로 반드시 여기서 대조한다 — 다른 앱에서 발급된 정상 토큰으로 로그인되는 것을 막는다.
 */
// ponytail: 로그인 시에만 1회 호출되는 저트래픽 전제. 로그인 QPS가 의미 있어지면
// nimbus JWKS 로컬 서명 검증으로 교체(외부 왕복 제거).
@Component
@Slf4j
public class GoogleTokenVerifier {

    // 검증된 신원 — provider_subject로 쓰는 sub와 표시·연락용 email(제공자가 안 줄 수 있어 nullable).
    public record GoogleIdentity(String sub, String email) {
    }

    private final RestClient googleTokenInfoRestClient;
    private final GoogleOauthProperties properties;
    private final JsonMapper jsonMapper;

    public GoogleTokenVerifier(RestClient googleTokenInfoRestClient, GoogleOauthProperties properties,
                               JsonMapper jsonMapper) {
        this.googleTokenInfoRestClient = googleTokenInfoRestClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public GoogleIdentity verify(String credential) {
        String body;
        try {
            body = googleTokenInfoRestClient.get()
                    .uri(uri -> uri.path("/tokeninfo").queryParam("id_token", credential).build())
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // 무효 토큰(4xx)과 Google 장애를 구분하지 않는다 — 사용자 행동은 어차피 "다시 로그인"뿐.
            log.warn("Google tokeninfo call failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AUTH_GOOGLE_INVALID);
        }
        JsonNode node = jsonMapper.readTree(body);
        String aud = node.path("aud").asString(null);
        String sub = node.path("sub").asString(null);
        if (sub == null || !properties.clientId().equals(aud)) {
            log.warn("Google id token rejected: aud mismatch or sub missing");
            throw new BusinessException(ErrorCode.AUTH_GOOGLE_INVALID);
        }
        return new GoogleIdentity(sub, node.path("email").asString(null));
    }
}
