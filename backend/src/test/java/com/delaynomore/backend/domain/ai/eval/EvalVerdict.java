package com.delaynomore.backend.domain.ai.eval;

import java.util.List;

/**
 * 케이스 한 번 실행의 판정.
 *
 * <p>실패와 경고를 나눈 이유가 이 하네스의 핵심 판단이다. 노출되지 않은 도구를 모델이 부르려
 * <b>시도</b>하는 것과 그 도구가 실제로 <b>실행</b>되는 것은 무게가 다르다 — 전자는 모델의 버릇이고
 * 후자는 권한 모델이 뚫린 것이다. 둘을 같은 칸에 넣으면 진짜 사고가 모델 잡음에 묻힌다.
 *
 * @param failures           설계가 깨진 것 — 이게 있으면 실패
 * @param warnings           설계대로 막혔지만 기록해 둘 것(모델이 시도했다는 사실 자체가 프롬프트 개선의 단서)
 * @param permissionBreached 금지된 도구가 <b>실행</b>됐는가. 다른 실패는 모델 품질 문제라 통과율로
 *                           다루지만 이것 하나는 설계가 뚫린 것이라, 실행기가 무조건 빌드를 깨뜨린다.
 *                           문자열 매칭으로 판별하면 메시지를 손볼 때 조용히 무력화되므로 값으로 둔다.
 */
public record EvalVerdict(String caseId, boolean passed, List<String> failures, List<String> warnings,
                          boolean permissionBreached) {

    public EvalVerdict {
        failures = failures == null ? List.of() : List.copyOf(failures);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
