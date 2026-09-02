package com.delaynomore.backend.domain.challenge.service;

import com.delaynomore.backend.domain.challenge.dto.JoinResponse;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.entity.ChallengeParticipant;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
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

    private static final int INITIAL_BALANCE = 1000;
    private static final String HOST = "guest-host-0001";
    private static final String CONDITION = "자격증:14";

    private final ChallengeRepository challengeRepository = new InMemoryChallengeRepository();
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
    void 정산_완주자가_없으면_전원_환불한다() {
        long id = openInstant(2, 100);
        joinAs("guest-a-0001", id, false);
        joinAs("guest-b-0001", id, false);

        challengeService.settleDue();

        assertThat(challengeRepository.balanceOf("guest-a-0001")).isEqualTo(INITIAL_BALANCE);
        assertThat(challengeRepository.balanceOf("guest-b-0001")).isEqualTo(INITIAL_BALANCE);
        assertThat(participantOf(id, "guest-a-0001").payout()).isEqualTo(100);
    }

    @Test
    void 정산_연결계획을_삭제한_완주자는_패배로_처리된다() {
        long id = openInstant(2, 100);
        joinAs("guest-a-0001", id, true);
        joinAs("guest-b-0001", id, true);
        // a의 계획 삭제 — 완주 증명 소실.
        long planId = participantOf(id, "guest-a-0001").planId();
        planRepository.deleteById(planId, plan -> { });

        challengeService.settleDue();

        // 완주자는 b 혼자 — 풀 200을 독식, a는 0.
        assertThat(challengeRepository.balanceOf("guest-b-0001")).isEqualTo(INITIAL_BALANCE - 100 + 200);
        assertThat(participantOf(id, "guest-a-0001").payout()).isZero();
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
