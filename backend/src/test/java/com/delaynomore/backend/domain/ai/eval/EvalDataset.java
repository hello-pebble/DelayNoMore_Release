package com.delaynomore.backend.domain.ai.eval;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 케이스 묶음. 데이터를 코드에서 떼어 JSON에 둔 이유는, 케이스를 늘리는 일이 <b>컴파일 없이
 * diff로 보이는 변경</b>이어야 하기 때문이다 — 평가 데이터셋은 늘어나는 게 정상이고, 늘어난
 * 만큼이 리뷰에서 보여야 한다.
 */
public record EvalDataset(String name, String description, List<EvalCase> cases) {

    public static final String DEFAULT_RESOURCE = "/eval/agent-tool-selection.json";

    public static EvalDataset load(String resourcePath) {
        try (InputStream in = EvalDataset.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("평가 데이터셋을 찾을 수 없습니다: " + resourcePath);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // 케이스 대부분은 expectNoTools를 쓰지 않는다. 안 쓰는 필드를 매번 false로 적게 하면
            // 데이터셋에 잡음이 늘고 실수도 늘어나므로, 생략을 정상으로 받는다.
            return JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .build()
                    .readValue(json, EvalDataset.class);
        } catch (Exception e) {
            throw new IllegalStateException("평가 데이터셋을 읽지 못했습니다: " + resourcePath, e);
        }
    }

    public static EvalDataset loadDefault() {
        return load(DEFAULT_RESOURCE);
    }

    /**
     * 케이스 id 접두사(쉼표 구분)로 부분집합을 고른다. <b>한 축을 깊게 재기 위한</b> 기능이다 —
     * 흔들리는 축의 기저 실패율이 낮으면(실측: {@code notool.*}가 약 9%) 개선을 확인하려면 반복을
     * 크게 올려야 하는데, 이미 안정적인 케이스까지 그 횟수만큼 돌리는 것은 토큰 낭비다.
     *
     * <p>접두사로 고르는 이유: id가 {@code notool.greeting}처럼 <b>축.케이스</b> 구조라 접두사 하나가
     * 곧 하나의 축이다. 정규식을 받으면 표현력은 늘지만 명령줄에서 쓰기 나빠진다.
     *
     * <p>고른 결과를 이름에 남기는 것이 중요하다 — 부분집합 리포트가 전체 실행 리포트처럼 보이면
     * 통과율 100%가 "전부 통과"로 오독된다.
     */
    public EvalDataset filter(String prefixesCsv) {
        if (prefixesCsv == null || prefixesCsv.isBlank()) {
            return this;
        }
        List<String> prefixes = java.util.Arrays.stream(prefixesCsv.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .toList();
        List<EvalCase> selected = cases.stream()
                .filter(testCase -> prefixes.stream().anyMatch(prefix -> testCase.id().startsWith(prefix)))
                .toList();
        if (selected.isEmpty()) {
            // 조용히 0케이스를 돌리면 빌드가 성공해, 아무것도 재지 않은 실행이 통과로 읽힌다.
            throw new IllegalArgumentException("-Deval.only=" + prefixesCsv + " 에 맞는 케이스가 없습니다. "
                    + "사용 가능한 id: " + cases.stream().map(EvalCase::id).toList());
        }
        return new EvalDataset(name + " (only=" + String.join(",", prefixes) + ")", description, selected);
    }

    public EvalDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
