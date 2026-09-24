package com.delaynomore.backend.domain.points.service;

import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.points.dto.PointLedgerResponse;
import com.delaynomore.backend.domain.points.repository.PointLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래 내역 조회(v0.31.0) — 읽기 전용. 이 서비스에는 포인트를 움직이는 메서드가 없다.
 *
 * <p>기표는 전부 잔액을 바꾸는 저장소의 원자 구간 안에서 일어나기 때문이다
 * ({@link ChallengeRepository} 주석). 여기에 "포인트를 준다" 같은 메서드를 만들면 잔액과
 * 원장이 갈라질 수 있는 두 번째 경로가 생긴다 — 그래서 읽기만 둔다.
 */
@Service
@RequiredArgsConstructor
public class PointLedgerService {

    /** 거래 내역 조회 상한 — 데모 규모라 페이지네이션 대신 최근 N건으로 자른다. */
    static final int MAX_ENTRIES = 50;

    private final PointLedgerRepository ledgerRepository;
    // 잔액(캐시)의 소유자는 여전히 챌린지 저장소다 — 여기서 별도로 계산하지 않고 물어본다.
    private final ChallengeRepository challengeRepository;

    /**
     * 내 거래 내역. 잔액과 원장 합계를 함께 내려 둘이 어긋나면 드러나게 한다.
     *
     * <p>@Transactional인 이유는 balanceOf가 지갑을 지연 생성하며 발행을 기표할 수 있기
     * 때문이다(신규 사용자의 첫 조회) — 그 둘은 한 단위여야 한다.
     */
    @Transactional
    public PointLedgerResponse myLedger(String owner) {
        int balance = challengeRepository.balanceOf(owner);
        return PointLedgerResponse.of(balance, ledgerRepository.sumOf(owner),
                ledgerRepository.findByAccount(owner, MAX_ENTRIES));
    }
}
