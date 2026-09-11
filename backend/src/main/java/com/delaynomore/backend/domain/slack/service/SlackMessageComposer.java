package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 슬랙 메시지 텍스트 조립. 체크리스트의 작업 번호는 "계획 id 오름차순 → 작업 원순서"의
 * 전역 번호 1..N이다 — 번호의 소스오브트루스는 이 정렬 규칙이지 저장값이 아니며,
 * 멘션 자연어 해석(v0.27 예정)이 같은 규칙으로 번호를 재현해 taskId를 찾는다.
 */
@Component
public class SlackMessageComposer {

    public String composeChecklist(TodayDashboardResponse dashboard) {
        StringBuilder text = new StringBuilder();
        LocalDate date = LocalDate.parse(dashboard.date());
        text.append("📋 *").append(date.getMonthValue()).append("월 ").append(date.getDayOfMonth())
                .append("일 오늘 할 일* — ").append(dashboard.done()).append('/')
                .append(dashboard.total()).append(" 완료\n");

        int number = 1;
        List<TodayDashboardResponse.PlanItem> plans = dashboard.plans().stream()
                .sorted(Comparator.comparingLong(item -> item.plan().id()))
                .toList();
        for (TodayDashboardResponse.PlanItem item : plans) {
            text.append("\n*").append(item.plan().goalName()).append("*\n");
            for (Object task : item.tasks()) {
                if (!(task instanceof Map<?, ?> map)) {
                    continue;
                }
                boolean completed = Boolean.TRUE.equals(map.get("completed"));
                text.append(number++).append(". ").append(completed ? "✅" : "⬜").append(' ')
                        .append(String.valueOf(map.get("content"))).append('\n');
            }
        }
        return text.toString();
    }
}
