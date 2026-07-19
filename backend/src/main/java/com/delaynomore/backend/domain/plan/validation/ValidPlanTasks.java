package com.delaynomore.backend.domain.plan.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 계획 tasks 맵의 형식 제약 — {날짜(YYYY-MM-DD): [{id, content, completed?}]}.
 * 예전엔 tasks 내부를 opaque로 신뢰해 프론트(normalizeTasks)만 형식을 지켰는데,
 * curl 등 직접 호출은 임의 구조를 저장할 수 있었다. 형식 검증은 DTO 관례(@Valid → 400 +
 * fieldErrors)를 따르고, 내용은 계속 원본 그대로 보관한다(정규화·변조 없음).
 */
@Documented
@Constraint(validatedBy = PlanTasksValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPlanTasks {

    String message() default "계획 내용(tasks)의 형식이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
