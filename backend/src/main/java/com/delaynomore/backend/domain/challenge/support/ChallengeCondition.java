package com.delaynomore.backend.domain.challenge.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// 챌린지 자동 생성의 "비슷한 조건" 판정 — 계획의 (목적 카테고리, 기간)을 한 쌍으로 접는다.
// 이 record가 조건 판정의 유일한 소유자다: 임계치·정원 같은 정책은 ChallengeService가,
// "무엇이 같은 조건인가"와 "카테고리로 무엇을 허용하는가"는 여기가 갖는다.
//
// [카테고리는 어디서 오는가] 계획 초안을 만드는 LLM 호출이 함께 판정해 plans.category에 저장한다
// (v0.23.0). 그 값이 없을 때만 classify()의 키워드 사전으로 폴백한다 — mock 폴백(API 키 없음),
// 레거시 계획, 모델이 키를 빠뜨린 응답이 그 경우다.
//
// [어휘의 단일 소유권] CATEGORIES는 프롬프트 문구·모델 응답 검증·키워드 폴백이 모두 참조한다.
// 프롬프트는 이 목록을 조립해 만들어지므로(AiPromptBuilder) 목록과 프롬프트가 벌어질 수 없다.
public record ChallengeCondition(String category, int durationDays) {

    // 챌린지가 열릴 수 있는 목적들. 순서가 곧 프롬프트에 실리는 순서다.
    public static final List<String> CATEGORIES =
            List.of("어학", "자격증", "운동", "코딩", "독서", "글쓰기", "악기", "요리", "재테크");

    // 모델에게 주는 탈출구 — 어디에도 맞지 않을 때 억지로 라벨을 고르지 않게 한다.
    // "기타 14일 챌린지"에는 목적이 없으므로 이 값으로는 챌린지를 만들지 않는다.
    public static final String UNCLASSIFIED = "기타";

    private static final String KEY_SEPARATOR = ":";

    // 폴백용 키워드 사전 — 카테고리 → 목표명에서 찾을 키워드.
    private static final Map<String, List<String>> KEYWORDS = Map.of(
            "어학", List.of("영어", "토익", "토플", "오픽", "일본어", "중국어", "회화", "단어", "어학"),
            "자격증", List.of("자격증", "기사", "시험", "합격", "필기", "실기"),
            "운동", List.of("운동", "헬스", "러닝", "달리기", "다이어트", "요가", "걷기", "홈트"),
            "코딩", List.of("코딩", "개발", "프로그래밍", "알고리즘", "코테", "자바", "파이썬", "리액트"),
            "독서", List.of("독서", "책", "읽기"),
            "글쓰기", List.of("글쓰기", "글쓰", "블로그", "일기", "에세이"),
            "악기", List.of("기타", "피아노", "드럼", "바이올린", "우쿨렐레", "악기"),
            "요리", List.of("요리", "베이킹", "레시피", "제빵"),
            "재테크", List.of("재테크", "주식", "투자", "가계부", "저축"));

    // 폴백 판정 순서 — Map.of는 순서를 보장하지 않으므로 여기서 고정한다. 먼저 걸리는 쪽이 이긴다.
    // "기타"(악기)가 "기타 등등"의 기타와 겹치는 것이 이 사전의 한계이고, LLM 판정이 주 경로가 된
    // 이유이기도 하다 — 폴백은 폴백만큼만 정확하면 된다.
    private static final List<String> PRIORITY =
            List.of("자격증", "어학", "코딩", "운동", "독서", "글쓰기", "요리", "재테크", "악기");

    // 기간 버킷 — 13일과 14일짜리 계획이 서로 다른 챌린지로 갈라지지 않도록 접는다.
    private static final int[] BUCKETS = {7, 14, 30, 60, 90};

    // 주 경로 — 이미 판정된 카테고리와 기간으로 조건을 만든다. 목록에 없는 라벨(모델 환각·오탈자)과
    // UNCLASSIFIED는 조건이 성립하지 않는다.
    public static Optional<ChallengeCondition> of(String category, Integer durationDays) {
        if (category == null || durationDays == null || durationDays < 1 || !CATEGORIES.contains(category)) {
            return Optional.empty();
        }
        return Optional.of(new ChallengeCondition(category, bucket(durationDays)));
    }

    // 폴백 — 목표명 키워드로 카테고리를 추측한다. LLM 판정이 없을 때만 쓴다.
    public static Optional<String> classify(String goalName) {
        if (goalName == null) {
            return Optional.empty();
        }
        String normalized = goalName.replaceAll("\s+", "").toLowerCase();
        return PRIORITY.stream()
                .filter(category -> KEYWORDS.get(category).stream().anyMatch(normalized::contains))
                .findFirst();
    }

    // key()의 역함수 — 저장된 키에서 제목·기간을 복원한다. 챌린지 생성이 계획의 conditionKey만
    // 받아도 되게 하고(분류가 두 군데서 일어나지 않게), 나중에 스케줄러가 SQL로 집계한 키를 그대로
    // 챌린지로 바꿀 수 있게 한다.
    public static Optional<ChallengeCondition> parse(String key) {
        if (key == null) {
            return Optional.empty();
        }
        int separator = key.lastIndexOf(KEY_SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        try {
            return of(key.substring(0, separator), Integer.parseInt(key.substring(separator + 1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int bucket(int durationDays) {
        for (int bucket : BUCKETS) {
            if (durationDays <= bucket) {
                return bucket;
            }
        }
        return BUCKETS[BUCKETS.length - 1];
    }

    // 저장소의 조건 키 — plans.condition_key · challenge_seeds의 PK 일부이자 challenges의
    // 부분 UNIQUE 인덱스 대상이다.
    public String key() {
        return category + KEY_SEPARATOR + durationDays;
    }

    public String title() {
        return category + " " + durationDays + "일 챌린지";
    }
}
