package com.delaynomore.backend.domain.ai.usage;

import com.delaynomore.backend.global.config.OpenRouterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 토큰 사용량을 한 가지 형식으로만 남기는 곳. 호출부가 각자 log.info를 쓰면 라벨과 필드 이름이
 * 금방 갈라져 나중에 집계할 수 없게 되므로, 형식의 소유권을 여기 하나로 모은다.
 *
 * <p>형식은 {@code key=value} 나열이다 — 사람이 읽을 수 있으면서 {@code grep 'ai.usage'} 후
 * awk/스크립트로 바로 합산할 수 있다. 로그 수집기를 붙이기 전까지의 최소 관측 수단이고,
 * 나중에 지표 백엔드로 옮기더라도 호출부는 그대로 두고 이 클래스만 바꾸면 된다.
 *
 * <pre>
 *   ai.usage site=chat.stream model=qwen/qwen3.7-plus prompt=812 completion=143 total=955
 *   ai.usage site=agent.turn  model=qwen/qwen3.7-plus prompt=1904 completion=88 total=1992
 *   ai.usage site=agent.total model=qwen/qwen3.7-plus calls=3 prompt=6120 completion=402 total=6522
 * </pre>
 *
 * <p>계측은 본래 기능보다 항상 후순위다 — 이 클래스의 어떤 경로도 예외를 던지지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiUsageLogger {

    private final OpenRouterProperties properties;

    /** 업스트림 호출 한 번의 사용량. */
    public void record(AiCallSite site, TokenUsage usage) {
        if (usage == null || usage.isEmpty()) {
            // 업스트림이 usage를 안 내려준 경우. 조용히 넘기면 "호출이 없었다"와 구분되지 않으므로
            // 흔적은 남기되, 정상 동작을 방해하지 않게 DEBUG로 둔다.
            log.debug("ai.usage site={} model={} (upstream reported no usage)", label(site), properties.model());
            return;
        }
        log.info("ai.usage site={} model={} prompt={} completion={} total={}{}",
                label(site), properties.model(),
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), costSuffix(usage));
    }

    /**
     * 사용자 요청 하나에 들어간 업스트림 호출 전체의 합계. 에이전트 루프처럼 한 요청이 여러 번
     * 호출하는 경로에서만 의미가 있으며, {@code calls}가 곧 "이 요청이 업스트림을 몇 번 때렸는가"다.
     */
    public void recordTotal(AiCallSite site, int calls, TokenUsage usage) {
        if (calls <= 0 || usage == null || usage.isEmpty()) {
            return;
        }
        log.info("ai.usage site={} model={} calls={} prompt={} completion={} total={}{}",
                label(site), properties.model(), calls,
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), costSuffix(usage));
    }

    // cost는 usage accounting을 켠 응답에서만 오는 선택 필드라, 있을 때만 덧붙인다
    // (없을 때 cost=null을 찍으면 집계 스크립트가 0으로 오해한다).
    private static String costSuffix(TokenUsage usage) {
        return usage.cost() == null ? "" : " cost=" + usage.cost();
    }

    private static String label(AiCallSite site) {
        return site == null ? "unknown" : site.label();
    }
}
