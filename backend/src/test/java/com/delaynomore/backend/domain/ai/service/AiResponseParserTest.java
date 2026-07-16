package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.dto.AiChatResponse;
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

    @Test
    void toChatResponse_구분자없는산문_계획미변경응답() {
        // given
        String raw = "지금 계획대로 진행하시면 충분합니다.";

        // when
        AiChatResponse response = parser.toChatResponse(raw);

        // then
        assertThat(response.reply()).isEqualTo("지금 계획대로 진행하시면 충분합니다.");
        assertThat(response.planUpdated()).isFalse();
        assertThat(response.patch()).isNull();
    }

    @Test
    void toChatResponse_구분자와patch포함_계획변경응답() {
        // given
        String raw = "3일차를 더 쉽게 바꿨어요.\n===PLAN===\n{\"2026-07-18\": [\"기초 문제 3개 풀기\"]}";

        // when
        AiChatResponse response = parser.toChatResponse(raw);

        // then
        assertThat(response.reply()).isEqualTo("3일차를 더 쉽게 바꿨어요.");
        assertThat(response.planUpdated()).isTrue();
        assertThat(response.patch()).containsEntry("2026-07-18", List.of("기초 문제 3개 풀기"));
    }

    @Test
    void toChatResponse_구분자뒤patch가깨진경우_계획미변경응답() {
        // given
        String raw = "계획을 수정했어요.\n===PLAN===\n이건 JSON이 아님";

        // when
        AiChatResponse response = parser.toChatResponse(raw);

        // then
        assertThat(response.planUpdated()).isFalse();
        assertThat(response.patch()).isNull();
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
