package com.delaynomore.backend.domain.plan.support;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.global.time.KstDates;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 없이도 추천 초안을 만들 수 있는 서버측 최소 생성기. AI 호출이 실패하거나(키 미설정·업스트림
 * 오류) 정확 개수 검증에 걸릴 때의 폴백으로, 원본 계획의 목표를 이어받아 KST 오늘부터 duration일
 * 동안 매일 정확히 tasksPerDay개의 유효한 할 일({id, content, completed})을 채운다. 내용은
 * 일반적(자리표시)이지만 형식은 저장·미리보기에 그대로 유효하다 — "AI가 실패해도 추천 기능은
 * 서버만으로 끝까지 동작한다"는 요구를 만족시키는 최후 보루다(1차 경로는 AI 생성).
 */
@Component
public class TemplatePlanGenerator {

    // 초안 생성 상한(일) — AiDraftRequest.fromSource의 클램프와 같은 값으로 맞춰 두 경로가 같은
    // 날짜 수를 만들게 한다(정확 개수 검증·미리보기 일관성).
    private static final int MAX_DURATION = 14;

    public Map<String, Object> generate(Plan source, int tasksPerDay) {
        int duration = clampDuration(source.duration());
        LocalDate today = KstDates.today();
        Map<String, Object> plan = new LinkedHashMap<>();
        for (int dayIndex = 0; dayIndex < duration; dayIndex++) {
            String date = today.plusDays(dayIndex).toString();
            List<Map<String, Object>> items = new ArrayList<>();
            for (int k = 0; k < tasksPerDay; k++) {
                items.add(Map.of(
                        "id", "t-" + date + "-" + k,
                        "content", source.goalName() + " — " + (dayIndex + 1) + "일차 학습 " + (k + 1),
                        "completed", false));
            }
            plan.put(date, items);
        }
        return plan;
    }

    private static int clampDuration(Integer duration) {
        int value = duration == null ? 1 : duration;
        return Math.max(1, Math.min(MAX_DURATION, value));
    }
}
