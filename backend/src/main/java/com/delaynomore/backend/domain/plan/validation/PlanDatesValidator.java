package com.delaynomore.backend.domain.plan.validation;

import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.support.PlanDates;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * ValidPlanDates 구현 — endDate 형식·범위 검증(startDate·duration은 서버 산출이라 검증 안 함).
 * - endDate가 있고 비ISO → "endDate는 YYYY-MM-DD 형식이어야 합니다"
 * - endDate가 있고 tasks의 마지막 날짜 키보다 앞서면 → "endDate가 마지막 할 일 날짜보다 앞설 수 없습니다"
 * - endDate/tasks가 null이면 통과 — 각각 선택 필드/@NotEmpty가 담당(중복 메시지 방지).
 * 메시지는 endDate 필드 노드에 귀속시켜 fieldErrors.endDate로 노출한다.
 */
public class PlanDatesValidator implements ConstraintValidator<ValidPlanDates, PlanSaveRequest> {

    @Override
    public boolean isValid(PlanSaveRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        String endDate = request.endDate();
        if (endDate == null) {
            return true; // endDate는 선택 필드 — 부재는 형식 위반이 아니다(있으면 서버가 검증).
        }
        if (!PlanDates.isIsoDate(endDate)) {
            return violation(context, "endDate는 YYYY-MM-DD 형식이어야 합니다.");
        }
        // maxTaskKey는 tasks 형식 검증(@ValidPlanTasks)과 무관하게 방어적으로 최대 ISO 키를 찾는다.
        // 포함 규칙: 마지막 할 일 날짜는 종료일 안에 있어야 한다(endDate >= max 날짜 키).
        String maxKey = PlanDates.maxTaskKey(request.tasks());
        if (maxKey != null && endDate.compareTo(maxKey) < 0) {
            return violation(context, "endDate가 마지막 할 일 날짜보다 앞설 수 없습니다.");
        }
        return true;
    }

    // 메시지에 사용자 입력을 끼우지 않으므로(정적 문자열) 보간 인젝션 정리(safe)가 필요 없다.
    private static boolean violation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode("endDate")
                .addConstraintViolation();
        return false;
    }
}
