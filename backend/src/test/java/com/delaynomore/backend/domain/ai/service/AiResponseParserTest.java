package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class AiResponseParserTest {

    private final AiResponseParser parser = new AiResponseParser(JsonMapper.builder().build());

    @Test
    void parsePlan_코드펜스로감싼응답_계획맵반환() {
        // given
        String raw = "```json\n{\"2026-07-16\": [\"핵심 개념 정리하기\"]}\n```";

        // when
        Object plan = parser.parsePlan(raw);

        // then
        assertThat(plan).isInstanceOf(Map.class);
        assertThat(plan).isEqualTo(Map.of("2026-07-16", List.of("핵심 개념 정리하기")));
    }

    @Test
    void parsePlan_설명이섞인응답_JSON객체만추출() {
        // given
        String raw = "다음은 계획입니다. {\"2026-07-16\": [\"예제 1개 풀이\"]} 참고하세요.";

        // when
        Object plan = parser.parsePlan(raw);

        // then
        assertThat(plan).isEqualTo(Map.of("2026-07-16", List.of("예제 1개 풀이")));
    }

    @Test
    void parsePlan_JSON이아닌응답_예외발생() {
        // given
        String raw = "죄송하지만 계획을 만들 수 없습니다.";

        // when
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> parser.parsePlan(raw));

        // then
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
    }

    // === 초안 날짜 키 정규화(normalizeDraftPlan) — LLM이 계약을 어긴 출력도 날짜 맵으로 강제 ===

    private static final java.time.LocalDate START = java.time.LocalDate.of(2026, 7, 19);

    @Test
    void normalizeDraftPlan_정상날짜맵_그대로유지() {
        // given
        Map<String, Object> parsed = Map.of("2026-07-20", List.of("핵심 개념 정리하기"));

        // when
        Map<String, Object> plan = parser.normalizeDraftPlan(parsed, START);

        // then — 이미 계약을 지킨 응답은 변형하지 않는다(키 재배열 없음)
        assertThat(plan).isEqualTo(Map.of("2026-07-20", List.of("핵심 개념 정리하기")));
    }

    @Test
    void normalizeDraftPlan_plan래퍼와date필드배열_날짜맵으로변환() {
        // given — {plan: [{date, tasks}]} 변형 스키마
        Object parsed = Map.of("plan", List.of(
                Map.of("date", "2026-07-19", "tasks", List.of("단어 암기")),
                Map.of("date", "2026-07-20", "tasks", List.of("문법 정리"))));

        // when
        Map<String, Object> plan = parser.normalizeDraftPlan(parsed, START);

        // then
        assertThat(plan).isEqualTo(Map.of(
                "2026-07-19", List.of("단어 암기"),
                "2026-07-20", List.of("문법 정리")));
    }

    @Test
    void normalizeDraftPlan_날짜없는최상위배열_시작일부터위치기반합성() {
        // given — 날짜 필드가 아예 없는 배열(예전엔 프론트가 "Day 1" 키를 만들던 저하 경로)
        Object parsed = List.of(
                Map.of("tasks", List.of("단어 암기")),
                Map.of("tasks", List.of("문법 정리")));

        // when
        Map<String, Object> plan = parser.normalizeDraftPlan(parsed, START);

        // then
        assertThat(plan).isEqualTo(Map.of(
                "2026-07-19", List.of("단어 암기"),
                "2026-07-20", List.of("문법 정리")));
    }

    @Test
    void normalizeDraftPlan_Day키맵_전체를위치기반재키잉() {
        // given — 비날짜 키가 하나라도 섞이면 결정적으로 전체 재배열
        Map<String, Object> parsed = new java.util.LinkedHashMap<>();
        parsed.put("Day 1", List.of("단어 암기"));
        parsed.put("Day 2", List.of("문법 정리"));

        // when
        Map<String, Object> plan = parser.normalizeDraftPlan(parsed, START);

        // then
        assertThat(plan).isEqualTo(Map.of(
                "2026-07-19", List.of("단어 암기"),
                "2026-07-20", List.of("문법 정리")));
    }

    @Test
    void normalizeDraftPlan_content객체배열_문자열만추출() {
        // given — 할 일이 {content: "..."} 객체로 온 변형
        Map<String, Object> parsed = Map.of(
                "2026-07-19", List.of(Map.of("content", "단어 암기"), Map.of("no-content", 1)));

        // when
        Map<String, Object> plan = parser.normalizeDraftPlan(parsed, START);

        // then
        assertThat(plan).isEqualTo(Map.of("2026-07-19", List.of("단어 암기")));
    }

    @Test
    void normalizeDraftPlan_유효한할일없음_AI_RESPONSE_INVALID예외() {
        // given
        Map<String, Object> parsed = Map.of("2026-07-19", "배열이 아님");

        // when
        BusinessException e = catchThrowableOfType(
                BusinessException.class, () -> parser.normalizeDraftPlan(parsed, START));

        // then — 프론트는 이 오류에서 mock 폴백으로 넘어간다(기존 경로)
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
    }

    @Test
    void parseChat_구분자없는산문_patch없음() {
        // given
        String raw = "지금 계획대로 진행하시면 충분합니다.";

        // when
        AiResponseParser.ChatParse parsed = parser.parseChat(raw);

        // then
        assertThat(parsed.reply()).isEqualTo("지금 계획대로 진행하시면 충분합니다.");
        assertThat(parsed.patch()).isNull();
    }

    @Test
    void parseChat_구분자와patch포함_patch반환() {
        // given
        String raw = "3일차를 더 쉽게 바꿨어요.\n===PLAN===\n{\"2026-07-18\": [\"기초 문제 3개 풀기\"]}";

        // when
        AiResponseParser.ChatParse parsed = parser.parseChat(raw);

        // then — 병합(현재 계획 + patch → 전체 tasks)은 AiService/ChatPatchMerger가 담당(별도 테스트)
        assertThat(parsed.reply()).isEqualTo("3일차를 더 쉽게 바꿨어요.");
        assertThat(parsed.patch()).containsEntry("2026-07-18", List.of("기초 문제 3개 풀기"));
    }

    @Test
    void parseChat_구분자뒤patch가깨진경우_patch없음() {
        // given
        String raw = "계획을 수정했어요.\n===PLAN===\n이건 JSON이 아님";

        // when
        AiResponseParser.ChatParse parsed = parser.parseChat(raw);

        // then
        assertThat(parsed.patch()).isNull();
    }

    @Test
    void parseDayLine_완성된NDJSON한줄_DayPlan반환() {
        // given
        String line = "{\"date\":\"2026-07-16\",\"tasks\":[\"단어 30개 암기\",\"듣기 1회 연습\"]}";

        // when
        AiResponseParser.DayPlan day = parser.parseDayLine(line);

        // then
        assertThat(day.date()).isEqualTo("2026-07-16");
        assertThat(day.tasks()).containsExactly("단어 30개 암기", "듣기 1회 연습");
    }

    @Test
    void parseDayLine_아직완성되지않은부분줄_null반환() {
        // given
        String line = "{\"date\":\"2026-07-16\",\"tasks\":[\"단어 30개";

        // when
        AiResponseParser.DayPlan day = parser.parseDayLine(line);

        // then
        assertThat(day).isNull();
    }

    @Test
    void cleanKoreanText_한자혼입텍스트_한자제거및공백정리() {
        // given
        String raw = "重點 핵심 개념을  정리하세요";

        // when
        String cleaned = parser.cleanKoreanText(raw);

        // then
        assertThat(cleaned).isEqualTo("핵심 개념을 정리하세요");
    }
}
