package com.delaynomore.backend.domain.challenge.service;

import com.delaynomore.backend.domain.challenge.dto.JoinResponse;
import com.delaynomore.backend.support.PointsFixture;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.entity.ChallengeParticipant;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.domain.points.repository.InMemoryPointLedgerRepository;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 단일 스레드 규칙 검증 — 참가(정원·중복·잔액·자격)와 정산(분배·환불·멱등)의 각 분기가 올바른
// 결과·ErrorCode로 나오는지. 동시 요청에서의 보장은 ChallengeServiceConcurrencyTest /
// ChallengeJoinConcurrencyIT가, 챌린지가 어떻게 생기는지는 ChallengeAutoGenerationTest가 맡는다.
//
// [정산 테스트의 시간] 시계를 주입하지 않는다 — durationDays=0 픽스처면 시작 즉시 종료(endsAt =
// startedAt)이고 모집 수명(createdAt + 0×2)도 즉시 만료라, 실제 시간을 기다리지 않고 정산 경로가
// 열린다. conditionKey는 문자열 매칭이라 기간과 어긋나도 참가 자격 판정에는 영향이 없다.
class ChallengeServiceTest {

    private final PointsFixture points = new PointsFixture();

    private static final int INITIAL_BALANCE = 1000;
    private static final String HOST = "guest-host-0001";
    private static final String CONDITION = "자격증:14";

    private final ChallengeRepository challengeRepository = new InMemoryChallengeRepository(points.wallets());
    private final InMemoryPlanRepository planRepository = new InMemoryPlanRepository();
    private final ChallengeService challengeService = new ChallengeService(challengeRepository, planRepository);

    // 개설 API가 없어졌으므로(v0.23.0) 픽스처는 저장소에 직접 넣는다.
    private long open(int capacity, int entryFee) {
        return open(capacity, entryFee, 14, Instant.now().toString());
    }

    private long open(int capacity, int entryFee, int durationDays, String createdAt) {
        return challengeRepository.save(new Challenge(null, HOST, "자격증 공부 14일", durationDays, capacity,
                entryFee, 0, createdAt, CONDITION, null, null)).id();
    }

    // 참가 자격이 되는 계획 — 카테고리 자격증 + 14일 = conditionKey "자격증:14".
    private long confirmedPlan(String owner, boolean allDone) {
        Map<String, Object> tasks = Map.of(
                "2026-09-01", List.of(Map.of("id", "t1", "content", "기출 풀기", "completed", true)),
                "2026-09-02", List.of(Map.of("id", "t2", "content", "오답 정리", "completed", allDone)));
        String now = Instant.now().toString();
        return planRepository.save(new Plan(null, owner, "정보처리기사 실기", 14, 2, "초급", tasks,
                "CONFIRMED", now, null, "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증")).id();
    }

    // 할 일이 하나도 없는 고정 계획 — 완주 조건은 "1개 이상 + 전부 완료"라 0 == 0은 완주가 아니다.
    private long emptyPlan(String owner) {
        String now = Instant.now().toString();
        return planRepository.save(new Plan(null, owner, "빈 계획", 14, 2, "초급", Map.of(),
                "CONFIRMED", now, null, "2026-09-01", "2026-09-14", now, System.currentTimeMillis(), "자격증")).id();
    }

    // 이미 마감이 지난 진행 중 챌린지(1일짜리, 이틀 전 시작) — 시계를 주입하지 않고 "마감 이후"를 만든다.
    // 인메모리 join은 시작 여부를 보지 않으므로 참가자를 채울 수 있다.
    private long openEnded(int entryFee) {
        String twoDaysAgo = Instant.now().minusSeconds(2 * 86_400L).toString();
        return challengeRepository.save(new Challenge(null, HOST, "마감 지난 챌린지", 1, 5,
                entryFee, 0, twoDaysAgo, CONDITION, twoDaysAgo, null)).id();
    }

    private void finishAllTasks(long planId) {
        planRepository.mutate(planId, plan -> {
            Map<String, Object> done = Map.of(
                    "2026-09-01", List.of(Map.of("id", "t1", "content", "기출 풀기", "completed", true)),
                    "2026-09-02", List.of(Map.of("id", "t2", "content", "오답 정리", "completed", true)));
            return new Plan(plan.id(), plan.owner(), plan.goalName(), plan.duration(), plan.dailyHours(),
                    plan.currentLevel(), done, plan.status(), plan.confirmedAt(), plan.completedAt(),
                    plan.startDate(), plan.endDate(), plan.createdAt(), System.currentTimeMillis(), plan.category());
        });
    }

    private JoinResponse joinAs(String owner, long challengeId, boolean allDone) {
        confirmedPlan(owner, allDone);
        return challengeService.join(challengeId, owner);
    }

    private ChallengeParticipant participantOf(long challengeId, String owner) {
        return challengeRepository.findParticipants(challengeId).stream()
                .filter(p -> p.owner().equals(owner))
                .findFirst().orElseThrow();
    }

    // === 참가 ===

    @Test
    void join_성공하면_참가비만큼_차감되고_인원이_1_늘어난다() {
        long id = open(5, 100);

        JoinResponse joined = joinAs("guest-a-0001", id, false);

        assertThat(joined.balance()).isEqualTo(INITIAL_BALANCE - 100);
        assertThat(joined.challenge().participantCount()).isEqualTo(1);
        assertThat(joined.challenge().remainingSeats()).isEqualTo(4);
        assertThat(joined.challenge().joined()).isTrue();
    }

    @Test
    void join_같은조건_고정계획이_없으면_400_CHALLENGE_PLAN_REQUIRED_이고_아무것도_변하지_않는다() {
        long id = open(5, 100);

        assertThatThrownBy(() -> challengeService.join(id, "guest-noplan-01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_PLAN_REQUIRED);

        assertThat(challengeRepository.balanceOf("guest-noplan-01")).isEqualTo(INITIAL_BALANCE);
        assertThat(challengeRepository.findById(id).orElseThrow().participantCount()).isZero();
    }

    @Test
    void join_계획이_여러개면_최신_저장분이_연결된다() {
        long id = open(5, 100);
        confirmedPlan("guest-a-0001", false);
        long newer = confirmedPlan("guest-a-0001", false);

        challengeService.join(id, "guest-a-0001");

        assertThat(participantOf(id, "guest-a-0001").planId()).isEqualTo(newer);
    }

    @Test
    void join_마지막_자리를_채우면_시작시각이_기록된다() {
        long id = open(2, 100);
        joinAs("guest-a-0001", id, false);
        assertThat(challengeRepository.findById(id).orElseThrow().started()).isFalse();

        joinAs("guest-b-0001", id, false);

        Challenge started = challengeRepository.findById(id).orElseThrow();
        assertThat(started.started()).isTrue();
        assertThat(started.endsAt()).isNotNull();
    }

    @Test
    void join_정원이_찼으면_409_CHALLENGE_FULL() {
        long id = open(2, 100);
        joinAs("guest-a-0001", id, false);
        joinAs("guest-b-0001", id, false);

        confirmedPlan("guest-c-0001", false);
        assertThatThrownBy(() -> challengeService.join(id, "guest-c-0001"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_FULL);

        // 마감으로 거절당한 사람의 포인트는 차감되지 않는다.
        assertThat(challengeRepository.balanceOf("guest-c-0001")).isEqualTo(INITIAL_BALANCE);
    }

    @Test
    void join_이미_참가했으면_409_CHALLENGE_ALREADY_JOINED_이고_중복차감되지_않는다() {
        long id = open(5, 100);
        joinAs("guest-a-0001", id, false);

        assertThatThrownBy(() -> challengeService.join(id, "guest-a-0001"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_ALREADY_JOINED);

        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL_BALANCE - 100);
        assertThat(challengeRepository.findById(id).orElseThrow().participantCount()).isEqualTo(1);
    }

    @Test
    void join_잔액이_모자라면_400_POINTS_INSUFFICIENT_이고_자리도_소모되지_않는다() {
        // 초기 잔액 1000 → 참가비 600짜리 두 챌린지에 연달아 참가할 수는 없다.
        long first = open(5, 600);
        long second = open(5, 600);
        joinAs("guest-a-0001", first, false);

        assertThatThrownBy(() -> challengeService.join(second, "guest-a-0001"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.POINTS_INSUFFICIENT);

        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL_BALANCE - 600);
        assertThat(challengeRepository.findById(second).orElseThrow().participantCount()).isZero();
    }

    @Test
    void join_없는_챌린지면_404_CHALLENGE_NOT_FOUND() {
        assertThatThrownBy(() -> challengeService.join(99999L, "guest-a-0001"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_NOT_FOUND);
    }

    @Test
    void list_는_남이_만든_챌린지도_보여준다() {
        open(5, 100);

        var listed = challengeService.list("guest-other-01");

        assertThat(listed.challenges()).hasSize(1);
        assertThat(listed.challenges().get(0).status()).isEqualTo("RECRUITING");
        assertThat(listed.challenges().get(0).myPayout()).isNull();
        assertThat(listed.balance()).isEqualTo(INITIAL_BALANCE); // 최초 조회 시 지갑이 생긴다
    }

    // === 정산 ===

    // durationDays=0: 정원이 차는 순간 endsAt == startedAt이라 settleDue가 즉시 완주 정산을 연다.
    private long openInstant(int capacity, int entryFee) {
        return open(capacity, entryFee, 0, Instant.now().toString());
    }

    @Test
    void 정산_완주자끼리_풀을_균등분배하고_나머지는_소멸한다() {
        long id = openInstant(5, 100);
        joinAs("guest-w1", id, true);
        joinAs("guest-w2", id, true);
        joinAs("guest-w3", id, true);
        joinAs("guest-l1", id, false);
        joinAs("guest-l2", id, false);

        challengeService.settleDue();

        // pool 500, 완주 3명 → 각 166, 나머지 2P 소멸. 미완주는 0.
        assertThat(challengeRepository.balanceOf("guest-w1")).isEqualTo(INITIAL_BALANCE - 100 + 166);
        assertThat(challengeRepository.balanceOf("guest-l1")).isEqualTo(INITIAL_BALANCE - 100);
        assertThat(participantOf(id, "guest-w2").payout()).isEqualTo(166);
        assertThat(participantOf(id, "guest-l2").payout()).isZero();
        assertThat(challengeRepository.findById(id).orElseThrow().settled()).isTrue();
    }

    @Test
    void 정산_완주자가_없으면_풀_전액이_소멸하고_예치_계정은_0이_된다() {
        long id = openInstant(2, 100);
        joinAs("guest-a-0001", id, false);
        joinAs("guest-b-0001", id, false);

        challengeService.settleDue();

        // 기간 내 완주자가 없으면 참가비는 돌려받지 못한다(환불 아님) — 풀 200이 소각 계정으로 옮겨진다.
        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL_BALANCE - 100);
        assertThat(challengeRepository.balanceOf("guest-b-0001")).isEqualTo(INITIAL_BALANCE - 100);
        assertThat(participantOf(id, "guest-a-0001").payout()).isZero();
        assertThat(points.ledger().sumOf(PointAccounts.escrowOfChallenge(id))).isZero();
        assertThat(points.ledger().sumOf(PointAccounts.BURN)).isEqualTo(200);
        assertThat(points.ledger().totalSum()).isZero();
    }

    @Test
    void 정산_나누어떨어지면_소멸_기표가_없다() {
        long id = openInstant(2, 100);
        joinAs("guest-a-0001", id, true);
        joinAs("guest-b-0001", id, true);

        challengeService.settleDue();

        // 풀 200을 완주자 2명이 100씩 — 남는 몫이 없으니 0원 이동을 기표하지 않는다(PointTransfer는 0원을 거부한다).
        assertThat(points.ledger().sumOf(PointAccounts.BURN)).isZero();
        assertThat(points.ledger().sumOf(PointAccounts.escrowOfChallenge(id))).isZero();
        assertThat(points.ledger().findByAccount(PointAccounts.BURN, 10)).isEmpty();
    }

    @Test
    void 완주_조건은_할일이_1개_이상이고_전부_완료다_빈_목표는_완주가_아니다() {
        long id = open(5, 100);
        emptyPlan("guest-empty-01");

        challengeService.join(id, "guest-empty-01");

        assertThat(participantOf(id, "guest-empty-01").completedAt()).isNull();
    }

    @Test
    void 마감_전_목표를_삭제하면_완주_기록이_사라진다() {
        long id = open(2, 100); // 14일 — 마감이 미래
        joinAs("guest-a-0001", id, true);
        joinAs("guest-b-0001", id, true);
        assertThat(participantOf(id, "guest-a-0001").completedAt()).isNotNull();

        // PlanService.delete가 삭제 직전 목표 행 잠금 안에서 부르는 훅
        challengeService.onPlanProgress(participantOf(id, "guest-a-0001").planId(), false);

        assertThat(participantOf(id, "guest-a-0001").completedAt()).isNull();
        assertThat(participantOf(id, "guest-b-0001").completedAt()).isNotNull();
    }

    @Test
    void 마감_후_체크는_완주로_기록되지_않는다() {
        long id = openEnded(100);
        joinAs("guest-a-0001", id, false);
        long planId = participantOf(id, "guest-a-0001").planId();

        finishAllTasks(planId);                         // 마감이 지난 뒤 100%를 만들어도
        challengeService.onPlanProgress(planId, true);  // 저장소가 마감 이후의 변경을 무시한다
        challengeService.settleDue();

        assertThat(participantOf(id, "guest-a-0001").completedAt()).isNull();
        assertThat(participantOf(id, "guest-a-0001").payout()).isZero();
        assertThat(points.ledger().sumOf(PointAccounts.BURN)).isEqualTo(100);
    }

    @Test
    void 마감_후_목표를_삭제해도_동결된_완주_기록은_보존되고_정산된다() {
        long id = openEnded(100);
        joinAs("guest-a-0001", id, true); // 참가 시점에 이미 100% — 마감 전 기록
        long planId = participantOf(id, "guest-a-0001").planId();

        challengeService.onPlanProgress(planId, false); // 마감 후 삭제 훅 — 무시된다
        planRepository.deleteById(planId, plan -> { });
        challengeService.settleDue();

        assertThat(participantOf(id, "guest-a-0001").payout()).isEqualTo(100); // 풀 100을 혼자 받는다
        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL_BALANCE);
        assertThat(points.ledger().sumOf(PointAccounts.BURN)).isZero();
    }

    @Test
    void 정산_후_목표를_삭제해도_결과는_유지되고_응답은_삭제됨으로_표시된다() {
        long id = openInstant(2, 100);
        joinAs("guest-a-0001", id, true);
        joinAs("guest-b-0001", id, false);
        challengeService.settleDue();
        long planId = participantOf(id, "guest-a-0001").planId();

        planRepository.deleteById(planId, plan -> { });
        var view = challengeService.list("guest-a-0001").challenges().getFirst();

        assertThat(view.status()).isEqualTo("CLOSED");
        assertThat(view.myPayout()).isEqualTo(200);        // 확정된 정산 결과
        assertThat(view.myPlan().deleted()).isTrue();      // 화면은 "삭제되었어요"만 말한다
        assertThat(view.myPlan().completed()).isTrue();    // 기록은 보존
    }

    @Test
    void 목록_응답은_참가에_쓴_목표를_보여준다() {
        long id = open(5, 100);
        joinAs("guest-a-0001", id, false);

        var view = challengeService.list("guest-a-0001").challenges().getFirst();

        assertThat(view.myPlan().goalName()).isEqualTo("정보처리기사 실기");
        assertThat(view.myPlan().ratePercent()).isEqualTo(50); // 1/2
        assertThat(view.myPlan().deleted()).isFalse();
        assertThat(challengeService.list("guest-other-01").challenges().getFirst().myPlan()).isNull();
    }

    @Test
    void 정산_모집이_수명을_넘기면_미달인_채로_전원_환불하고_마감한다() {
        long id = openInstant(5, 100); // durationDays=0 → 수명(2배)도 0 → 즉시 만료
        joinAs("guest-a-0001", id, false);

        challengeService.settleDue();

        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL_BALANCE);
        Challenge settled = challengeRepository.findById(id).orElseThrow();
        assertThat(settled.settled()).isTrue();
        assertThat(settled.started()).isFalse();
    }

    @Test
    void 정산_마감된_조건에는_같은_조건의_다음_챌린지가_열릴_수_있다() {
        long id = openInstant(5, 100);
        joinAs("guest-a-0001", id, false);
        challengeService.settleDue(); // 미달 환불 마감

        challengeRepository.createIfNoOpenCondition(new Challenge(null, "system", "자격증 공부 14일",
                14, 5, 100, 0, Instant.now().toString(), CONDITION, null, null));

        // 마감된 챌린지는 "모집 중"이 아니므로 새 챌린지가 만들어진다.
        assertThat(challengeRepository.findAll()).hasSize(2);
    }

    @Test
    void 정산_재호출은_아무것도_바꾸지_않는다() {
        long id = openInstant(2, 100);
        joinAs("guest-a-0001", id, true);
        joinAs("guest-b-0001", id, false);
        challengeService.settleDue();
        int winnerBalance = challengeRepository.balanceOf("guest-a-0001");

        challengeService.settleDue();

        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(winnerBalance);
        assertThat(participantOf(id, "guest-a-0001").payout()).isEqualTo(200);
    }

    @Test
    void 정산_마감된_챌린지에_참가하면_409_CHALLENGE_CLOSED() {
        long id = openInstant(5, 100);
        joinAs("guest-a-0001", id, false);
        challengeService.settleDue(); // 미달 환불 마감 — 정원은 여전히 남아 있다

        confirmedPlan("guest-b-0001", false);
        assertThatThrownBy(() -> challengeService.join(id, "guest-b-0001"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_CLOSED);

        assertThat(challengeRepository.balanceOf("guest-b-0001")).isEqualTo(INITIAL_BALANCE);
    }

    // === 참가자 현황(리더보드) ===

    @Test
    void 참가자현황_완료율_내림차순으로_정렬되고_내_줄에만_me가_선다() {
        long id = open(5, 100);
        joinAs("guest-high", id, true);   // 2/2 = 100%
        joinAs("guest-low", id, false);   // 1/2 = 50%

        var board = challengeService.participants(id, "guest-low");

        assertThat(board).hasSize(2);
        assertThat(board.get(0).ratePercent()).isEqualTo(100);
        assertThat(board.get(0).me()).isFalse();
        assertThat(board.get(1).ratePercent()).isEqualTo(50);
        assertThat(board.get(1).me()).isTrue();
        // 미정산이라 payout은 전부 null이다.
        assertThat(board).allSatisfy(p -> assertThat(p.payout()).isNull());
    }

    @Test
    void 참가자현황_연결계획을_삭제한_참가자는_0퍼센트로_선다() {
        long id = open(5, 100);
        joinAs("guest-a-0001", id, true);
        planRepository.deleteById(participantOf(id, "guest-a-0001").planId(), plan -> { });

        var board = challengeService.participants(id, "guest-a-0001");

        assertThat(board.get(0).ratePercent()).isZero();
        assertThat(board.get(0).total()).isZero();
    }

    @Test
    void 참가자현황_없는_챌린지면_404() {
        assertThatThrownBy(() -> challengeService.participants(99999L, "guest-a-0001"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_NOT_FOUND);
    }

    @Test
    void 정산_기간이_남은_진행중_챌린지는_건드리지_않는다() {
        long id = open(2, 100); // durationDays=14 — endsAt이 미래
        joinAs("guest-a-0001", id, true);
        joinAs("guest-b-0001", id, true);

        challengeService.settleDue();

        assertThat(challengeRepository.findById(id).orElseThrow().settled()).isFalse();
        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL_BALANCE - 100);
    }
}
