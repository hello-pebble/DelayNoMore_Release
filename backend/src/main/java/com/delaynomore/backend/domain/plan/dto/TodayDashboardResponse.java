package com.delaynomore.backend.domain.plan.dto;

import java.util.List;

/** Read model for the Today screen. It keeps the UI from composing plans and reflections itself. */
public record TodayDashboardResponse(
        String date,
        int done,
        int total,
        List<PlanItem> plans
) {
    public record PlanItem(
            PlanResponse plan,
            List<Object> tasks,
            int done,
            int total,
            ReflectionResponse reflection,
            boolean completionEligible
    ) {
    }
}
