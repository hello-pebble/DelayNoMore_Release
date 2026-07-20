package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.support.PlanWeeklySummary;

import java.util.List;

// 주간 완료율 요약 응답. 완료 개수는 plan.tasks(라이브 상태)에서 서버가 계산한다 — PlanResponse.progress와
// 같은 소스·소유권. 계획을 startDate 기준 7일 버킷("N주차")으로 묶어 주별 done/total/rate를 내린다.
public record WeeklySummaryResponse(
        long planId,
        String startDate,
        String endDate,
        int totalDone,
        int totalTotal,
        List<Week> weeks
) {

    // 한 주(N주차)의 완료율. rate = 완료/전체 백분율(반올림), 전체 0이면 0.
    public record Week(int index, String startDate, String endDate, int done, int total, int rate) {

        static Week of(PlanWeeklySummary.WeekBucket bucket, Plan.TaskCounts counts) {
            return new Week(bucket.index(), bucket.from(), bucket.to(),
                    counts.completed(), counts.total(), computeRate(counts.completed(), counts.total()));
        }
    }

    public static WeeklySummaryResponse from(Plan plan) {
        List<Week> weeks = PlanWeeklySummary.buckets(plan.startDate(), plan.endDate()).stream()
                .map(bucket -> Week.of(bucket, plan.countTasksBetween(bucket.from(), bucket.to())))
                .toList();
        Plan.TaskCounts all = plan.countAllTasks();
        return new WeeklySummaryResponse(plan.id(), plan.startDate(), plan.endDate(),
                all.completed(), all.total(), weeks);
    }

    private static int computeRate(int done, int total) {
        return total > 0 ? Math.round(done * 100f / total) : 0;
    }
}
