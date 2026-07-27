package com.delaynomore.backend.domain.ai.agent.tools;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 모델이 만든 도구 인자에서 값을 꺼내는 공용 헬퍼. LLM 인자는 신뢰할 수 없는 입력이므로
 * (필드 누락·null·타입 불일치가 흔하다) 모든 접근을 방어적으로 통일한다 —
 * AiResponseParser가 응답 본문에 하는 일을 도구 인자에 하는 셈이다.
 */
final class ToolArgs {

    private ToolArgs() {
    }

    // 문자열 필드. 없거나 빈 값이면 null(호출부가 기본값을 정하도록).
    static String text(JsonNode args, String field) {
        if (args == null) return null;
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) return null;
        String value = node.isString() ? node.asString() : node.toString();
        return value.isBlank() ? null : value.trim();
    }

    // 객체 필드를 Map으로. 객체가 아니면 null — patch처럼 구조가 중요한 인자에 쓴다.
    static Map<String, Object> object(JsonNode args, String field) {
        if (args == null) return null;
        JsonNode node = args.get(field);
        if (node == null || !node.isObject()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        node.propertyStream().forEach(entry -> out.put(entry.getKey(), unwrap(entry.getValue())));
        return out;
    }

    // JsonNode → 순수 Java 값. patch 병합기(ChatPatchMerger)가 Map/List/String만 다루므로
    // 그 계약에 맞춰 되돌린다. null 노드는 null로 남긴다 — patch에서 "그 날짜 삭제"를 뜻한다.
    private static Object unwrap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isString()) return node.asString();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isNumber()) return node.asDouble();
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            node.forEach(child -> list.add(unwrap(child)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.propertyStream().forEach(entry -> map.put(entry.getKey(), unwrap(entry.getValue())));
            return map;
        }
        return node.asString();
    }
}
