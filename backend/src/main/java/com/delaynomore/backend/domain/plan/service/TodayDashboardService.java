package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.ReflectionResponse;
import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TodayDashboardService {

    private final PlanRepository planRepository;
    private final ReflectionRepository reflectionRepository;

    public TodayDashboardResponse get(String owner) {
        String today = KstDates.today().toString();
        List<TodayDashboardResponse.PlanItem> plans = planRepository.findAllByOwner(owner).stream()
                .map(plan -> toItem(plan, today))
                .filter(item -> item.total() > 0)
                .toList();
        int done = plans.stream().mapToInt(TodayDashboardResponse.PlanItem::done).sum();
        int total = plans.stream().mapToInt(TodayDashboardResponse.PlanItem::total).sum();
        return new TodayDashboardResponse(today, done, total, plans);
    }

    private TodayDashboardResponse.PlanItem toItem(Plan plan, String today) {
        Plan.TaskCounts todayCounts = plan.countTasksOn(today);
        ReflectionResponse reflection = reflectionRepository.findByPlanIdAndDate(plan.id(), today)
                .map(ReflectionResponse::from)
                .orElse(null);
        Plan.TaskCounts allCounts = plan.countAllTasks();
        boolean completionEligible = plan.statusOrDraft() == PlanStatus.CONFIRMED
                && ((plan.endDate() != null && today.compareTo(plan.endDate()) >= 0)
                || (allCounts.total() > 0 && allCounts.completed() == allCounts.total()));
        List<Object> tasks = plan.tasks() != null && plan.tasks().get(today) instanceof List<?> list
                ? new java.util.ArrayList<>(list)
                : List.of();
        return new TodayDashboardResponse.PlanItem(PlanResponse.from(plan), tasks,
                todayCounts.completed(), todayCounts.total(), reflection, completionEligible);
    }
}
