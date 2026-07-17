package com.delaynomore.backend.domain.plan.entity;

// 하루 마무리 회고 한 건 — (planId, date)당 1건만 유지(업서트). 지금은 인메모리 보관용이지만,
// 추후 로그인+PostgreSQL 도입 시 이 record가 테이블 행과 1:1로 대응하도록 필드를 평탄하게 유지한다.
// completedCount/totalCount는 저장 시점에 서버가 plan.tasks에서 재계산한 스냅샷이다(클라이언트 불신).
public record Reflection(
        long planId,
        String date,           // 회고 대상 날짜(YYYY-MM-DD, Asia/Seoul 기준 "오늘"만 허용)
        int completedCount,    // 저장 시점의 오늘 완료 개수(서버 재계산)
        int totalCount,        // 저장 시점의 오늘 전체 개수(서버 재계산)
        String difficulty,     // EASY | NORMAL | HARD (체감 난이도)
        String reason,         // AS_PLANNED | NOT_ENOUGH_TIME | TOO_MUCH_WORK | HARD_TO_FOCUS | HARDER_THAN_EXPECTED
        String createdAt,      // 최초 저장 시각(ISO 문자열) — 재저장해도 보존
        String updatedAt       // 마지막 저장 시각(ISO 문자열)
) {
}
