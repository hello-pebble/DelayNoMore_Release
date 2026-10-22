package com.delaynomore.backend.domain.challenge.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 챌린지 정산용 트랜잭션 도구(v0.34.0) — postgres 프로필 전용.
 *
 * <p>전파를 {@code REQUIRES_NEW}로 못 박는 이유: 기본값 REQUIRED는 바깥 트랜잭션이 있으면 거기에 합류한다.
 * 호출자(컨트롤러·에이전트 도구·테스트)가 어떤 트랜잭션 안에 있어도 챌린지 한 건의 정산권 선점·지급·소멸이
 * <b>자기 트랜잭션에서 따로 커밋/롤백</b>돼야 한다 — 한 건의 실패가 다른 건의 커밋을 되돌리거나, 여러 건의
 * 지갑 잠금이 한 트랜잭션에 쌓여 잠금 순서가 뒤집히지 않도록.
 *
 * <p>이 빈이 없거나 {@code ChallengeService}에 주입되지 않으면 기동 단계에서 실패한다
 * ({@link SettlementTransactionGuard}) — 트랜잭션 없이 정산하면 지급 실패 시 정산권 선점까지 롤백한다는
 * 보장이 깨진다.
 */
@Configuration
@Profile("postgres")
public class SettlementTransactionConfig {

    @Bean
    @Qualifier("challengeSettlementTx")
    public TransactionTemplate challengeSettlementTx(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
