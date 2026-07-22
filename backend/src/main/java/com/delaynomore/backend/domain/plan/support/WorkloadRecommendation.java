package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.Reflection;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 다음 계획의 하루 분량 추천 — 수행 기록 계산 + 결정 규칙의 단일 소유처(순수 함수, PlanWeeklySummary
 * 스타일의 static 유틸). 추천 숫자는 오직 이 규칙이 정한다 — AI는 이유 설명·내용 생성만 담당하고
 * 숫자를 바꾸지 못한다. 완료율은 미래 날짜를 제외한 관찰 창(startDate ~ min(endDate, 오늘))에서만
 * 계산하고(미래 할 일을 미완료로 세지 않는다), 회고는 실제 저장된 것만 집계한다(없는 날을 임의
 * 처리하지 않는다).
 */
public final class WorkloadRecommendation {

    // 관찰 기간 하한 — 이 미만이면 추천하지 않고 기존 분량을 유지한다.
    private static final int MIN_OBSERVED_DAYS = 3;
    // 완료율 임계값 — 미만이면 분량 감소, 이상(HIGH)이면서 여유 회고 다수면 증가.
    private static final int LOW_RATE = 50;
    private static final int HIGH_RATE = 85;
    // 안전 범위 — 하루 분량은 1~5개, 한 번에 최대 ±1개만 조정한다.
    private static final int MIN_TASKS_PER_DAY = 1;
    private static final int MAX_TASKS_PER_DAY = 5;
    // "분량이 많았어요" 회고가 반복이라고 볼 최소 횟수.
    private static final int TOO_MUCH_REPEAT_THRESHOLD = 2;

    private WorkloadRecommendation() {
    }

    // 추천 결과 — 화면 "지난 계획" 통계와 결정된 다음 분량을 함께 담는다.
    public record Recommendation(
            int currentTasksPerDay,      // round(전체 항목수 / 날짜 버킷 수)
            int recommendedTasksPerDay,  // 규칙 적용 + 안전범위 클램프
            int observedDays,            // startDate ~ min(endDate, 오늘) 사이 날짜 버킷 수
            int completedCount,
            int totalCount,
            int completionRate,          // 관찰 창 한정(미래 제외), 반올림 백분율
            int hardCount,
            int reflectionCount,
            String topReasonCode,        // 최빈 회고 이유(enum name), 없으면 null
            boolean insufficientHistory, // observedDays < 3 → 추천 없이 기존 유지
            int delta                    // -1 / 0 / +1
    ) {
    }

    public static Recommendation compute(Plan plan, List<Reflection> reflections, LocalDate today) {
        int bucketCount = dateBucketCount(plan);
        int current = roundDiv(plan.countAllTasks().total(), bucketCount);
        current = clamp(current, MIN_TASKS_PER_DAY, MAX_TASKS_PER_DAY);

        // 관찰 창: 시작일 ~ min(종료일, 오늘). 미래 날짜는 완료율·관찰일수에서 제외한다.
        String todayKey = today.toString();
        String to = minKey(plan.endDate(), todayKey);
        Plan.TaskCounts observed = plan.countTasksBetween(plan.startDate(), to);
        int rate = computeRate(observed.completed(), observed.total());
        int observedDays = observedBucketCount(plan, to);

        // 회고 집계 — 실제 저장된 것만.
        int reflectionCount = reflections == null ? 0 : reflections.size();
        int hardCount = countDifficulty(reflections, "HARD");
        int easyCount = countDifficulty(reflections, "EASY");
        int tooMuchCount = countReason(reflections, "TOO_MUCH_WORK");
        String topReasonCode = topReason(reflections);

        boolean insufficient = observedDays < MIN_OBSERVED_DAYS;
        int delta = 0;
        if (!insufficient) {
            boolean easyMajority = reflectionCount > 0 && easyCount * 2 >= reflectionCount;
            boolean hardMajority = reflectionCount > 0 && hardCount * 2 >= reflectionCount;
            boolean tooMuchRepeated = tooMuchCount >= TOO_MUCH_REPEAT_THRESHOLD;
            if (rate < LOW_RATE) {
                delta = -1;
            } else if (rate >= HIGH_RATE && easyMajority) {
                delta = 1;
            }
            // 벅찬 회고가 절반 이상이고 분량 과다가 반복되면 감소로 확정(중립·증가 판정을 덮어씀).
            if (hardMajority && tooMuchRepeated) {
                delta = -1;
            }
        }
        int recommended = clamp(current + delta, MIN_TASKS_PER_DAY, MAX_TASKS_PER_DAY);
        // 클램프로 delta가 흡수되면(예: 이미 1개인데 -1) 실제 변화 없음을 delta에도 반영한다.
        int effectiveDelta = recommended - current;

        return new Recommendation(current, recommended, observedDays,
                observed.completed(), observed.total(), rate, hardCount, reflectionCount,
                topReasonCode, insufficient, effectiveDelta);
    }

    // 추천 버튼 노출 조건 — 완료(고정 + 전부 완료)했거나 3일 이상 실행한 계획. 규칙을 서버가 소유해
    // 프론트는 이 플래그(PlanResponse.recommendationEligible)만 본다.
    public static boolean isEligible(Plan plan, LocalDate today, Plan.TaskCounts counts) {
        boolean completed = plan.isConfirmed() && counts.total() > 0 && counts.completed() == counts.total();
        return completed || observedBucketCount(plan, today.toString()) >= MIN_OBSERVED_DAYS;
    }

    // plan.tasks의 날짜 버킷(값이 List인 ISO 날짜 키) 수 — 하루 평균 계산의 분모.
    private static int dateBucketCount(Plan plan) {
        if (plan.tasks() == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : plan.tasks().entrySet()) {
            if (PlanDates.isIsoDate(entry.getKey()) && entry.getValue() instanceof List<?>) {
                count++;
            }
        }
        return count;
    }

    // to(포함) 이하인 날짜 버킷 수 — 관찰일수(미래 제외). to가 null이면 0.
    private static int observedBucketCount(Plan plan, String to) {
        if (plan.tasks() == null || to == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : plan.tasks().entrySet()) {
            String key = entry.getKey();
            if (PlanDates.isIsoDate(key) && entry.getValue() instanceof List<?> && key.compareTo(to) <= 0) {
                count++;
            }
        }
        return count;
    }

    private static int countDifficulty(List<Reflection> reflections, String difficulty) {
        if (reflections == null) {
            return 0;
        }
        int count = 0;
        for (Reflection r : reflections) {
            if (difficulty.equals(r.difficulty())) {
                count++;
            }
        }
        return count;
    }

    private static int countReason(List<Reflection> reflections, String reason) {
        if (reflections == null) {
            return 0;
        }
        int count = 0;
        for (Reflection r : reflections) {
            if (reason.equals(r.reason())) {
                count++;
            }
        }
        return count;
    }

    // 최빈 회고 이유(enum name). 동률이면 먼저 만난 이유. 회고가 없거나 이유가 전부 null이면 null.
    private static String topReason(List<Reflection> reflections) {
        if (reflections == null || reflections.isEmpty()) {
            return null;
        }
        String best = null;
        int bestCount = 0;
        for (Reflection candidate : reflections) {
            String reason = candidate.reason();
            if (reason == null) {
                continue;
            }
            int count = 0;
            for (Reflection r : reflections) {
                if (reason.equals(r.reason())) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = reason;
            }
        }
        return best;
    }

    private static int computeRate(int done, int total) {
        return total > 0 ? Math.round(done * 100f / total) : 0;
    }

    // 반올림 나눗셈(분모 0이면 0). 하루 평균 항목 수 산출용.
    private static int roundDiv(int numerator, int denominator) {
        return denominator > 0 ? Math.round(numerator / (float) denominator) : 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // 두 ISO 날짜 문자열(사전식=시간순) 중 이른 것. 한쪽이 null이면 다른 쪽.
    private static String minKey(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }
}
