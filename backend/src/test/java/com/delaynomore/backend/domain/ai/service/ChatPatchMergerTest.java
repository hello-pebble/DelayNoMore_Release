package com.delaynomore.backend.domain.ai.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// LLM 채팅 patch 병합의 단위 테스트 — 예전에 프론트(applyPlanPatch+carryOverCompleted)가 하던
// 병합·완료 보존 규칙이 서버로 그대로 이관됐는지 검증한다.
class ChatPatchMergerTest {

    private static Map<String, Object> task(String id, String content, boolean completed) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("content", content);
        t.put("completed", completed);
        return t;
    }

    @Test
    void merge_새날짜추가_문자열을객체로lift() {
        // given
        Map<String, Object> current = Map.of(
                "2026-07-16", List.of(task("t-2026-07-16-0", "단어 암기", false)));
        Map<String, Object> patch = Map.of("2026-07-17", List.of("듣기 연습", "문법 정리"));

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then
        assertThat(merged).containsKeys("2026-07-16", "2026-07-17");
        assertThat(merged.get("2026-07-17")).isEqualTo(List.of(
                task("t-2026-07-17-0", "듣기 연습", false),
                task("t-2026-07-17-1", "문법 정리", false)));
    }

    @Test
    void merge_기존날짜교체_새id로재구성() {
        // given
        Map<String, Object> current = Map.of(
                "2026-07-16", List.of(task("t-old", "예전 내용", false)));
        Map<String, Object> patch = Map.of("2026-07-16", List.of("새 내용"));

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then
        assertThat(merged.get("2026-07-16")).isEqualTo(List.of(task("t-2026-07-16-0", "새 내용", false)));
    }

    @Test
    void merge_값이null인날짜_삭제() {
        // given — 기간 단축 등
        Map<String, Object> current = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", false)),
                "2026-07-17", List.of(task("t-2", "듣기 연습", false)));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("2026-07-17", null);

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then
        assertThat(merged).containsOnlyKeys("2026-07-16");
    }

    @Test
    void merge_완료항목_같은날짜같은내용재등장시완료보존() {
        // given
        Map<String, Object> current = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", true)));
        Map<String, Object> patch = Map.of("2026-07-16", List.of("단어 암기"));

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then — id는 재생성되지만(carryover는 id 무관) completed는 보존
        assertThat(merged.get("2026-07-16")).isEqualTo(List.of(task("t-2026-07-16-0", "단어 암기", true)));
    }

    @Test
    void merge_완료항목_내용바뀌면완료리셋() {
        // given — 같은 날짜라도 content가 바뀌면 다른 할 일이 된 것이므로 미완료로 리셋
        Map<String, Object> current = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", true)));
        Map<String, Object> patch = Map.of("2026-07-16", List.of("문법 정리"));

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then
        assertThat(merged.get("2026-07-16")).isEqualTo(List.of(task("t-2026-07-16-0", "문법 정리", false)));
    }

    @Test
    void merge_날짜키오름차순재정렬() {
        // given — patch가 역순으로 와도 결과는 오름차순
        Map<String, Object> current = Map.of();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("2026-07-18", List.of("셋째 날"));
        patch.put("2026-07-16", List.of("첫째 날"));
        patch.put("2026-07-17", List.of("둘째 날"));

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then
        assertThat(merged.keySet()).containsExactly("2026-07-16", "2026-07-17", "2026-07-18");
    }

    @Test
    void merge_병합후모든날짜삭제되면_null반환() {
        // given — 유일한 날짜를 삭제하는 patch
        Map<String, Object> current = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", false)));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("2026-07-16", null);

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then — 호출부는 "변경 없음"으로 취급
        assertThat(merged).isNull();
    }

    @Test
    void merge_빈문자열만있는날짜_삭제() {
        // given — 필터 후 남는 항목이 없으면 그 날짜는 삭제된다(applyPlanPatch와 동일)
        Map<String, Object> current = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", false)));
        Map<String, Object> patch = Map.of("2026-07-16", List.of("  ", ""));

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then
        assertThat(merged).isNull();
    }

    @Test
    void merge_비List현재값_예외없이빈리스트로방어() {
        // given — 현재 계획에 이상값이 섞여 있어도 예외를 던지지 않는다(프론트 carryOverCompleted와
        // 동일하게, 완료 보존 단계에서 비List 값은 빈 리스트로 취급된다).
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("2026-07-16", "이상한 값");
        Map<String, Object> patch = Map.of("2026-07-17", List.of("새 항목"));

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then
        assertThat(merged.get("2026-07-16")).isEqualTo(List.of());
        assertThat(merged.get("2026-07-17")).isEqualTo(List.of(task("t-2026-07-17-0", "새 항목", false)));
    }

    @Test
    void merge_content객체요소_문자열만추출() {
        // given — {content: "..."} 형태의 patch 요소
        Map<String, Object> current = Map.of();
        Map<String, Object> patch = Map.of("2026-07-16", List.of(Map.of("content", "객체로 온 항목")));

        // when
        Map<String, Object> merged = ChatPatchMerger.merge(current, patch);

        // then
        assertThat(merged.get("2026-07-16")).isEqualTo(List.of(task("t-2026-07-16-0", "객체로 온 항목", false)));
    }

    @Test
    void merge_patch가null이거나빈맵_null반환() {
        Map<String, Object> current = Map.of("2026-07-16", List.of(task("t-1", "단어 암기", false)));

        assertThat(ChatPatchMerger.merge(current, null)).isNull();
        assertThat(ChatPatchMerger.merge(current, Map.of())).isNull();
    }
}
