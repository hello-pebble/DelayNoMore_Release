package com.delaynomore.backend.global.auth;

import com.delaynomore.backend.domain.auth.repository.AuthRepository;
import com.delaynomore.backend.domain.auth.service.AuthService;
import com.delaynomore.backend.domain.auth.support.SessionToken;
import com.delaynomore.backend.domain.plan.support.OwnerGuestId;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@code @Owner String owner} 해석기 — 이 앱의 소유자 판정이 모이는 단 하나의 지점.
 *
 * <p>규칙:
 * <ul>
 *   <li>Authorization: Bearer 토큰이 있으면 세션을 조회해 로그인 사용자의 id를 반환한다.
 *       세션이 없거나 만료면 <b>게스트로 조용히 폴백하지 않고 401을 던진다</b> — 만료를 숨기면
 *       이후 쓰기가 게스트 보관함에 잘못 귀속되고 사용자는 로그인 상태라고 믿은 채 데이터가
 *       갈라진다. 프론트는 401을 받으면 저장된 auth를 지우고 게스트로 복귀한다(db_service.js).</li>
 *   <li>Authorization이 없으면 기존 X-Guest-Id 해석(OwnerGuestId)으로 폴백한다 — 로그인 도입
 *       이전의 모든 게스트 요청이 그대로 동작한다.</li>
 * </ul>
 */
// ponytail: Bearer 요청마다 세션 SELECT 1회 — 저트래픽 전제, 병목이 되면 짧은 TTL 캐시.
public class OwnerArgumentResolver implements HandlerMethodArgumentResolver {

    private final AuthRepository authRepository;

    public OwnerArgumentResolver(AuthRepository authRepository) {
        this.authRepository = authRepository;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Owner.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String bearer = AuthService.extractBearer(webRequest.getHeader(HttpHeaders.AUTHORIZATION));
        if (bearer != null) {
            return authRepository.findUserIdByToken(SessionToken.hash(bearer))
                    .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));
        }
        return OwnerGuestId.resolve(webRequest.getHeader("X-Guest-Id"));
    }
}
