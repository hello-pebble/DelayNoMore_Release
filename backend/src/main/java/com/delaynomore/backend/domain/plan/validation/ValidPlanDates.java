package com.delaynomore.backend.domain.plan.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 계획 날짜의 교차필드 제약 — endDate가 ISO(YYYY-MM-DD)이고 tasks의 마지막 날짜 키 이상인지 검증.
 * startDate·duration은 서버가 tasks에서 산출하므로(PlanService) 검증 대상이 아니다.
 * @ValidPlanTasks가 FIELD 레벨인 것과 달리 endDate↔tasks를 함께 봐야 해 TYPE(record) 레벨이다.
 * 위반은 DTO 관례(@Valid → 400 + fieldErrors)를 따른다.
 */
@Documented
@Constraint(validatedBy = PlanDatesValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPlanDates {

    String message() default "계획 날짜(endDate)가 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
