package com.delaynomore.backend.domain.ai.usage;

import com.delaynomore.backend.global.config.AiRateLimitProperties;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 호출의 일일 상한(v0.30.0) — 종량제 키를 쓰는 데모 서버의 비용 방어선.
 *
 * <p><b>왜 두 층인가.</b> 소유자당 상한만 두면 막히지 않는다 — 게스트 ID는 브라우저가 만드는
 * 값이라 새로 발급하면 카운터가 초기화된다. 그래서 소유자 한도는 "정상 사용자의 폭주"를 막고,
 * 지갑을 실제로 지키는 것은 <b>전역 상한</b>이다. 전역 상한은 {@code OpenRouterClient}에 걸려
 * 있어 <b>어떤 진입점이 새로 생겨도 자동으로 덮인다</b>(레거시 /chats·/drafts 포함).
 *
 * <p><b>왜 인메모리인가.</b> 계획 생성 한도(v0.20.0)는 삭제를 살아남아야 해서 감사 이력을 세지만,
 * 비용 방어는 "지금 이 프로세스가 오늘 얼마나 썼는가"만 알면 된다. 단일 컨테이너 배포라
 * 카운터가 갈라질 일이 없고, 재기동하면 리셋되는 것은 감수한다(재기동이 잦다면 그쪽이 먼저
 * 문제다). 대신 스키마·DB 쓰기가 늘지 않는다.
 *
 * <p><b>왜 호출 수인가.</b> 토큰은 응답이 와야 알 수 있어 사전 차단에 쓸 수 없다. 호출 수로
 * 막고, 실제 소비량은 기존 {@code ai.usage} 로그로 사후 집계한다(AGENT.md 6장).
 *
 * <p>날짜 기준은 KST다 — 계획 생성 한도와 같은 자정에 리셋돼야 사용자가 규칙을 하나로 이해한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRateLimiter {

    private static final String GLOBAL_KEY = "__global__";

    private final AiRateLimitProperties properties;

    // 오늘(KST) 카운터. 날짜가 바뀌면 통째로 갈아끼운다 — 키별로 날짜를 들고 다니면 만료된 키가
    // 영원히 쌓인다(게스트 ID는 계속 새로 생긴다).
    private final Object dayLock = new Object();
    private volatile LocalDate currentDay = KstDates.today();
    private volatile ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /** 전역 상한 — 모든 업스트림 호출이 지난다. 한도가 남아 있으면 1 소비하고 true. */
    public boolean tryAcquireGlobal() {
        return tryAcquire(GLOBAL_KEY, properties.globalLimit(), properties.globalEnabled());
    }

    /** 소유자 상한 — owner를 아는 진입점(에이전트·슬랙)에서만 확인한다. */
    public boolean tryAcquireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return true; // 소유자를 모르는 레거시 경로는 전역 상한이 덮는다
        }
        return tryAcquire("owner:" + owner, properties.perOwnerLimit(), properties.perOwnerEnabled());
    }

    /** 지금 남은 여유 — health 응답과 운영 점검이 읽는다(소비하지 않는다). */
    public Snapshot snapshot() {
        Map<String, AtomicInteger> today = todayCounters();
        int globalUsed = used(today, GLOBAL_KEY);
        return new Snapshot(currentDay, globalUsed, properties.globalLimit(),
                properties.globalEnabled() && globalUsed >= properties.globalLimit());
    }

    public record Snapshot(LocalDate date, int globalUsed, int globalLimit, boolean globalExhausted) {
    }

    private boolean tryAcquire(String key, int limit, boolean enabled) {
        if (!enabled) {
            return true;
        }
        // 증가 후 초과면 되돌린다. "확인 후 증가"는 동시 호출에서 한도를 넘길 수 있다 —
        // 판정을 쓰기(increment) 안에 두는 이 저장소의 관례와 같은 형태다.
        AtomicInteger counter = todayCounters().computeIfAbsent(key, k -> new AtomicInteger());
        if (counter.incrementAndGet() > limit) {
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    private ConcurrentHashMap<String, AtomicInteger> todayCounters() {
        return countersFor(KstDates.today());
    }

    /**
     * 주어진 날짜의 카운터 맵. 마지막으로 본 날짜와 다르면 통째로 교체한다.
     *
     * <p>시계를 인자로 받는 이유는 테스트(자정 리셋)뿐이다 — 운영 경로는 언제나
     * {@link KstDates#today()}만 넘긴다(SlackDispatchService가 now를 한 번 읽어 쓰는 것과 같은 결).
     */
    ConcurrentHashMap<String, AtomicInteger> countersFor(LocalDate day) {
        if (!day.equals(currentDay)) {
            synchronized (dayLock) {
                if (!day.equals(currentDay)) {
                    counters = new ConcurrentHashMap<>();
                    currentDay = day;
                    log.info("ai.ratelimit reset date={}", day);
                }
            }
        }
        return counters;
    }

    private static int used(Map<String, AtomicInteger> counters, String key) {
        AtomicInteger counter = counters.get(key);
        return counter == null ? 0 : counter.get();
    }
}
