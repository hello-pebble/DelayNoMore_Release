package com.delaynomore.backend.domain.ai.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 도구 실행 한 건의 결과. 실패도 예외가 아니라 값으로 돌려주는 이유는 에이전트 루프의 회복력
 * 때문이다 — 인자 형식이 틀렸거나 대상이 없으면 루프를 중단하는 대신 실패 사유를 모델에게
 * 되돌려주고 다시 시도하게 한다(모델이 스스로 고칠 수 있는 오류가 대부분이다).
 * 진짜 예외(업스트림 장애 등)만 BusinessException으로 올라가 루프를 끝낸다.
 */
public record ToolResult(boolean ok, Object payload, String message) {

    public static ToolResult ok(Object payload) {
        return new ToolResult(true, payload, null);
    }

    public static ToolResult fail(String message) {
        return new ToolResult(false, null, message);
    }

    // 모델에게 되돌려줄 tool 메시지 본문. 성공/실패를 스키마로 구분해 모델이 분기할 수 있게 한다.
    public Map<String, Object> toModelPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        if (ok) {
            out.put("result", payload);
        } else {
            out.put("error", message);
        }
        return out;
    }
}
