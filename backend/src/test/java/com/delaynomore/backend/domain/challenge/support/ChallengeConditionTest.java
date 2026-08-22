package com.delaynomore.backend.domain.challenge.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 조건 판정 규칙 — "무엇이 같은 조건인가". 챌린지가 언제 몇 개 열리는지는
// ChallengeAutoGenerationTest가 맡고, 여기서는 (카테고리, 기간) → 조건 변환과
// 카테고리가 없을 때의 목표명 폴백만 본다.
class ChallengeConditionTest {

    @Test
    void 카테고리와_기간으로_조건을_만든다() {
        assertThat(ChallengeCondition.of("어학", 14).orElseThrow().key()).isEqualTo("어학:14");
        assertThat(ChallengeCondition.of("요리", 30).orElseThrow().title()).isEqualTo("요리 30일 챌린지");
    }

    @Test
    void 기간은_버킷으로_접힌다() {
        // 13일짜리와 14일짜리가 서로 다른 챌린지로 갈라지지 않아야 한다.
        assertThat(ChallengeCondition.of("어학", 8).orElseThrow().durationDays()).isEqualTo(14);
        assertThat(ChallengeCondition.of("어학", 14).orElseThrow().durationDays()).isEqualTo(14);
        // 버킷 경계 — 7 이하는 7, 8부터는 다음 버킷.
        assertThat(ChallengeCondition.of("어학", 7).orElseThrow().durationDays()).isEqualTo(7);
        assertThat(ChallengeCondition.of("어학", 15).orElseThrow().durationDays()).isEqualTo(30);
        assertThat(ChallengeCondition.of("어학", 61).orElseThrow().durationDays()).isEqualTo(90);
        // 마지막 버킷을 넘겨도 90으로 수렴한다(365일짜리 계획도 90일 챌린지에 들어간다).
        assertThat(ChallengeCondition.of("어학", 365).orElseThrow().durationDays()).isEqualTo(90);
    }

    @Test
    void 목록_밖_라벨과_기타는_조건이_아니다() {
        // 모델 환각·오탈자는 여기서 걸린다 — "기타 14일 챌린지"에는 목적이 없다.
        assertThat(ChallengeCondition.of(ChallengeCondition.UNCLASSIFIED, 14)).isEmpty();
        assertThat(ChallengeCondition.of("헬스", 14)).isEmpty();   // 운동의 오탈자가 아니라 목록 밖 값
        assertThat(ChallengeCondition.of(null, 14)).isEmpty();
        assertThat(ChallengeCondition.of("어학", null)).isEmpty();
        assertThat(ChallengeCondition.of("어학", 0)).isEmpty();
    }

    @Test
    void 폴백_분류는_표현이_달라도_같은_카테고리로_묶는다() {
        assertThat(ChallengeCondition.classify("토익 900점 달성")).contains("어학");
        assertThat(ChallengeCondition.classify("영어 회화 연습")).contains("어학");
        assertThat(ChallengeCondition.classify("매일 헬스 가기")).contains("운동");
        assertThat(ChallengeCondition.classify("알고리즘 문제풀이")).contains("코딩");
        assertThat(ChallengeCondition.classify("그냥 뭔가 해보기")).isEmpty();
        assertThat(ChallengeCondition.classify(null)).isEmpty();
    }

    @Test
    void parse는_key의_역함수다() {
        // 저장된 키만으로 챌린지 제목·기간을 복원할 수 있어야 한다(챌린지 생성·미래의 스케줄러).
        for (String category : ChallengeCondition.CATEGORIES) {
            String key = ChallengeCondition.of(category, 30).orElseThrow().key();
            assertThat(ChallengeCondition.parse(key)).contains(new ChallengeCondition(category, 30));
        }
    }

    @Test
    void parse는_키가_아닌_문자열을_거부한다() {
        assertThat(ChallengeCondition.parse(null)).isEmpty();
        assertThat(ChallengeCondition.parse("어학")).isEmpty();        // 구분자 없음
        assertThat(ChallengeCondition.parse("어학:열넷")).isEmpty();    // 기간이 숫자가 아님
        assertThat(ChallengeCondition.parse("기타:14")).isEmpty();      // 목적이 없는 조건
        assertThat(ChallengeCondition.parse("없는카테고리:14")).isEmpty();
    }
}
