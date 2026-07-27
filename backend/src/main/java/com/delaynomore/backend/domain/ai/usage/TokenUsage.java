package com.delaynomore.backend.domain.ai.usage;

import tools.jackson.databind.JsonNode;

/**
 * LLM 호출 한 번(또는 여러 번의 합)의 토큰 사용량. 업스트림 응답의 {@code usage} 객체를 그대로
 * 옮긴 값 객체다.
 *
 * <p>에이전트 루프는 한 번의 사용자 요청에 최대 5번(도구 턴 4 + 강제 마무리 1) 업스트림을
 * 호출한다. 호출당 로그만 남기면 "요청 하나에 얼마나 썼는가"를 사람이 눈으로 더해야 하므로,
 * 합산할 수 있는 값으로 두고 {@link #plus}로 누적한다.
 *
 * <p>{@code cost}는 OpenRouter가 usage accounting을 켠 응답에서만 내려오는 선택 필드라 null일 수
 * 있다. 없다고 해서 토큰 수까지 못 믿는 것은 아니므로 별도로 다룬다.
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens, Double cost) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, null);

    /**
     * 응답의 {@code usage} 노드를 읽는다. 필드가 없거나 형식이 어긋나면 {@link #EMPTY} —
     * 계측이 실패해도 본래 기능(대화·계획 생성)은 그대로 굴러가야 하므로 예외를 던지지 않는다.
     *
     * <p>{@code total_tokens}를 안 주는 모델이 있어 없으면 prompt+completion으로 채운다.
     */
    public static TokenUsage from(JsonNode usageNode) {
        if (usageNode == null || !usageNode.isObject()) {
            return EMPTY;
        }
        int prompt = usageNode.path("prompt_tokens").asInt(0);
        int completion = usageNode.path("completion_tokens").asInt(0);
        int total = usageNode.path("total_tokens").asInt(prompt + completion);
        JsonNode costNode = usageNode.path("cost");
        Double cost = costNode.isNumber() ? costNode.asDouble() : null;
        return new TokenUsage(prompt, completion, total, cost);
    }

    /**
     * 두 사용량을 더한다. cost는 한쪽만 있으면 있는 쪽을 취한다 — 여러 턴 중 일부만 cost를
     * 내려주는 경우 "합계"라고 부르기 어렵지만, 0으로 취급해 과소 보고하는 것보다는 낫다.
     */
    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        Double summedCost = (cost == null && other.cost == null) ? null
                : (cost == null ? other.cost : (other.cost == null ? cost : cost + other.cost));
        return new TokenUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens,
                summedCost);
    }

    // 업스트림이 usage를 아예 안 내려준 경우(구형 모델·스트리밍 미지원 등). 로그를 남길 가치가 없다.
    public boolean isEmpty() {
        return totalTokens == 0 && promptTokens == 0 && completionTokens == 0;
    }
}
