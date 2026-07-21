package com.delaynomore.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 다시 확인해주세요."),
    GUEST_ID_REQUIRED(HttpStatus.BAD_REQUEST, "게스트 식별자가 필요합니다. X-Guest-Id 헤더를 확인해주세요."),
    GUEST_ID_INVALID(HttpStatus.BAD_REQUEST, "게스트 식별자 형식이 올바르지 않습니다(영문·숫자·하이픈 8~64자)."),
    AI_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해주세요."),
    AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "AI 응답을 해석하지 못했습니다. 잠시 후 다시 시도해주세요."),
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "계획을 찾을 수 없습니다. 이미 삭제되었을 수 있어요."),
    // 소유자당 한도 초과 — 사용자가 직접 해소할 수 있으므로 400 + 액션 가능한 메시지.
    PLAN_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "내 보관함이 가득 찼습니다(최대 10개). 기존 계획을 삭제한 뒤 다시 저장해주세요."),
    // 전역 상한 초과 — 서버 메모리 보호용(사용자 잘못이 아님)이라 503.
    PLAN_STORE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "데모 서버 보관함이 가득 찼습니다. 잠시 후 다시 시도해주세요."),
    // 요청 형식 오류(400)가 아니라 "리소스의 현재 상태와 충돌"이므로 409.
    PLAN_LOCKED(HttpStatus.CONFLICT, "고정(CONFIRMED)된 계획은 완료 체크 외에는 수정할 수 없습니다."),
    REFLECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 날짜의 회고가 아직 없습니다."),
    REFLECTION_DATE_INVALID(HttpStatus.BAD_REQUEST, "날짜 형식이 올바르지 않습니다(YYYY-MM-DD)."),
    REFLECTION_DATE_NOT_TODAY(HttpStatus.BAD_REQUEST, "회고는 오늘(한국 시간 기준) 날짜에만 저장할 수 있습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
