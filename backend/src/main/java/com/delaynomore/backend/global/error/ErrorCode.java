package com.delaynomore.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 다시 확인해주세요."),
    // 없어진 엔드포인트를 아직 부르는 예전 프론트(예: v0.22.0의 챌린지 개설 POST)가 500을 받지
    // 않도록 — 서버 오류가 아니라 "그 메서드는 지원하지 않는다"가 정확한 사실이다.
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    GUEST_ID_REQUIRED(HttpStatus.BAD_REQUEST, "게스트 식별자가 필요합니다. X-Guest-Id 헤더를 확인해주세요."),
    GUEST_ID_INVALID(HttpStatus.BAD_REQUEST, "게스트 식별자 형식이 올바르지 않습니다(영문·숫자·하이픈 8~64자)."),
    AI_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해주세요."),
    AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "AI 응답을 해석하지 못했습니다. 잠시 후 다시 시도해주세요."),
    // 에이전트 루프가 도구 호출 상한(MAX_TOOL_TURNS)까지 가고도 최종 답을 못 낸 경우.
    // 폭주 방어라 사용자 잘못이 아니고, 프론트는 기존 자유 대화 경로로 폴백한다.
    AI_TOOL_LOOP_EXCEEDED(HttpStatus.BAD_GATEWAY, "AI가 답을 정리하지 못했습니다. 다시 한 번 물어봐 주세요."),
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "계획을 찾을 수 없습니다. 이미 삭제되었을 수 있어요."),
    // 소유자당 한도 초과 — 사용자가 직접 해소할 수 있으므로 400 + 액션 가능한 메시지.
    PLAN_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "내 보관함이 가득 찼습니다(최대 10개). 기존 계획을 삭제한 뒤 다시 저장해주세요."),
    // 전역 상한 초과 — 서버 메모리 보호용(사용자 잘못이 아님)이라 503.
    PLAN_STORE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "데모 서버 보관함이 가득 찼습니다. 잠시 후 다시 시도해주세요."),
    // 하루 생성 한도 초과 — 시간이 지나면 해소되는 속도 제한이므로 429. 카운트 소스는
    // 감사 이력(PLAN_CREATED)이라 삭제-재생성으로 우회할 수 없다.
    PLAN_DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 계획 생성 한도(5회)를 모두 사용했습니다. 내일 다시 만들어주세요."),
    // 요청 형식 오류(400)가 아니라 "리소스의 현재 상태와 충돌"이므로 409.
    PLAN_LOCKED(HttpStatus.CONFLICT, "고정(CONFIRMED)된 계획은 완료 체크 외에는 수정할 수 없습니다."),
    // 같은 409지만 용도가 다르다 — PLAN_LOCKED는 "내용 수정이 상태에 막힘"(레거시 PUT 경로,
    // 프론트가 error.code로 분기하므로 유지), 이 코드는 "상태 전이 자체가 전이표(PlanStatus)에
    // 없음"(전이 엔드포인트 confirm·complete·cancel 전용).
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "현재 계획 상태에서는 요청한 상태로 변경할 수 없습니다."),
    // 고정(CONFIRMED) 계획의 완료 체크는 오늘(KST)·미래 날짜만 — 지난 날짜는 체크·해제 모두 불가.
    // 이월 규칙이 "오늘 → 내일"뿐이라 미루지 않은 지난 항목은 놓친 것으로 확정되며, 사후 체크로
    // 완료율을 소급 조작할 수 없다. 리소스 상태(날짜 경과)와의 충돌이므로 409.
    PAST_TASK_LOCKED(HttpStatus.CONFLICT, "지난 날짜의 완료 체크는 변경할 수 없습니다."),
    CHALLENGE_NOT_FOUND(HttpStatus.NOT_FOUND, "챌린지를 찾을 수 없습니다. 이미 삭제되었을 수 있어요."),
    // 정원 마감 — 요청 형식은 옳고 "리소스의 현재 상태와 충돌"이므로 PLAN_LOCKED 계열의 409다.
    // 판정은 조건부 UPDATE(WHERE participant_count < capacity)가 0행을 갱신했을 때만 내려진다 —
    // 서비스가 미리 세어 본 값으로 던지지 않는다(그러면 동시 요청에서 정원을 넘긴다).
    CHALLENGE_FULL(HttpStatus.CONFLICT, "모집이 마감되었습니다. 다른 참가자가 마지막 자리를 가져갔어요."),
    // 중복 참가 — 판정 주체는 challenge_participants의 복합 PK(인메모리는 참가자 집합)다.
    CHALLENGE_ALREADY_JOINED(HttpStatus.CONFLICT, "이미 참가한 챌린지입니다."),
    // 포인트 부족 — 사용자가 (다른 챌린지를 덜 참가해서) 해소할 수 있는 조건이므로 400.
    POINTS_INSUFFICIENT(HttpStatus.BAD_REQUEST, "포인트가 부족해 참가할 수 없습니다."),
    // 세션 토큰 불일치·만료 — 401. 프론트는 이 응답을 받으면 저장된 auth를 지우고 게스트로
    // 복귀한다. 게스트로 조용히 폴백하지 않는 이유: 만료를 숨기면 이후 쓰기가 게스트 보관함에
    // 잘못 귀속되고, 사용자는 로그인돼 있다고 믿은 채 데이터가 갈라진다.
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해주세요."),
    // Google ID 토큰 검증 실패(서명·aud 불일치·만료) — 사용자가 재로그인으로 해소하므로 401.
    AUTH_GOOGLE_INVALID(HttpStatus.UNAUTHORIZED, "Google 인증에 실패했습니다. 다시 시도해주세요."),
    // 로그인 기능이 꺼져 있음(클라이언트 ID 미설정 또는 스위치 오프) — 사용자 잘못이 아니라 503.
    AUTH_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "로그인 기능이 현재 비활성화되어 있습니다."),
    REFLECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 날짜의 회고가 아직 없습니다."),
    REFLECTION_DATE_INVALID(HttpStatus.BAD_REQUEST, "날짜 형식이 올바르지 않습니다(YYYY-MM-DD)."),
    REFLECTION_DATE_NOT_TODAY(HttpStatus.BAD_REQUEST, "회고는 오늘(한국 시간 기준) 날짜에만 저장할 수 있습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
