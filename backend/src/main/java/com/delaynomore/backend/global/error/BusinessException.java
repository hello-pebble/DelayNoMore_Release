package com.delaynomore.backend.global.error;

import lombok.Getter;

// 비즈니스 예외는 이 한 가지로 통일한다(개별 예외 클래스 금지). 처리는 GlobalExceptionHandler 한 곳.
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
