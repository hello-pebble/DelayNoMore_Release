package com.delaynomore.backend.domain.ai.service;

import com.delaynomore.backend.domain.ai.dto.AiChatResponse;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LLM 응답 해석 전담: 코드펜스 제거·JSON 추출, ===PLAN=== 구분자 분리,
 * 비한국어 CJK(한자/가나) 제거 등 "모델이 어긴 출력 계약"을 결정적으로 보정한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiResponseParser {

    private final JsonMapper jsonMapper;

    // 자유 대화 응답에서 "산문 reply"와 "계획 patch JSON"을 가르는 구분자.
    public static final String PLAN_SENTINEL = "===PLAN===";

    // qwen 등 중국어권 모델이 한국어에 섞어 내는 한자(漢字)·가나 등 비한국어 CJK 문자를 제거한다.
    // 프롬프트 규칙만으로는 가끔 새기 때문에, 화면에 나가기 전 결정적으로 걸러 순수 한국어를 보장한다.
    // 히라가나/가타카나(3040-30FF), CJK 확장 A(3400-4DBF), 통합 한자(4E00-9FFF), 호환 한자(F900-FAFF).
    // 한글·라틴(RSA·TCP/IP 등)·숫자·기호는 보존한다.
    private static final Pattern CJK_NOISE =
            Pattern.compile("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]");

    // 초안 스트리밍의 "하루 = 한 줄" 파싱 결과.
    public record DayPlan(String date, List<String> tasks) {
    }

    // 초안 계획 원문(JSON)을 파싱해 모든 태스크 문자열을 정제한 값(Map/List)으로 돌려준다.
    // 코드펜스·설명이 섞여도 JSON 객체만 뽑아 시도하고, 그래도 해석 불가면 예외로 알린다(프론트는 mock 폴백).
    public Object parsePlan(String rawContent) {
        String sanitized = sanitizeJson(rawContent);
        try {
            Object parsed = jsonMapper.readValue(sanitized, Object.class);
            return cleanValue(parsed);
        } catch (Exception e) {
            log.warn("Failed to parse draft plan JSON from AI response");
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
        }
    }

    // 자유 대화 응답 원문(산문 + 선택적 ===PLAN=== + patch)을 {reply, planUpdated, patch}로 가른다.
    public AiChatResponse toChatResponse(String raw) {
        if (raw == null) raw = "";
        int idx = raw.indexOf(PLAN_SENTINEL);
        if (idx < 0) {
            return AiChatResponse.replyOnly(cleanKoreanText(raw.trim()));
        }
        String reply = cleanKoreanText(raw.substring(0, idx).trim());
        Map<String, Object> patch = parsePatch(raw.substring(idx + PLAN_SENTINEL.length()));
        if (patch == null || patch.isEmpty()) {
            return AiChatResponse.replyOnly(reply);
        }
        return AiChatResponse.withPatch(reply, patch);
    }

    // patch JSON(문자열)을 Map으로 파싱한다. 앞뒤 설명이 섞여도 중괄호 균형으로 객체만 뽑는다.
    @SuppressWarnings("unchecked")
    public Map<String, Object> parsePatch(String jsonPart) {
        if (jsonPart == null) return null;
        String s = jsonPart.trim();
        String extracted = s.startsWith("{") ? s : extractJsonObject(s);
        if (extracted == null) return null;
        try {
            JsonNode node = jsonMapper.readTree(extracted);
            if (!node.isObject() || node.isEmpty()) return null;
            Map<String, Object> map = jsonMapper.convertValue(node, Map.class);
            // patch의 태스크 문자열에서도 비한국어 CJK 문자를 제거한다(체크리스트 표시용).
            return (Map<String, Object>) cleanValue(map);
        } catch (Exception e) {
            return null;
        }
    }

    // NDJSON 한 줄을 {"date","tasks":[...]}로 파싱한다. 파싱 불가/부분 줄/코드펜스는 null(무시).
    public DayPlan parseDayLine(String line) {
        String s = line == null ? "" : line.trim();
        if (s.isEmpty() || s.startsWith("```")) return null;
        if (!s.startsWith("{")) {
            s = extractJsonObject(s);
            if (s == null) return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(s);
            if (!node.isObject()) return null;
            String date = node.path("date").asString("");
            JsonNode tasksNode = node.path("tasks");
            if (date.isBlank() || !tasksNode.isArray()) return null;
            List<String> tasks = new ArrayList<>();
            for (JsonNode t : tasksNode) {
                String content = cleanKoreanText(t.asString(""));
                if (content != null && !content.isBlank()) tasks.add(content);
            }
            return tasks.isEmpty() ? null : new DayPlan(date, tasks);
        } catch (Exception e) {
            return null; // 아직 완성되지 않은 줄 등 — 다음 델타에서 완성되면 그때 방출
        }
    }

    public String stripCjk(String s) {
        if (s == null || s.isEmpty()) return s;
        return CJK_NOISE.matcher(s).replaceAll("");
    }

    // stripCjk 후, 문자를 지우며 생긴 중복 공백을 정리하고 앞뒤 공백을 제거한다(문자열 전체 정제용).
    // 공백 정리는 같은 줄(스페이스/탭)에만 적용하고 줄바꿈 구조는 보존한다. 정규화는 멱등이라
    // 스트림 단계에서 이미 한자를 지운 문자열도 안전하게 다시 통과시킬 수 있다.
    public String cleanKoreanText(String s) {
        if (s == null || s.isEmpty()) return s;
        return stripCjk(s)
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]+(\\n)", "$1")
                .trim();
    }

    // 파싱된 값(String/List/Map)을 재귀적으로 순회하며 모든 문자열에 cleanKoreanText를 적용한다.
    private Object cleanValue(Object v) {
        if (v instanceof String s) return cleanKoreanText(s);
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) out.add(cleanValue(o));
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), cleanValue(e.getValue()));
            }
            return out;
        }
        return v;
    }

    // 코드펜스를 벗기고, 그래도 순수 JSON이 아니면(추론 텍스트/설명이 섞이면) 중괄호 균형으로 객체만 뽑는다.
    private String sanitizeJson(String rawContent) {
        if (rawContent == null) {
            return "{}";
        }
        String trimmed = rawContent.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        if (!trimmed.startsWith("{")) {
            String extracted = extractJsonObject(trimmed);
            if (extracted != null) {
                return extracted;
            }
        }
        return trimmed;
    }

    // 임의의 텍스트에서 최상위 JSON 객체({ ... })를 추출한다. 중첩 중괄호와 문자열 리터럴을 고려한다.
    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
