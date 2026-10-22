package com.delaynomore.backend.domain.challenge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 접속자가 없어도 만기 챌린지를 정산한다(v0.34.0). 지금까지는 누가 목록을 열거나 에이전트 도구가
 * 불릴 때만 정산돼, 아무도 들어오지 않으면 만기가 지나도 참가비가 잠겨 있었다.
 *
 * <p>목록 조회의 lazy 정산은 그대로 둔다(즉시 반영용). 겹쳐 돌아도 안전한 이유는 이중 정산 판정이
 * 이 클래스의 if가 아니라 {@code claimSettlement}의 조건부 UPDATE이기 때문이다 — 스케줄러와 lazy가 같은
 * 챌린지를 동시에 잡아도 정확히 하나만 정산권을 얻는다. 챌린지 한 건 = 한 트랜잭션이고, 예외는 삼키지
 * 않는다(스케줄러 스레드가 로그를 남기고 다음 턴에 재시도 — 실패 건은 claim째 롤백돼 있다).
 */
@Component
@RequiredArgsConstructor
public class ChallengeSettlementScheduler {

    private final ChallengeService challengeService;

    // 기동 직후 1분 뒤 첫 실행, 이후 직전 실행이 끝난 시점부터 5분 간격. 정산 시각의 정밀도는 분 단위면
    // 충분하다(기간이 일 단위) — 더 촘촘히 돌릴 이유가 없다.
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void settleDue() {
        challengeService.settleDue();
    }
}
