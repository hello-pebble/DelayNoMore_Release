package com.delaynomore.backend.domain.plan.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 이월 순수 연산 단위 테스트 — 날짜를 고정해 결정적으로 돌린다(오늘/내일 판정은 PlanService 몫).
// 의미는 프론트 carryOverTasks와 동일해야 한다: 미완료만 이동, ID 보존, 날짜 키 오름차순.
class PlanCarryOverTest {

    private static final String FROM = "2026-07-16";
    private static final String TO = "2026-07-17";

    private static Map<String, Object> task(String id, String content, boolean completed) {
        return Map.of("id", id, "content", content, "completed", completed);
    }

    @Test
    void apply_미완료만이동_완료잔류및ID보존() {
        // given
        Map<String, Object> tasks = Map.of(
                FROM, List.of(task("t-1", "단어 암기", false), task("t-2", "문법 정리", true)),
                TO, List.of(task("t-3", "듣기 연습", false)));

        // when
        PlanCarryOver.Result result = PlanCarryOver.apply(tasks, FROM, TO);

        // then — 미완료 t-1만 목적지 뒤에 붙고, ID는 그대로(변경 이력이 "같은 할 일"로 인식)
        assertThat(result.movedCount()).isEqualTo(1);
        assertThat(result.tasks().get(FROM)).isEqualTo(List.of(task("t-2", "문법 정리", true)));
        assertThat(result.tasks().get(TO))
                .isEqualTo(List.of(task("t-3", "듣기 연습", false), task("t-1", "단어 암기", false)));
    }

    @Test
    void apply_이동후_날짜키오름차순() {
        // given — 목적지 키가 없던 경우, 끝에 붙지 않고 날짜순 위치에 들어가야 한다(Day 순서 보존)
        Map<String, Object> tasks = Map.of(
                FROM, List.of(task("t-1", "단어 암기", false)),
                "2026-07-18", List.of(task("t-9", "총정리", false)));

        // when
        PlanCarryOver.Result result = PlanCarryOver.apply(tasks, FROM, TO);

        // then
        assertThat(result.tasks().keySet()).containsExactly(TO, "2026-07-18");
    }

    @Test
    void apply_목적지ID충돌_접미사회피() {
        // given — 목적지에 같은 ID가 이미 있는 비정상 데이터
        Map<String, Object> tasks = Map.of(
                FROM, List.of(task("t-1", "단어 암기", false)),
                TO, List.of(task("t-1", "다른 할 일", false)));

        // when
        PlanCarryOver.Result result = PlanCarryOver.apply(tasks, FROM, TO);

        // then — 이동분의 ID에 -m<ts> 접미사가 붙어 프론트 렌더 key 충돌을 피한다
        List<?> destination = (List<?>) result.tasks().get(TO);
        assertThat(destination).hasSize(2);
        Map<?, ?> movedItem = (Map<?, ?>) destination.get(1);
        assertThat((String) movedItem.get("id")).startsWith("t-1-m");
        assertThat(movedItem.get("content")).isEqualTo("단어 암기");
    }

    @Test
    void apply_전부완료_0건원본유지() {
        // given
        Map<String, Object> tasks = Map.of(FROM, List.of(task("t-1", "단어 암기", true)));

        // when
        PlanCarryOver.Result result = PlanCarryOver.apply(tasks, FROM, TO);

        // then — no-op: 원본 객체 그대로
        assertThat(result.movedCount()).isZero();
        assertThat(result.tasks()).isSameAs(tasks);
    }

    @Test
    void apply_원본날짜값이배열아님_0건방어() {
        // given — tasks는 프론트 원본 그대로 보관된 opaque 구조라 방어적으로 다룬다
        Map<String, Object> tasks = Map.of(FROM, "이상한 값");

        // when
        PlanCarryOver.Result result = PlanCarryOver.apply(tasks, FROM, TO);

        // then
        assertThat(result.movedCount()).isZero();
        assertThat(result.tasks()).isSameAs(tasks);
    }

    @Test
    void apply_전부이동_원본날짜키제거() {
        // given
        Map<String, Object> tasks = Map.of(FROM, List.of(task("t-1", "단어 암기", false)));

        // when
        PlanCarryOver.Result result = PlanCarryOver.apply(tasks, FROM, TO);

        // then — 빈 날은 빈 배열이 아니라 키 삭제(프론트 동작 그대로)
        assertThat(result.movedCount()).isEqualTo(1);
        assertThat(result.tasks()).doesNotContainKey(FROM);
        assertThat(result.tasks().get(TO)).isEqualTo(List.of(task("t-1", "단어 암기", false)));
    }
}
