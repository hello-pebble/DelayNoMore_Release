package com.delaynomore.backend.global.error;

import com.delaynomore.backend.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(errorCode)));
    }

    // @Valid 검증 실패 — 필드별 사유를 fieldErrors로 내려 프론트가 어떤 값이 문제인지 알게 한다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.info("Request rejected by validation: {}", fieldErrors.keySet());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.INVALID_INPUT, fieldErrors)));
    }

    // 본문이 JSON이 아니거나 타입이 맞지 않는 경우(예: duration에 소수·문자)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.INVALID_INPUT)));
    }

    // SSE(비동기) 타임아웃은 JSON 응답으로 바꿔 쓸 수 없으므로 컨테이너 기본 처리에 되돌린다.
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e) throws AsyncRequestTimeoutException {
        throw e;
    }

    // 존재하지 않는 정적 리소스(예: 봇이 스캔하는 /.env, /wp-admin) — 캐치올(Exception.class)이
    // 이걸 500·ERROR 로그로 바꿔버리면 정상적인 스캔 트래픽이 장애처럼 보인다. 원래 Spring
    // 기본 동작(조용한 404)대로 되돌린다.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.error(ErrorResponse.of(ErrorCode.INTERNAL_ERROR)));
    }
}
