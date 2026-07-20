package com.delaynomore.backend.domain.plan.validation;

import com.delaynomore.backend.domain.plan.support.PlanDates;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.Map;

/**
 * tasks 형식 검증 구현. 규칙:
 * - 날짜 키: LocalDate.parse가 받는 엄격한 YYYY-MM-DD ("2026-7-1" 거부 — 프론트는 항상 패딩된
 *   형식만 생성한다: todayStr/getFormattedDate).
 * - 값: 배열(List). 빈 배열은 허용(프론트는 빈 날을 키 삭제로 처리하지만 관용).
 * - 항목: 객체(Map)이면서 id/content가 비어 있지 않은 문자열. completed는 부재 허용(소비 코드가
 *   부재=false로 해석), 존재하면 Boolean만. 그 외 알 수 없는 키는 허용(전방 호환).
 * - null/빈 맵은 통과 — @NotEmpty가 담당한다(중복 메시지 방지).
 */
public class PlanTasksValidator implements ConstraintValidator<ValidPlanTasks, Map<String, Object>> {

    @Override
    public boolean isValid(Map<String, Object> tasks, ConstraintValidatorContext context) {
        if (tasks == null || tasks.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> dayEntry : tasks.entrySet()) {
            String date = dayEntry.getKey();
            if (!PlanDates.isIsoDate(date)) {
                return violation(context, "tasks의 날짜 키는 YYYY-MM-DD 형식이어야 합니다: '" + safe(date) + "'");
            }
            if (!(dayEntry.getValue() instanceof List<?> list)) {
                return violation(context, "tasks의 '" + date + "' 값은 할 일 배열이어야 합니다.");
            }
            for (Object item : list) {
                String problem = itemProblem(item);
                if (problem != null) {
                    return violation(context, "tasks의 '" + date + "' 항목이 올바르지 않습니다: " + problem);
                }
            }
        }
        return true;
    }

    // 항목 하나의 형식 문제를 설명 문자열로 돌려준다(문제 없으면 null).
    private static String itemProblem(Object item) {
        if (!(item instanceof Map<?, ?> task)) {
            return "각 할 일은 id와 content를 가진 객체여야 합니다.";
        }
        if (!isNonBlankString(task.get("id"))) {
            return "id는 비어 있지 않은 문자열이어야 합니다.";
        }
        if (!isNonBlankString(task.get("content"))) {
            return "content는 비어 있지 않은 문자열이어야 합니다.";
        }
        Object completed = task.get("completed");
        if (completed != null && !(completed instanceof Boolean)) {
            return "completed는 true/false여야 합니다.";
        }
        return null;
    }

    private static boolean isNonBlankString(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    private static boolean violation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }

    // 템플릿 메시지에 끼우는 사용자 입력 정리 — {}·$·\ 는 Bean Validation 메시지 보간 문법이라
    // 제거하고(임의 표현식 평가 방지), 표시용이므로 길이도 짧게 자른다.
    private static String safe(String raw) {
        if (raw == null) return "null";
        String cleaned = raw.replaceAll("[{}$\\\\]", "");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) + "…" : cleaned;
    }
}
