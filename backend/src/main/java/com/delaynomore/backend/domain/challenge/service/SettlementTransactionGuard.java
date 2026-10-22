package com.delaynomore.backend.domain.challenge.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * postgres 프로필에서 정산용 트랜잭션 도구가 {@link ChallengeService}에 주입되지 않았으면 기동을 실패시킨다.
 *
 * <p>설정 누락을 조용히 넘기고 "트랜잭션 없이 직접 실행"으로 떨어지면, 지급이 도중에 실패해도 정산권 선점
 * (settled_at)이 롤백되지 않아 정산이 반쯤 끝난 채 영영 재시도되지 않는다. 그 경로는 인메모리 단위 테스트
 * 전용이다.
 */
@Component
@Profile("postgres")
public class SettlementTransactionGuard {

    public SettlementTransactionGuard(ChallengeService challengeService) {
        if (!challengeService.hasSettlementTransaction()) {
            throw new IllegalStateException(
                    "챌린지 정산용 트랜잭션(challengeSettlementTx)이 ChallengeService에 주입되지 않았습니다. "
                            + "postgres 프로필에서는 필수입니다(SettlementTransactionConfig).");
        }
    }
}
