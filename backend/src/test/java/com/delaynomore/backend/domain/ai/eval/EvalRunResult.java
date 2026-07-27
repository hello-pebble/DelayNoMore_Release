package com.delaynomore.backend.domain.ai.eval;

import com.delaynomore.backend.domain.ai.usage.TokenUsage;

import java.util.List;

/**
 * 케이스 한 번 실행의 전체 기록. 판정만 남기지 않고 사용량·왕복 수까지 함께 담는 이유는,
 * 평가의 쓸모가 <b>정확도와 비용을 같은 표에서 보는 것</b>에 있기 때문이다 — 정확도만 보면
 * "도구를 더 많이 부를수록 좋다"는 잘못된 방향으로 최적화된다.
 *
 * @param upstreamCalls 이 케이스가 업스트림을 몇 번 때렸는가(에이전트 루프의 턴 수 + 강제 마무리)
 * @param error         실행 자체가 실패한 경우의 사유(업스트림 오류 등). 정상이면 null.
 */
public record EvalRunResult(
        EvalCase testCase,
        int repeat,
        EvalVerdict verdict,
        List<String> attemptedTools,
        TokenUsage usage,
        int upstreamCalls,
        String error) {

    public EvalRunResult {
        attemptedTools = attemptedTools == null ? List.of() : List.copyOf(attemptedTools);
        usage = usage == null ? TokenUsage.EMPTY : usage;
    }

    public boolean passed() {
        return error == null && verdict != null && verdict.passed();
    }
}
