package com.delaynomore.backend.domain.plan.entity;

// 계획 변경 이력 한 건 — 계획을 바꾸는 서비스가 변경과 함께 직접 발행한다(append-only, 쓰기 API 없음).
// 계획·회고와 같은 인메모리(휘발성) 체제지만, 계획이 삭제되어도 이력은 남긴다(전역 상한으로만 축출)
// — "계획이 언제 삭제됐는가"에 답하는 것이 이 도메인의 존재 이유이기 때문.
// 조회·보관함 열기 같은 읽기 행동과 AI 요청/응답 전문은 기록하지 않는다.
public record AuditEvent(
        long id,           // 저장소 발급 전역 시퀀스 — 최신순 정렬의 단일 기준(동일 시각 타이브레이커)
        long planId,
        String type,       // PLAN_CREATED | PLAN_UPDATED | PLAN_CONFIRMED | TASK_COMPLETED
                           // | TASK_REOPENED | REFLECTION_SAVED | PLAN_DELETED
        String detail,     // 사람이 읽는 부가 설명(한국어, 없으면 null)
        String sessionId,  // X-Session-Id 헤더 값(구형 클라이언트/curl은 null → 화면에서 "알 수 없음")
        String createdAt   // 발생 시각(ISO 문자열)
) {

    public AuditEvent withId(long newId) {
        return new AuditEvent(newId, planId, type, detail, sessionId, createdAt);
    }
}
