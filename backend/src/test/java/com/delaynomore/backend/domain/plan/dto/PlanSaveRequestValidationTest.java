package com.delaynomore.backend.domain.plan.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// DTO 형식 검증(@Valid → 400 + fieldErrors) 단위 테스트 — Spring 컨텍스트 없이 순수 Validator로 돌린다.
// tasks 구조(@ValidPlanTasks)와 status(@Pattern)는 예전엔 서버가 opaque로 신뢰하던 영역이라,
// 프론트가 실제로 보내는 페이로드가 계속 통과하는지(호환)와 임의 구조가 거부되는지를 함께 본다.
class PlanSaveRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static Map<String, Object> task(String id, String content, Object completed) {
        Map<String, Object> task = new HashMap<>();
        task.put("id", id);
        task.put("content", content);
        if (completed != null) {
            task.put("completed", completed);
        }
        return task;
    }

    private static PlanSaveRequest requestWithTasks(Map<String, Object> tasks) {
        return new PlanSaveRequest("토익 900", 3, 2, "완전 초보", tasks,
                "DRAFT", null, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z");
    }

    private static PlanSaveRequest requestWithStatus(String status) {
        Map<String, Object> tasks = Map.of("2026-07-16", List.of(task("t-1", "단어 암기", false)));
        return new PlanSaveRequest("토익 900", 3, 2, "완전 초보", tasks,
                status, null, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z");
    }

    private Set<ConstraintViolation<PlanSaveRequest>> violations(PlanSaveRequest request) {
        return validator.validate(request);
    }

    @Test
    void tasks_프론트실제페이로드_통과() {
        // given — 프론트가 만드는 형태: t-<날짜>-<idx> id, completed 포함/생략, 이월 접미사(-m<ts>)
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(
                        task("t-2026-07-16-0", "단어 암기", true),
                        task("chk-srv-3", "문법 정리", null)),
                "2026-07-17", List.of(task("t-2026-07-16-1-m1752900000000", "듣기 연습", false)));

        // when / then
        assertThat(violations(requestWithTasks(tasks))).isEmpty();
    }

    @Test
    void tasks_빈배열인날짜_관용통과() {
        // given — 프론트는 빈 날을 키 삭제로 처리하지만, 빈 배열 자체는 형식 위반이 아니다
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", false)),
                "2026-07-17", List.of());

        // when / then
        assertThat(violations(requestWithTasks(tasks))).isEmpty();
    }

    @Test
    void tasks_비날짜키_위반() {
        // given — LLM 저하 응답이 만들던 "Day 1" 키와 비패딩 날짜
        for (String badKey : List.of("Day 1", "2026-7-1")) {
            Map<String, Object> tasks = Map.of(badKey, List.of(task("t-1", "단어 암기", false)));

            // when
            Set<ConstraintViolation<PlanSaveRequest>> violations = violations(requestWithTasks(tasks));

            // then
            assertThat(violations).as("키: %s", badKey).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("tasks");
        }
    }

    @Test
    void tasks_값이배열이아님_위반() {
        // given
        Map<String, Object> tasks = Map.of("2026-07-16", "단어 암기");

        // when / then
        assertThat(violations(requestWithTasks(tasks))).hasSize(1);
    }

    @Test
    void tasks_항목형식위반_각각거부() {
        // given — id 누락 / content 공백 / completed 가 문자열
        List<Map<String, Object>> badItems = List.of(
                Map.of("content", "단어 암기"),
                task("t-1", " ", false),
                task("t-1", "단어 암기", "true"));

        for (Map<String, Object> badItem : badItems) {
            Map<String, Object> tasks = Map.of("2026-07-16", List.of(badItem));

            // when / then
            assertThat(violations(requestWithTasks(tasks))).as("항목: %s", badItem).hasSize(1);
        }
    }

    @Test
    void tasks_항목이객체가아님_위반() {
        // given — 문자열 배열(초안 응답 형태)은 보관 형식으로는 허용하지 않는다
        Map<String, Object> tasks = Map.of("2026-07-16", List.of("단어 암기"));

        // when / then
        assertThat(violations(requestWithTasks(tasks))).hasSize(1);
    }

    @Test
    void tasks_알수없는추가키_관용통과() {
        // given — 전방 호환: 항목에 미지의 필드가 있어도 통과
        Map<String, Object> item = new HashMap<>(task("t-1", "단어 암기", false));
        item.put("priority", "high");
        Map<String, Object> tasks = Map.of("2026-07-16", List.of(item));

        // when / then
        assertThat(violations(requestWithTasks(tasks))).isEmpty();
    }

    @Test
    void status_DRAFT와CONFIRMED_통과() {
        assertThat(violations(requestWithStatus("DRAFT"))).isEmpty();
        assertThat(violations(requestWithStatus("CONFIRMED"))).isEmpty();
    }

    @Test
    void status_null_통과_toPlan에서DRAFT보정() {
        // given
        PlanSaveRequest request = requestWithStatus(null);

        // when / then — @Pattern은 null을 통과시키고, toPlan이 DRAFT 기본값을 준다
        assertThat(violations(request)).isEmpty();
        assertThat(request.toPlan(1L, 0L, "2026-07-16", 1).status()).isEqualTo("DRAFT");
    }

    @Test
    void status_허용외값_위반() {
        for (String badStatus : List.of("confirmed", "ARCHIVED", "")) {
            // when
            Set<ConstraintViolation<PlanSaveRequest>> violations = violations(requestWithStatus(badStatus));

            // then
            assertThat(violations).as("status: %s", badStatus).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("status");
        }
    }

    // === 날짜 규칙(@ValidPlanDates) — endDate 형식·범위 검증(startDate·duration은 서버 산출) ===

    @Test
    void endDate_마지막할일날짜보다앞_위반() {
        // given — 마지막 할 일은 07-18인데 endDate가 07-17이면 포함 규칙 위반
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", false)),
                "2026-07-18", List.of(task("t-2", "총정리", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 3, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-17", "2026-07-16T00:00:00Z");

        // when
        Set<ConstraintViolation<PlanSaveRequest>> violations = violations(request);

        // then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("endDate");
    }

    @Test
    void endDate_비ISO형식_위반() {
        // given — 비패딩 날짜(프론트는 항상 패딩된 형식만 생성)
        Map<String, Object> tasks = Map.of("2026-07-16", List.of(task("t-1", "단어 암기", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 1, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-7-1", "2026-07-16T00:00:00Z");

        // when
        Set<ConstraintViolation<PlanSaveRequest>> violations = violations(request);

        // then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("endDate");
    }

    @Test
    void endDate_마지막할일날짜와같음_단일일_통과() {
        // given — 하루짜리 계획(시작=종료=유일한 할 일 날짜)
        Map<String, Object> tasks = Map.of("2026-07-16", List.of(task("t-1", "총정리", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 1, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-16", "2026-07-16T00:00:00Z");

        // when / then
        assertThat(violations(request)).isEmpty();
    }

    @Test
    void endDate_날짜갭있지만마지막할일이내_통과() {
        // given — 중간 날짜가 비어도(키 갭) endDate가 마지막 할 일 날짜 이상이면 유효(상한만 검증)
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", false)),
                "2026-07-19", List.of(task("t-2", "총정리", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 5, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-20", "2026-07-16T00:00:00Z");

        // when / then
        assertThat(violations(request)).isEmpty();
    }

    @Test
    void duration_보관상한365_경계값회귀() {
        // given — 이월/기간연장으로 14일을 넘길 수 있어 보관 상한은 느슨하다(1~365)
        Map<String, Object> tasks = Map.of("2026-07-16", List.of(task("t-1", "단어 암기", false)));

        PlanSaveRequest at365 = new PlanSaveRequest("토익 900", 365, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2027-07-15", "2026-07-16T00:00:00Z");
        PlanSaveRequest over = new PlanSaveRequest("토익 900", 366, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2027-07-16", "2026-07-16T00:00:00Z");

        // when / then
        assertThat(violations(at365)).isEmpty();
        assertThat(violations(over)).hasSize(1);
    }
}
