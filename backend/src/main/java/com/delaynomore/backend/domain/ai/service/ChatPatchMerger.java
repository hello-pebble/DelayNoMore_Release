package com.delaynomore.backend.domain.ai.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 자유 대화(chat) LLM patch 병합의 단일 소유처. 예전엔 LLM이 변경된 날짜만 담은 sparse patch를
 * 내면 프론트(ai_engine.js의 applyPlanPatch+carryOverCompleted)가 현재 계획에 병합했지만, 이제
 * 서버가 병합해 정규화된 전체 tasks({id, content, completed} 객체)를 돌려준다 — 프론트는 채택만
 * 한다. LLM은 여전히 compact patch를 낸다(출력 토큰 절약은 그대로 유지, 커지는 건 서버→클라
 * 응답뿐). Spring bean이 아닌 static 유틸인 이유는 PlanDates와 같다 — 현재 계획(tasks)과 patch
 * 둘 다 순수 값이라 상태가 필요 없다.
 */
public final class ChatPatchMerger {

    private ChatPatchMerger() {
    }

    // patch(변경된 날짜만: 값이 List면 그 날의 새 할 일 문자열/객체, null이면 삭제)를 현재 계획에
    // 병합해 정규화된 전체 tasks를 돌려준다. 병합 후 남는 날짜가 없으면 null(변경 없음 취급).
    public static Map<String, Object> merge(Map<String, Object> currentTasks, Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            return null;
        }
        Map<String, Object> next = new LinkedHashMap<>(currentTasks == null ? Map.of() : currentTasks);

        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String date = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                next.remove(date); // 기간 단축 등 — 해당 날짜 삭제
                continue;
            }
            if (!(value instanceof List<?> list)) {
                continue; // 배열이 아닌 값은 무시(계약 위반 방어)
            }
            List<Map<String, Object>> items = liftItems(date, list);
            if (items.isEmpty()) {
                next.remove(date);
            } else {
                next.put(date, items);
            }
        }

        // 날짜 키를 오름차순으로 재정렬해 Day 순서를 맞춘다(YYYY-MM-DD는 사전식=시간순).
        Map<String, Object> ordered = new LinkedHashMap<>(new TreeMap<>(next));
        if (ordered.isEmpty()) {
            return null;
        }
        return carryOverCompleted(currentTasks, ordered);
    }

    // patch의 할 일 하나를 {id, content, completed:false} 객체로 복원한다. 문자열은 그대로,
    // {content:"..."} 객체는 content만 취한다(AiResponseParser.taskStrings와 같은 흡수 규칙).
    private static List<Map<String, Object>> liftItems(String date, List<?> list) {
        List<Map<String, Object>> items = new ArrayList<>();
        int idx = 0;
        for (Object task : list) {
            String content = null;
            if (task instanceof String s) {
                content = s;
            } else if (task instanceof Map<?, ?> m && m.get("content") instanceof String s) {
                content = s;
            }
            if (content != null && !content.isBlank()) {
                items.add(Map.of("id", "t-" + date + "-" + idx, "content", content.trim(), "completed", false));
                idx++;
            }
        }
        return items;
    }

    // 대화 수정으로 계획이 다시 만들어질 때, 이전 계획에서 완료한 항목의 체크를 보존한다.
    // 같은 날짜에 같은 내용의 할 일이 다시 등장하면 완료로 간주한다 — 내용이 바뀐 항목은 다른
    // 할 일이 된 것이므로 미완료로 리셋되는 게 맞다(프론트 carryOverCompleted와 같은 semantics).
    private static Map<String, Object> carryOverCompleted(Map<String, Object> prev, Map<String, Object> next) {
        if (prev == null || prev.isEmpty()) {
            return next;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : next.entrySet()) {
            String date = entry.getKey();
            List<?> list = entry.getValue() instanceof List<?> l ? l : List.of();
            Object prevValue = prev.get(date);
            List<?> prevList = prevValue instanceof List<?> l ? l : List.of();

            Set<Object> completedContents = new HashSet<>();
            for (Object item : prevList) {
                if (item instanceof Map<?, ?> task && Boolean.TRUE.equals(task.get("completed"))) {
                    completedContents.add(task.get("content"));
                }
            }

            List<Object> mapped = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> task
                        && !Boolean.TRUE.equals(task.get("completed"))
                        && completedContents.contains(task.get("content"))) {
                    Map<String, Object> withCompleted = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> field : task.entrySet()) {
                        withCompleted.put(String.valueOf(field.getKey()), field.getValue());
                    }
                    withCompleted.put("completed", true);
                    mapped.add(withCompleted);
                } else {
                    mapped.add(item);
                }
            }
            result.put(date, mapped);
        }
        return result;
    }
}
