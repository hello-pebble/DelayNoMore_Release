package com.delaynomore.backend.domain.slack.service;

import com.delaynomore.backend.domain.plan.dto.TodayDashboardResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 슬랙 메시지 텍스트 조립. 체크리스트의 작업 번호는 "계획 id 오름차순 → 작업 원순서"의
 * 전역 번호 1..N이다 — 번호의 소스오브트루스는 이 정렬 규칙이지 저장값이 아니며,
 * 멘션 자연어 해석(v0.27.0 SlackCommandService)이 {@link #numberTasks}로 같은 번호를
 * 재현해 taskId를 찾는다. 발송 텍스트와 해석 문맥이 같은 목록을 쓰므로 어긋날 수 없다.
 */
@Component
public class SlackMessageComposer {

    /** 전역 번호가 매겨진 작업 한 줄 — 발송 텍스트와 의도 해석 문맥의 공용 모델. */
    public record NumberedTask(int number, long planId, String taskId, String goalName,
                               String content, boolean completed) {
    }

    /** 정렬 규칙(계획 id 오름차순 → 작업 원순서)으로 오늘 작업에 번호를 매긴다. */
    public List<NumberedTask> numberTasks(TodayDashboardResponse dashboard) {
        List<NumberedTask> numbered = new ArrayList<>();
        int number = 1;
        List<TodayDashboardResponse.PlanItem> plans = dashboard.plans().stream()
                .sorted(Comparator.comparingLong(item -> item.plan().id()))
                .toList();
        for (TodayDashboardResponse.PlanItem item : plans) {
            for (Object task : item.tasks()) {
                if (!(task instanceof Map<?, ?> map)) {
                    continue;
                }
                numbered.add(new NumberedTask(number++, item.plan().id(),
                        String.valueOf(map.get("id")), item.plan().goalName(),
                        String.valueOf(map.get("content")),
                        Boolean.TRUE.equals(map.get("completed"))));
            }
        }
        return numbered;
    }

    public String composeChecklist(TodayDashboardResponse dashboard) {
        StringBuilder text = new StringBuilder();
        LocalDate date = LocalDate.parse(dashboard.date());
        text.append("📋 *").append(date.getMonthValue()).append("월 ").append(date.getDayOfMonth())
                .append("일 오늘 할 일* — ").append(dashboard.done()).append('/')
                .append(dashboard.total()).append(" 완료\n");

        String currentGoal = null;
        for (NumberedTask task : numberTasks(dashboard)) {
            if (!task.goalName().equals(currentGoal)) {
                currentGoal = task.goalName();
                text.append("\n*").append(currentGoal).append("*\n");
            }
            text.append(task.number()).append(". ").append(task.completed() ? "✅" : "⬜").append(' ')
                    .append(task.content()).append('\n');
        }
        return text.toString();
    }
}
