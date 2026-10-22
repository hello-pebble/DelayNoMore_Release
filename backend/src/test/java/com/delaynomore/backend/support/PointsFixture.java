package com.delaynomore.backend.support;

import com.delaynomore.backend.domain.points.repository.InMemoryPointLedgerRepository;
import com.delaynomore.backend.domain.points.repository.InMemoryPointWalletRepository;
import com.delaynomore.backend.domain.points.repository.PointWalletRepository;

/**
 * 포인트 스택(원장 → 지갑)을 한 번에 세우는 테스트 픽스처.
 *
 * <p>포인트가 계획·챌린지 양쪽의 협력자가 되면서 생성자 인자가 여러 테스트에 번졌다. 이 클래스는
 * 그 배선을 한 곳에 모아, <b>포인트가 관심사가 아닌 테스트</b>가 배선 때문에 깨지지 않게 한다
 * (포인트가 관심사인 테스트는 이 픽스처의 {@link #ledger()}·{@link #wallets()}를 직접 단언한다).
 *
 * <p>한 인스턴스 = 하나의 일관된 스택이다 — 지갑과 원장이 서로 다른 인스턴스를 보면 불변식이
 * 성립하지 않으므로, 정적 팩터리 대신 인스턴스로 둔다.
 */
public final class PointsFixture {

    private final InMemoryPointLedgerRepository ledger = new InMemoryPointLedgerRepository();
    private final PointWalletRepository wallets = new InMemoryPointWalletRepository(ledger);

    public InMemoryPointLedgerRepository ledger() {
        return ledger;
    }

    public PointWalletRepository wallets() {
        return wallets;
    }
}
