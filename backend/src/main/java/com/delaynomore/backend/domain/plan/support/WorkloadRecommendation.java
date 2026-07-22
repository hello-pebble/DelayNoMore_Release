package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.Reflection;

import java.time.LocalDate;
import java.util.ArrayList;
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
            int currentTasksPerDay,      // round(전체 항목수 / 날짜 버킷 수) — 가장 최근 계획 기준
            int recommendedTasksPerDay,  // 규칙 적용 + 안전범위 클램프
            int observedDays,            // 합산 관찰일수(계보 전체, 미래 제외)
            int completedCount,          // 합산 완료 개수
            int totalCount,              // 합산 전체 개수
            int completionRate,          // 관찰 창 한정(미래 제외), 합산 기준 반올림 백분율
            int hardCount,               // 합산 '벅찼어요' 회고 수
            int reflectionCount,         // 합산 회고 수
            String topReasonCode,        // 합산 최빈 회고 이유(enum name), 없으면 null
            boolean insufficientHistory, // 합산 observedDays < 3 → 추천 없이 기존 유지
            int delta,                   // -1 / 0 / +1
            int observedPlanCount        // 합산에 쓴 계획 수(1~3) — 같은 goalName 최근 계획
    ) {
    }

    // 합산 입력 — 한 계획과 그 계획의 회고. 계보(같은 목표 최근 계획들)를 최근-우선으로 넘긴다.
    public record PlanHistory(Plan plan, List<Reflection> reflections) {
    }

    // 단일 계획 호환 진입점 — 합산 버전에 1건짜리 계보로 위임한다(기존 호출·테스트 무변경).
    public static Recommendation compute(Plan plan, List<Reflection> reflections, LocalDate today) {
        return compute(List.of(new PlanHistory(plan, reflections)), today);
    }

    // 같은 목표의 최근 계획들을 합산해 추천을 낸다. lineage는 최근 계획이 index 0. 현재 분량은 가장
    // 최근 계획 기준으로, 완료율·관찰일수·회고는 계보 전체를 합산해 표본을 키운다. 미래 제외는
    // 계획별로 관찰 창(startDate ~ min(endDate, 오늘))을 잡아 적용하고, 회고는 실제 저장된 것만 센다.
    public static Recommendation compute(List<PlanHistory> lineage, LocalDate today) {
        if (lineage == null || lineage.isEmpty()) {
            return new Recommendation(0, 0, 0, 0, 0, 0, 0, 0, null, true, 0, 0);
        }
        Plan latest = lineage.get(0).plan();
        int current = clamp(roundDiv(latest.countAllTasks().total(), dateBucketCount(latest)),
                MIN_TASKS_PER_DAY, MAX_TASKS_PER_DAY);

        String todayKey = today.toString();
        int completed = 0;
        int total = 0;
        int observedDays = 0;
        List<Reflection> allReflections = new ArrayList<>();
        for (PlanHistory history : lineage) {
            Plan plan = history.plan();
            // 관찰 창: 시작일 ~ min(종료일, 오늘). 미래 날짜는 완료율·관찰일수에서 제외한다.
            String to = minKey(plan.endDate(), todayKey);
            Plan.TaskCounts observed = plan.countTasksBetween(plan.startDate(), to);
            completed += observed.completed();
            total += observed.total();
            observedDays += observedBucketCount(plan, to);
            if (history.reflections() != null) {
                allReflections.addAll(history.reflections());
            }
        }
        int rate = computeRate(completed, total);

        // 회고 집계 — 계보 전체 합산, 실제 저장된 것만.
        int reflectionCount = allReflections.size();
        int hardCount = countDifficulty(allReflections, "HARD");
        int easyCount = countDifficulty(allReflections, "EASY");
        int tooMuchCount = countReason(allReflections, "TOO_MUCH_WORK");
        String topReasonCode = topReason(allReflections);

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
                completed, total, rate, hardCount, reflectionCount,
                topReasonCode, insufficient, effectiveDelta, lineage.size());
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
