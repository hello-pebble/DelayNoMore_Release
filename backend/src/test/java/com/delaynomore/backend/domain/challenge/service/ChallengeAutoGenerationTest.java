package com.delaynomore.backend.domain.challenge.service;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.domain.plan.entity.Plan;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 챌린지 자동 생성 — "비슷한 조건의 계획이 몇 명분 모이면 열리는가". 조건 판정 자체는
// ChallengeConditionTest가, 참가 규칙은 ChallengeServiceTest가 맡는다.
// 계획 고정 경로(PlanService.confirm)는 계획의 conditionKey를 넘기는 것이 전부라, 여기서는
// 실제 Plan을 만들어 그 값을 넘긴다 — 카테고리→키 파생까지 같은 경로로 함께 검증된다.
class ChallengeAutoGenerationTest {

    private final ChallengeRepository challengeRepository = new InMemoryChallengeRepository();
    private final ChallengeService challengeService = new ChallengeService(challengeRepository);

    // category가 있으면 그것을, 없으면(null) 목표명 키워드 폴백을 태운다.
    private void confirm(String owner, String category, String goalName, int durationDays) {
        Plan plan = new Plan(1L, owner, goalName, durationDays, 2, "초급", Map.of(), "CONFIRMED",
                null, null, "2026-08-22", "2026-09-04", "2026-08-22T00:00:00Z", 1L, category);
        challengeService.onPlanConfirmed(owner, plan.conditionKey());
    }

    @Test
    void 소유자가_2명이면_아직_열리지_않는다() {
        confirm("guest-a-0001", "어학", "토익 900점", 14);
        confirm("guest-b-0001", "어학", "영어 회화", 14);

        assertThat(challengeRepository.findAll()).isEmpty();
    }

    @Test
    void 소유자_3명이_모이면_자동으로_열린다() {
        confirm("guest-a-0001", "어학", "토익 900점", 14);
        confirm("guest-b-0001", "어학", "영어 회화 연습", 13); // 기간이 달라도 같은 버킷(14)이라 함께 센다
        confirm("guest-c-0001", "어학", "오픽 준비", 14);

        assertThat(challengeRepository.findAll()).singleElement().satisfies(c -> {
            assertThat(c.title()).isEqualTo("어학 14일 챌린지");
            assertThat(c.owner()).isEqualTo("system"); // 자동 생성 챌린지에는 개설자가 없다
            assertThat(c.conditionKey()).isEqualTo("어학:14");
            assertThat(c.capacity()).isEqualTo(5);
            assertThat(c.entryFee()).isEqualTo(100);
            assertThat(c.participantCount()).isZero(); // 계획을 고정했다고 자동 참가되지는 않는다
        });
    }

    @Test
    void 카테고리가_없으면_목표명_키워드로_폴백한다() {
        // mock 폴백·레거시 계획처럼 LLM 판정이 없는 경우 — 목표명만으로도 같은 조건에 묶인다.
        confirm("guest-a-0001", null, "토익 900점", 14);
        confirm("guest-b-0001", null, "영어 회화", 14);
        confirm("guest-c-0001", null, "오픽 준비", 14);

        assertThat(challengeRepository.findAll()).singleElement()
                .satisfies(c -> assertThat(c.conditionKey()).isEqualTo("어학:14"));
    }

    @Test
    void 키워드_사전에_없던_목적도_LLM_카테고리로_묶인다() {
        // 사전에는 없지만 CATEGORIES에는 있는 목적 — LLM 판정이 주 경로가 된 이유다.
        confirm("guest-a-0001", "요리", "주말마다 새로운 메뉴 도전", 14);
        confirm("guest-b-0001", "요리", "집밥 실력 늘리기", 14);
        confirm("guest-c-0001", "요리", "도시락 싸기 습관", 14);

        assertThat(challengeRepository.findAll()).singleElement()
                .satisfies(c -> assertThat(c.title()).isEqualTo("요리 14일 챌린지"));
    }

    @Test
    void 같은_소유자가_3번_고정해도_열리지_않는다() {
        // 카운트의 단위는 계획이 아니라 소유자다 — 혼자 세 번 고정한 것은 함께 달릴 사람이 아니다.
        confirm("guest-a-0001", "어학", "토익 900점", 14);
        confirm("guest-a-0001", "어학", "영어 회화", 14);
        confirm("guest-a-0001", "어학", "오픽 준비", 14);

        assertThat(challengeRepository.findAll()).isEmpty();
    }

    @Test
    void 조건이_다르면_따로_센다() {
        confirm("guest-a-0001", "어학", "토익 900점", 14);
        confirm("guest-b-0001", "운동", "매일 러닝", 14);
        confirm("guest-c-0001", "코딩", "알고리즘 풀이", 14);

        assertThat(challengeRepository.findAll()).isEmpty();
    }

    @Test
    void 모집중인_챌린지가_있으면_중복_생성되지_않는다() {
        confirm("guest-a-0001", "어학", "토익 900점", 14);
        confirm("guest-b-0001", "어학", "영어 회화", 14);
        confirm("guest-c-0001", "어학", "오픽 준비", 14);
        confirm("guest-d-0001", "어학", "토플 준비", 14); // 4번째 — 이미 모집 중이므로 새로 열지 않는다

        assertThat(challengeRepository.findAll()).hasSize(1);
    }

    @Test
    void 정원이_차면_같은_조건으로_다시_열린다() {
        confirm("guest-a-0001", "어학", "토익 900점", 14);
        confirm("guest-b-0001", "어학", "영어 회화", 14);
        confirm("guest-c-0001", "어학", "오픽 준비", 14);
        long first = challengeRepository.findAll().getFirst().id();
        for (int i = 0; i < 5; i++) {
            challengeService.join(first, "guest-filler-" + i);
        }

        confirm("guest-d-0001", "어학", "토플 준비", 14);

        assertThat(challengeRepository.findAll()).hasSize(2);
        assertThat(challengeRepository.findById(first).orElseThrow().full()).isTrue();
    }

    @Test
    void 분류되지_않은_목표는_챌린지를_만들지_않는다() {
        // 모델이 "기타"를 골랐거나(파서가 걸러 null) 폴백 사전에도 안 걸리는 경우.
        confirm("guest-a-0001", null, "그냥 뭔가 해보기", 14);
        confirm("guest-b-0001", null, "이것저것 하기", 14);
        confirm("guest-c-0001", null, "아무거나", 14);

        assertThat(challengeRepository.findAll()).isEmpty();
    }

    @Test
    void 기존_사용자_개설_챌린지는_조건_판정에_끼어들지_않는다() {
        // condition_key가 없는 레거시 행(v0.22.0 이전 개설분)이 있어도 자동 생성은 정상 동작한다.
        challengeRepository.save(new Challenge(null, "guest-legacy", "옛날 챌린지", 14, 5, 100, 0,
                java.time.Instant.now().toString(), null));

        confirm("guest-a-0001", "어학", "토익 900점", 14);
        confirm("guest-b-0001", "어학", "영어 회화", 14);
        confirm("guest-c-0001", "어학", "오픽 준비", 14);

        assertThat(challengeRepository.findAll()).hasSize(2);
    }
}
