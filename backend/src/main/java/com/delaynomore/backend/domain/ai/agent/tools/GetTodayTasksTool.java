package com.delaynomore.backend.domain.ai.agent.tools;

import com.delaynomore.backend.domain.ai.agent.AgentContext;
import com.delaynomore.backend.domain.ai.agent.AgentTool;
import com.delaynomore.backend.domain.ai.agent.ToolResult;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.service.PlanService;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 특정 날짜(기본 오늘, KST)의 할 일과 완료 상태를 읽는다. "오늘 뭐 해야 해?", "어제 몇 개
 * 끝냈어?" 류 질문에 모델이 계획 원문을 추측하지 않고 실제 데이터를 인용하게 하는 도구.
 *
 * 날짜 기준은 반드시 {@link KstDates}를 쓴다 — 컨테이너 JVM은 UTC라 LocalDate.now()를 쓰면
 * KST 자정~오전 9시에 하루가 어긋난다(v0.8.1에서 잡은 결함과 같은 함정).
 */
@Component
@RequiredArgsConstructor
public class GetTodayTasksTool implements AgentTool {

    private final PlanService planService;

    @Override
    public String name() {
        return "get_today_tasks";
    }

    @Override
    public String description() {
        return "Read the tasks and their completion state for one date of the current plan "
                + "(defaults to today in KST). Use this before answering anything about what the "
                + "user has to do or has already finished on a given day.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("date", Map.of(
                        "type", "string",
                        "description", "Target date in YYYY-MM-DD. Omit for today (KST).")),
                "required", List.of());
    }

    @Override
    public ToolResult execute(JsonNode args, AgentContext context) {
        String date = ToolArgs.text(args, "date");
        if (date == null) {
            date = KstDates.today().toString();
        } else {
            try {
                date = LocalDate.parse(date).toString(); // "2026-7-1" 같은 비정규 표기를 정규화
            } catch (DateTimeParseException e) {
                return ToolResult.fail("date는 YYYY-MM-DD 형식이어야 합니다: " + date);
            }
        }

        Map<String, Object> tasks = resolveTasks(context);
        if (tasks == null) {
            return ToolResult.fail("계획을 찾을 수 없습니다.");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int done = 0;
        if (tasks.get(date) instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> task)) continue;
                boolean completed = Boolean.TRUE.equals(task.get("completed"));
                if (completed) done++;
                items.add(Map.of("content", String.valueOf(task.get("content")), "completed", completed));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", date);
        payload.put("isToday", date.equals(KstDates.today().toString()));
        payload.put("tasks", items);
        payload.put("doneCount", done);
        payload.put("totalCount", items.size());
        return ToolResult.ok(payload);
    }

    /**
     * 계획 원문의 출처. 보관된 계획이면 서버 저장본을 읽어 "화면에 보이는 것"이 아니라 "서버가
     * 아는 것"을 인용하게 하고, 아직 보관 전 초안(planId 없음)이면 요청에 실려온 현재 초안을 쓴다.
     * 소유자는 항상 context.owner()다 — 모델 인자로 소유자를 받지 않는다.
     */
    private Map<String, Object> resolveTasks(AgentContext context) {
        if (!context.hasPlanId()) {
            return context.currentTasks();
        }
        try {
            PlanResponse plan = planService.getPlan(context.planId(), context.owner());
            return plan.tasks() == null ? Map.of() : plan.tasks();
        } catch (BusinessException e) {
            return null; // 삭제됐거나 남의 계획 — 존재를 숨기는 서비스 규약을 그대로 따른다
        }
    }
}
