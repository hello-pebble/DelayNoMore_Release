package com.delaynomore.backend.domain.plan.entity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 계획의 파생 접근자 conditionKey() — 챌린지가 "같은 조건"을 판정하는 값이 여기서 나온다.
// 저장은 하지만 읽어 들이지 않는 컬럼(plans.condition_key)의 원본이라, 이 계산이 곧 진실이다.
class PlanTest {

    private static Plan plan(String goalName, Integer duration, String category) {
        return new Plan(1L, "guest-a-0001", goalName, duration, 2, "초급", Map.of(), "DRAFT",
                null, null, "2026-08-22", "2026-09-04", "2026-08-22T00:00:00Z", 1L, category);
    }

    @Test
    void 카테고리가_있으면_그것으로_키를_만든다() {
        assertThat(plan("토익 900점", 14, "어학").conditionKey()).isEqualTo("어학:14");
    }

    @Test
    void 카테고리가_목표명과_달라도_카테고리가_이긴다() {
        // LLM 판정이 주 경로다 — 목표명에 "책"이 있어도 모델이 자격증이라 봤으면 자격증이다.
        assertThat(plan("기출문제집 풀기", 14, "자격증").conditionKey()).isEqualTo("자격증:14");
    }

    @Test
    void 카테고리가_없으면_목표명_키워드로_폴백한다() {
        // mock 폴백(API 키 없음)·레거시 계획·모델이 키를 빠뜨린 응답이 이 경로다.
        assertThat(plan("토익 900점", 14, null).conditionKey()).isEqualTo("어학:14");
    }

    @Test
    void 어느_쪽으로도_분류되지_않으면_키가_없다() {
        assertThat(plan("그냥 뭔가 해보기", 14, null).conditionKey()).isNull();
        assertThat(plan("토익 900점", 14, "기타").conditionKey()).isNull();      // 모델의 탈출구
        assertThat(plan("토익 900점", null, "어학").conditionKey()).isNull();     // 기간 미상
    }

    @Test
    void 기간이_바뀌면_키도_따라_바뀐다() {
        // 이월로 기간이 늘어 버킷을 넘어가면 다음 저장 때 다른 조건이 된다 — 동기화 코드가 없어도
        // 어긋나지 않는 이유가 이것이다(컬럼은 매 저장마다 이 값으로 다시 기록된다).
        assertThat(plan("토익 900점", 14, "어학").conditionKey()).isEqualTo("어학:14");
        assertThat(plan("토익 900점", 15, "어학").conditionKey()).isEqualTo("어학:30");
    }
}
