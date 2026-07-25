package com.delaynomore.backend.domain.plan.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Set;

// 계획 상태 수명주기의 소스오브트루스 — 상태 집합·전이 규칙·상태별 허용 동작을 한 곳에 선언한다.
// AuditEventType과 같은 관례로, 판정은 이 enum으로만 하고 저장·응답(Plan.status)은 String을
// 유지한다(DB 행 1:1). 서비스는 경계에서 fromStored로 파싱해 들어온다. 프론트 라벨은 메타 API
// (GET /meta/plan-statuses)로 내려간다. DB의 CHECK 제약(V2)은 이 표의 최후 안전망일 뿐,
// 규칙 소유권은 여기다.
//
//   DRAFT ──confirm──▶ CONFIRMED ──complete──▶ COMPLETED   (종결)
//     │                    │
//     └──────cancel────────┴──────────────────▶ CANCELLED   (종결)
@Getter
@RequiredArgsConstructor
public enum PlanStatus {
    DRAFT("초안"),
    CONFIRMED("고정"),
    COMPLETED("완료"),
    CANCELLED("중단");

    // 선언적 전이표 — 전이 엔드포인트(PlanService.transition)·레거시 PUT 가드·테스트가 모두
    // 이 표 하나를 본다. self-loop는 없다(같은 상태로의 "전이"는 무의미하므로 409).
    private static final Map<PlanStatus, Set<PlanStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(CONFIRMED, CANCELLED),
            CONFIRMED, Set.of(COMPLETED, CANCELLED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of());

    private final String label;

    public boolean canTransitionTo(PlanStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    // 나가는 전이가 없는 상태 — 종결 상태에서는 어떤 변경(내용 수정·토글·이월·전이)도 불가.
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    // 상태별 허용 동작 — if-체인 대신 상태가 자기 능력을 선언한다.
    // 구조 변경(항목 추가·삭제·이동, 기간 변경, 이월)은 초안에서만.
    public boolean allowsStructuralEdit() {
        return this == DRAFT;
    }

    // 완료 체크(completed 토글)는 종결 전까지 허용 — 고정된 계획의 실행 단계가 이 토글이다.
    public boolean allowsCompletionToggle() {
        return !isTerminal();
    }

    // 이월(오늘 KST 미완료 → 내일)은 실행 단계 액션 — 내용 재협상이 아니라 서버 소유 규칙의
    // 통제된 이동(오늘→내일만, 필요 시 기간 하루 연장)이므로 고정(CONFIRMED) 후에도 허용한다.
    // 어제로 밀린 항목은 대상이 아니고, 내일로 미룬 항목은 그날이 "오늘"이 되면 다시 미룰 수 있다.
    public boolean allowsCarryOver() {
        return !isTerminal();
    }

    // 저장값 → 상태 파싱. null/blank는 DRAFT로 본다(PlanSaveRequest의 status 기본값 규칙과 동일).
    // 알 수 없는 값은 IllegalArgumentException — DB CHECK 제약이 있어 정상 경로에선 나올 수 없다.
    public static PlanStatus fromStored(String raw) {
        return (raw == null || raw.isBlank()) ? DRAFT : valueOf(raw);
    }
}
