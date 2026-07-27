package com.delaynomore.backend.domain.ai.usage;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용량 파싱·합산의 계약. 계측 코드는 틀려도 화면이 깨지지 않아 버그가 조용히 살아남으므로,
 * "업스트림이 이렇게 줄 때 우리는 이렇게 읽는다"를 케이스로 못박는다.
 */
class TokenUsageTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private JsonNode node(String json) {
        return jsonMapper.readTree(json);
    }

    @Test
    void 표준_usage_객체를_읽는다() {
        TokenUsage usage = TokenUsage.from(node("""
                {"prompt_tokens": 812, "completion_tokens": 143, "total_tokens": 955}"""));

        assertThat(usage.promptTokens()).isEqualTo(812);
        assertThat(usage.completionTokens()).isEqualTo(143);
        assertThat(usage.totalTokens()).isEqualTo(955);
        assertThat(usage.cost()).isNull();
        assertThat(usage.isEmpty()).isFalse();
    }

    @Test
    void total_tokens가_없으면_prompt와_completion의_합으로_채운다() {
        // 일부 모델은 total_tokens를 생략한다. 0으로 두면 합계 로그가 통째로 0이 되어
        // "토큰을 안 썼다"로 읽히므로 직접 더한다.
        TokenUsage usage = TokenUsage.from(node("""
                {"prompt_tokens": 100, "completion_tokens": 20}"""));

        assertThat(usage.totalTokens()).isEqualTo(120);
    }

    @Test
    void usage가_없거나_객체가_아니면_EMPTY다() {
        // 계측 실패가 본래 기능을 막으면 안 된다 — 예외가 아니라 빈 값으로 떨어져야 한다.
        assertThat(TokenUsage.from(null)).isEqualTo(TokenUsage.EMPTY);
        assertThat(TokenUsage.from(node("{}").path("usage"))).isEqualTo(TokenUsage.EMPTY);
        assertThat(TokenUsage.from(node("{\"usage\": \"nope\"}").path("usage"))).isEqualTo(TokenUsage.EMPTY);
        assertThat(TokenUsage.EMPTY.isEmpty()).isTrue();
    }

    @Test
    void cost는_있을_때만_읽는다() {
        assertThat(TokenUsage.from(node("""
                {"prompt_tokens": 10, "completion_tokens": 5, "cost": 0.00042}""")).cost())
                .isEqualTo(0.00042);
        assertThat(TokenUsage.from(node("""
                {"prompt_tokens": 10, "completion_tokens": 5}""")).cost())
                .isNull();
    }

    @Test
    void 합산은_토큰을_더한다() {
        TokenUsage total = TokenUsage.EMPTY
                .plus(new TokenUsage(100, 20, 120, null))
                .plus(new TokenUsage(300, 40, 340, null));

        assertThat(total.promptTokens()).isEqualTo(400);
        assertThat(total.completionTokens()).isEqualTo(60);
        assertThat(total.totalTokens()).isEqualTo(460);
    }

    @Test
    void 합산에서_cost는_있는_쪽만_있어도_살아남는다() {
        // 한쪽만 cost를 주는 경우 0으로 취급하면 비용이 실제보다 적게 보인다 — 과소 보고보다
        // "적어도 이만큼"이 낫다는 판단.
        TokenUsage total = new TokenUsage(10, 5, 15, 0.001)
                .plus(new TokenUsage(10, 5, 15, null));
        assertThat(total.cost()).isEqualTo(0.001);

        TokenUsage both = new TokenUsage(10, 5, 15, 0.001)
                .plus(new TokenUsage(10, 5, 15, 0.002));
        assertThat(both.cost()).isEqualTo(0.003);

        assertThat(TokenUsage.EMPTY.plus(null)).isEqualTo(TokenUsage.EMPTY);
    }
}
