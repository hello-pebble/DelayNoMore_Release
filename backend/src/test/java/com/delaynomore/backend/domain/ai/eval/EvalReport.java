package com.delaynomore.backend.domain.ai.eval;

import com.delaynomore.backend.domain.ai.usage.TokenUsage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실행 기록을 사람이 읽는 표로 만든다. 콘솔에 찍고 파일로도 남기는데, 파일이 있어야 릴리스
 * 사이의 결과를 <b>diff</b>할 수 있다 — 평가의 가치는 한 번의 점수가 아니라 변화의 방향에 있다.
 *
 * <p>반복(repeat)을 지원하는 이유: 모델은 결정적이지 않아 1회 실행의 통과/실패는 잡음을 포함한다.
 * 케이스별 통과 <b>비율</b>로 보면 "가끔 틀리는 케이스"와 "항상 틀리는 케이스"가 갈린다.
 */
public record EvalReport(String datasetName, String model, int repeats, List<EvalRunResult> results) {

    public String render() {
        Map<String, List<EvalRunResult>> byCase = new LinkedHashMap<>();
        for (EvalRunResult result : results) {
            byCase.computeIfAbsent(result.testCase().id(), key -> new java.util.ArrayList<>()).add(result);
        }

        StringBuilder out = new StringBuilder();
        out.append("# 에이전트 도구 선택 평가 — ").append(datasetName).append("\n\n");
        out.append("- 모델: `").append(model).append("`\n");
        out.append("- 케이스 ").append(byCase.size()).append("개 × ").append(repeats).append("회 = ")
                .append(results.size()).append("회 실행\n\n");

        out.append("| 케이스 | 통과 | 호출한 도구 | 왕복 | 토큰 |\n");
        out.append("| :--- | :---: | :--- | ---: | ---: |\n");
        for (Map.Entry<String, List<EvalRunResult>> entry : byCase.entrySet()) {
            List<EvalRunResult> runs = entry.getValue();
            long passed = runs.stream().filter(EvalRunResult::passed).count();
            out.append("| `").append(entry.getKey()).append("` | ")
                    .append(passed == runs.size() ? "✅ " : "❌ ").append(passed).append("/").append(runs.size())
                    .append(" | ").append(toolsColumn(runs))
                    .append(" | ").append(sumCalls(runs))
                    .append(" | ").append(sumUsage(runs).totalTokens())
                    .append(" |\n");
        }

        long passedRuns = results.stream().filter(EvalRunResult::passed).count();
        TokenUsage total = sumUsage(results);
        out.append("\n## 요약\n\n");
        out.append("- **통과율 ").append(percent(passedRuns, results.size())).append("%** (")
                .append(passedRuns).append("/").append(results.size()).append(" 실행)\n");
        out.append("- 업스트림 왕복 ").append(sumCalls(results)).append("회 · 토큰 ")
                .append(total.totalTokens()).append("(입력 ").append(total.promptTokens())
                .append(" / 출력 ").append(total.completionTokens()).append(")");
        if (total.cost() != null) {
            out.append(" · 비용 $").append(formatCost(total.cost()));
        }
        out.append("\n");

        appendDetails(out, "실패", results.stream()
                .filter(r -> !r.passed())
                .map(r -> "`" + r.testCase().id() + "` (#" + r.repeat() + ") — "
                        + (r.error() != null ? "실행 오류: " + r.error() : String.join("; ", r.verdict().failures())))
                .toList());
        appendDetails(out, "경고", results.stream()
                .filter(r -> r.verdict() != null && r.verdict().hasWarnings())
                .map(r -> "`" + r.testCase().id() + "` (#" + r.repeat() + ") — "
                        + String.join("; ", r.verdict().warnings()))
                .toList());
        return out.toString();
    }

    private static void appendDetails(StringBuilder out, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        out.append("\n## ").append(title).append("\n\n");
        lines.forEach(line -> out.append("- ").append(line).append("\n"));
    }

    // 반복 실행마다 도구 선택이 달라질 수 있으므로 관측된 조합을 모두 보여준다 — 흔들림 자체가 신호다.
    private static String toolsColumn(List<EvalRunResult> runs) {
        return runs.stream()
                .map(run -> run.attemptedTools().isEmpty() ? "(없음)" : String.join(", ", run.attemptedTools()))
                .distinct()
                .reduce((a, b) -> a + " / " + b)
                .orElse("(없음)");
    }

    private static int sumCalls(List<EvalRunResult> runs) {
        return runs.stream().mapToInt(EvalRunResult::upstreamCalls).sum();
    }

    private static TokenUsage sumUsage(List<EvalRunResult> runs) {
        TokenUsage total = TokenUsage.EMPTY;
        for (EvalRunResult run : runs) {
            total = total.plus(run.usage());
        }
        return total;
    }

    private static long percent(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 100.0 / denominator);
    }

    /**
     * 비용을 유효숫자 4자리로 줄여 찍는다. {@code double}을 그대로 문자열화하면 부동소수점 원값이
     * 새어 나온다($0.04795999999999999) — 여러 턴의 cost를 더한 값이라 오차가 누적되기 때문이다.
     *
     * <p>자릿수를 고정하지 않은 이유({@code %.5f}가 아닌 이유): 총액은 케이스 수와 반복 횟수에 따라
     * 자릿수가 크게 달라진다. 1회 실행의 $0.0008을 소수 5자리로 찍으면 유효숫자가 한 자리만 남고,
     * 48회 실행의 $0.048에 5자리는 과하다. 유효숫자 기준이면 규모와 무관하게 읽을 만한 값이 된다.
     */
    private static String formatCost(double cost) {
        return java.math.BigDecimal.valueOf(cost)
                .round(new java.math.MathContext(4))
                .stripTrailingZeros()
                .toPlainString();
    }
}
