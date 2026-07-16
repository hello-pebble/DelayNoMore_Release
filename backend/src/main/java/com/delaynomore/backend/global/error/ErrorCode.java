package com.delaynomore.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 다시 확인해주세요."),
    AI_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해주세요."),
    AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "AI 응답을 해석하지 못했습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
