package com.delaynomore.backend.domain.plan.entity;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// 전이표의 소스오브트루스 검증 — 상태 쌍 전수(from×to) 매트릭스로 허용 간선이 정확히 이 4개뿐임을
// 고정한다. 상태·간선을 추가하면 이 테스트가 먼저 깨져 전이표 변경이 의도적인지 묻게 된다.
class PlanStatusTest {

    // 허용 간선 전체 — 다이어그램(DRAFT→CONFIRMED→COMPLETED, DRAFT|CONFIRMED→CANCELLED) 그대로.
    private static final Set<String> ALLOWED_EDGES = Set.of(
            "DRAFT->CONFIRMED",
            "DRAFT->CANCELLED",
            "CONFIRMED->COMPLETED",
            "CONFIRMED->CANCELLED");

    @Test
    void canTransitionTo_전수매트릭스_허용간선정확히4개() {
        for (PlanStatus from : PlanStatus.values()) {
            for (PlanStatus to : PlanStatus.values()) {
                boolean expected = ALLOWED_EDGES.contains(from.name() + "->" + to.name());
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void canTransitionTo_selfLoop_전상태불허() {
        for (PlanStatus status : PlanStatus.values()) {
            assertThat(status.canTransitionTo(status)).as("%s -> %s", status, status).isFalse();
        }
    }

    @Test
    void isTerminal_종결은COMPLETED와CANCELLED뿐() {
        assertThat(PlanStatus.DRAFT.isTerminal()).isFalse();
        assertThat(PlanStatus.CONFIRMED.isTerminal()).isFalse();
        assertThat(PlanStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(PlanStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void allowsStructuralEdit_DRAFT만허용() {
        assertThat(PlanStatus.DRAFT.allowsStructuralEdit()).isTrue();
        assertThat(PlanStatus.CONFIRMED.allowsStructuralEdit()).isFalse();
        assertThat(PlanStatus.COMPLETED.allowsStructuralEdit()).isFalse();
        assertThat(PlanStatus.CANCELLED.allowsStructuralEdit()).isFalse();
    }

    @Test
    void allowsCompletionToggle_종결전까지허용() {
        assertThat(PlanStatus.DRAFT.allowsCompletionToggle()).isTrue();
        assertThat(PlanStatus.CONFIRMED.allowsCompletionToggle()).isTrue();
        assertThat(PlanStatus.COMPLETED.allowsCompletionToggle()).isFalse();
        assertThat(PlanStatus.CANCELLED.allowsCompletionToggle()).isFalse();
    }

    @Test
    void allowsCarryOver_실행단계액션_종결전까지허용() {
        // 이월은 고정(CONFIRMED) 후에도 허용 — 실행 중 "내일로 미루기"가 핵심 사용처다.
        assertThat(PlanStatus.DRAFT.allowsCarryOver()).isTrue();
        assertThat(PlanStatus.CONFIRMED.allowsCarryOver()).isTrue();
        assertThat(PlanStatus.COMPLETED.allowsCarryOver()).isFalse();
        assertThat(PlanStatus.CANCELLED.allowsCarryOver()).isFalse();
    }

    @Test
    void fromStored_null과blank는DRAFT() {
        // PlanSaveRequest의 status 기본값 규칙과 동일해야 한다(미지정 → DRAFT).
        assertThat(PlanStatus.fromStored(null)).isEqualTo(PlanStatus.DRAFT);
        assertThat(PlanStatus.fromStored("")).isEqualTo(PlanStatus.DRAFT);
        assertThat(PlanStatus.fromStored("  ")).isEqualTo(PlanStatus.DRAFT);
    }

    @Test
    void fromStored_저장된이름_해당상태() {
        for (PlanStatus status : PlanStatus.values()) {
            assertThat(PlanStatus.fromStored(status.name())).isEqualTo(status);
        }
    }

    @Test
    void fromStored_알수없는값_예외() {
        // DB CHECK 제약이 있어 정상 경로에선 나올 수 없다 — 프로그래밍 오류로 즉시 드러낸다.
        assertThatIllegalArgumentException().isThrownBy(() -> PlanStatus.fromStored("ACTIVE"));
    }
}
