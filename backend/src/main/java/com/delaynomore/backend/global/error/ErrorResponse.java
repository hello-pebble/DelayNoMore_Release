package com.delaynomore.backend.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

// ApiResponse.error에 담기는 오류 본문. 프론트 분기는 code로만 한다.
public record ErrorResponse(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> fieldErrors
) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, Map<String, String> fieldErrors) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), fieldErrors);
    }
}
