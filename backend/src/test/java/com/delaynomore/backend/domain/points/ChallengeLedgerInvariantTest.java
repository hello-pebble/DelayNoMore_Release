package com.delaynomore.backend.domain.points;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.points.dto.PointLedgerResponse;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.domain.points.repository.InMemoryPointLedgerRepository;
import com.delaynomore.backend.domain.points.service.PointLedgerService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원장 불변식의 실측(v0.31.0) — 챌린지 전 경로(지급·참가·정산·환불)를 실제로 돌린 뒤
 * <b>모든 계정의 원장 합계 = 지갑 잔액</b>, <b>전체 합계 = 0</b>이 성립하는지 본다.
 *
 * <p>이 테스트가 이 릴리스의 합격 기준이다. 잔액 캐시를 남겨 둔 채 원장을 도입했으므로,
 * 둘이 어긋나지 않는다는 것을 매 경로마다 증명하지 않으면 원장은 장식이 된다.
 *
 * <p>시간은 주입하지 않는다 — durationDays=0 픽스처면 정원이 차는 순간 종료라 정산 경로가
 * 즉시 열린다(ChallengeServiceTest와 같은 방식).
 */
class ChallengeLedgerInvariantTest {

    private static final int INITIAL = 1000;
    private static final String CONDITION = "자격증:14";

    private final InMemoryPointLedgerRepository ledger = new InMemoryPointLedgerRepository();
    private final ChallengeRepository challengeRepository = new InMemoryChallengeRepository(ledger);
    private final InMemoryPlanRepository planRepository = new InMemoryPlanRepository();
    private final ChallengeService challengeService = new ChallengeService(challengeRepository, planRepository);
    private final PointLedgerService ledgerService = new PointLedgerService(ledger, challengeRepository);

    // === 픽스처 ===

    private long open(int capacity, int entryFee, int durationDays) {
        return challengeRepository.save(new Challenge(null, "system", "자격증 공부", durationDays, capacity,
                entryFee, 0, Instant.now().toString(), CONDITION, null, null)).id();
    }

    private void joinAs(String owner, long challengeId, boolean allDone) {
        Map<String, Object> tasks = Map.of(
                "2026-09-01", List.of(Map.of("id", "t1", "content", "기출 풀기", "completed", true)),
                "2026-09-02", List.of(Map.of("id", "t2", "content", "오답 정리", "completed", allDone)));
        String now = Instant.now().toString();
        planRepository.save(new Plan(null, owner, "정보처리기사", 14, 2, "초급", tasks, "CONFIRMED",
                now, null, "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증"));
        challengeService.join(challengeId, owner);
    }

    /** 이 저장소의 불변식 둘 — 어느 경로를 돌든 항상 참이어야 한다. */
    private void assertInvariants(String... owners) {
        for (String owner : owners) {
            assertThat(ledger.sumOf(owner))
                    .as("계정 %s의 원장 합계는 지갑 잔액과 같아야 한다", owner)
                    .isEqualTo(challengeRepository.balanceOf(owner));
        }
        assertThat(ledger.totalSum()).as("전체 원장 합계는 언제나 0").isZero();
    }

    // === 경로별 검증 ===

    @Test
    void 최초_조회가_지갑을_만들면_발행_계정에서_나온_것으로_기표된다() {
        challengeRepository.balanceOf("guest-a-0001");

        assertThat(ledger.sumOf("guest-a-0001")).isEqualTo(INITIAL);
        // 신규 지급은 무에서 생기지 않는다 — 발행 계정이 그만큼 음수가 된다(= 총 발행량).
        assertThat(ledger.sumOf(PointAccounts.ISSUANCE)).isEqualTo(-INITIAL);
        assertInvariants("guest-a-0001");
    }

    @Test
    void 같은_사람을_여러_번_조회해도_발행은_한_번뿐이다() {
        for (int i = 0; i < 5; i++) {
            challengeRepository.balanceOf("guest-a-0001");
        }

        // 목록 조회마다 지갑 지연 생성 경로를 타므로, 여기가 새면 조회 횟수만큼 발행된다.
        assertThat(ledger.sumOf(PointAccounts.ISSUANCE)).isEqualTo(-INITIAL);
        assertInvariants("guest-a-0001");
    }

    @Test
    void 참가비는_그_챌린지의_예치_계정으로_들어간다() {
        long id = open(5, 100, 14);

        joinAs("guest-a-0001", id, false);

        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL - 100);
        assertThat(ledger.sumOf(PointAccounts.escrowOfChallenge(id))).isEqualTo(100);
        assertInvariants("guest-a-0001");
    }

    @Test
    void 참가에_실패하면_원장에도_남지_않는다() {
        // 정원 1을 먼저 채운 뒤 두 번째 사람이 CHALLENGE_FULL로 거절당하는 경로.
        long id = open(1, 100, 14);
        joinAs("guest-a-0001", id, false);
        Map<String, Object> tasks = Map.of("2026-09-01",
                List.of(Map.of("id", "t1", "content", "기출", "completed", true)));
        String now = Instant.now().toString();
        planRepository.save(new Plan(null, "guest-b-0001", "정보처리기사", 14, 2, "초급", tasks, "CONFIRMED",
                now, null, "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증"));

        try {
            challengeService.join(id, "guest-b-0001");
        } catch (RuntimeException expected) {
            // 거절 — 아래 단언이 본체다
        }

        // 거절당한 사람에게는 신규 지급만 있고 참가비 기표는 없다.
        assertThat(ledger.findByAccount("guest-b-0001", 50))
                .extracting(e -> e.kind().name())
                .containsExactly(PointTxKind.SIGNUP_BONUS.name());
        assertInvariants("guest-a-0001", "guest-b-0001");
    }

    @Test
    void 완주_정산_후_예치_계정에_남는_잔액이_소멸한_나머지다() {
        // 참가비 100 × 4명 = 400을 완주자 3명이 나누면 133씩, 나머지 1이 남는다.
        long id = open(4, 100, 0);
        joinAs("guest-w1-001", id, true);
        joinAs("guest-w2-001", id, true);
        joinAs("guest-w3-001", id, true);
        joinAs("guest-l1-001", id, false);

        challengeService.settleDue();

        assertThat(challengeRepository.balanceOf("guest-w1-001")).isEqualTo(INITIAL - 100 + 133);
        assertThat(challengeRepository.balanceOf("guest-l1-001")).isEqualTo(INITIAL - 100);
        // 소멸분이 추측이 아니라 관측된다 — 400 - 133×3 = 1.
        assertThat(ledger.sumOf(PointAccounts.escrowOfChallenge(id))).isEqualTo(1);
        assertInvariants("guest-w1-001", "guest-w2-001", "guest-w3-001", "guest-l1-001");
    }

    @Test
    void 전원_환불이면_예치_계정이_정확히_비워진다() {
        // 완주자가 없는 경우 — 풀을 삼키지 않고 전원에게 참가비를 돌려준다.
        long id = open(2, 100, 0);
        joinAs("guest-a-0001", id, false);
        joinAs("guest-b-0001", id, false);

        challengeService.settleDue();

        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL);
        assertThat(ledger.sumOf(PointAccounts.escrowOfChallenge(id))).isZero();
        assertInvariants("guest-a-0001", "guest-b-0001");
    }

    @Test
    void 거래_내역은_사유와_거래_직후_잔액을_함께_돌려준다() {
        long id = open(4, 100, 0);
        joinAs("guest-a-0001", id, true);
        joinAs("guest-b-0001", id, true);
        joinAs("guest-c-0001", id, true);
        joinAs("guest-d-0001", id, true);
        challengeService.settleDue();

        PointLedgerResponse response = ledgerService.myLedger("guest-a-0001");

        assertThat(response.balance()).isEqualTo(response.ledgerSum()); // 화면에서 바로 드러나는 검증
        assertThat(response.entries()).extracting(PointLedgerResponse.Entry::kind)
                .containsExactly("CHALLENGE_PAYOUT", "CHALLENGE_ENTRY", "SIGNUP_BONUS");
        assertThat(response.entries()).extracting(PointLedgerResponse.Entry::label)
                .containsExactly("챌린지 배당", "챌린지 참가비", "신규 지급");
        // 거래 직후 잔액은 현재 잔액에서 거꾸로 복원한 값이다 — 가장 오래된 줄은 지급 직후의 1000.
        assertThat(response.entries()).extracting(PointLedgerResponse.Entry::balanceAfter)
                .containsExactly(INITIAL - 100 + 100, INITIAL - 100, INITIAL);
    }
}
