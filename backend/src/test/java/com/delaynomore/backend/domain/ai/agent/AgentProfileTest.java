package com.delaynomore.backend.domain.ai.agent;

import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태 → 프로필 매핑 테스트. 매핑 자체는 switch 한 줄이지만, 이 표가 곧 "어떤 상태에서 누가
 * 응대하는가"라는 제품 규칙이므로 전수를 명시해 둔다 — 표가 바뀌면 여기가 먼저 알린다.
 */
class AgentProfileTest {

    @Test
    @DisplayName("네 상태 전부가 프로필 하나로 결정된다 — 초안은 코치, 고정은 전문가, 종결은 회고")
    void 상태_전수_매핑() {
        assertThat(AgentProfile.forStatus(PlanStatus.DRAFT)).isEqualTo(AgentProfile.CHECKLIST_COACH);
        assertThat(AgentProfile.forStatus(PlanStatus.CONFIRMED)).isEqualTo(AgentProfile.DOMAIN_EXPERT);
        assertThat(AgentProfile.forStatus(PlanStatus.COMPLETED)).isEqualTo(AgentProfile.RETRO_COMPANION);
        assertThat(AgentProfile.forStatus(PlanStatus.CANCELLED)).isEqualTo(AgentProfile.RETRO_COMPANION);
    }

    @Test
    @DisplayName("전문 에이전트 라벨은 목표명으로 특화된다")
    void 전문가_라벨은_목표명으로_특화된다() {
        assertThat(AgentProfile.DOMAIN_EXPERT.displayLabel("정보처리기사 실기"))
                .isEqualTo("정보처리기사 실기 전문 에이전트");
        // 앞뒤 공백은 라벨에 그대로 새지 않는다
        assertThat(AgentProfile.DOMAIN_EXPERT.displayLabel("  다이어트  "))
                .isEqualTo("다이어트 전문 에이전트");
    }

    @Test
    @DisplayName("목표명이 없으면 기본 라벨로 폴백한다 — 보관 전 초안 등 목표명 없는 경로가 실제로 있다")
    void 목표명이_없으면_기본_라벨() {
        assertThat(AgentProfile.DOMAIN_EXPERT.displayLabel(null)).isEqualTo("목표 영역 전문 에이전트");
        assertThat(AgentProfile.DOMAIN_EXPERT.displayLabel("   ")).isEqualTo("목표 영역 전문 에이전트");
    }

    @Test
    @DisplayName("코치와 회고 도우미 라벨은 목표명과 무관하다")
    void 다른_프로필은_목표명과_무관() {
        assertThat(AgentProfile.CHECKLIST_COACH.displayLabel("정보처리기사 실기")).isEqualTo("체크리스트 완성 코치");
        assertThat(AgentProfile.RETRO_COMPANION.displayLabel("정보처리기사 실기")).isEqualTo("회고 도우미");
    }
}
