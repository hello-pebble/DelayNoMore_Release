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

    public EvalDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
