package com.delaynomore.backend.global.response;

import com.delaynomore.backend.global.error.ErrorResponse;

// 모든 REST 응답의 공통 래퍼: { success, data, error }
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> error(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}
