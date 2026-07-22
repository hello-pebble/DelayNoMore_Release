package com.delaynomore.backend.domain.plan.dto;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.support.PlanDates;

import java.util.Map;

/**
 * 추천 초안(미리보기) 응답 — 아직 저장하지 않은 계획이다. 목표·수준은 원본에서 승계하고, 날짜·기간은
 * 생성된 tasks에서 서버가 산출한다. aiUsed=false면 AI 실패로 서버 템플릿이 채운 초안이다.
 */
public record RecommendationDraftResponse(
        long sourcePlanId,
        String goalName,
        int duration,
        Integer dailyHours,
        String currentLevel,
        String startDate,
        String endDate,
        int tasksPerDay,
        Map<String, Object> tasks,   // {날짜: [{id, content, completed}]}
        boolean aiUsed
) {

    public static RecommendationDraftResponse from(Plan source, int tasksPerDay,
                                                   Map<String, Object> tasks, boolean aiUsed) {
        String startDate = PlanDates.minTaskKey(tasks);
        String endDate = PlanDates.maxTaskKey(tasks);
        int duration = PlanDates.spanDays(startDate, endDate);
        return new RecommendationDraftResponse(source.id(), source.goalName(), duration,
                source.dailyHours(), source.currentLevel(), startDate, endDate, tasksPerDay, tasks, aiUsed);
    }
}
