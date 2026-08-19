package com.delaynomore.backend.domain.challenge.service;

import com.delaynomore.backend.domain.challenge.dto.ChallengeCreateRequest;
import com.delaynomore.backend.domain.challenge.dto.ChallengeResponse;
import com.delaynomore.backend.domain.challenge.dto.JoinResponse;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.repository.InMemoryChallengeRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 단일 스레드 규칙 검증 — 정원·중복·잔액·존재의 각 실패가 올바른 ErrorCode로 나오는지.
// 동시 요청에서의 보장은 ChallengeServiceConcurrencyTest / ChallengeJoinConcurrencyIT가 맡는다.
class ChallengeServiceTest {

    private static final int INITIAL_BALANCE = 1000;
    private static final String HOST = "guest-host-0001";

    private final ChallengeRepository challengeRepository = new InMemoryChallengeRepository();
    private final ChallengeService challengeService = new ChallengeService(challengeRepository);

    private long open(int capacity, int entryFee) {
        return challengeService.create(new ChallengeCreateRequest("자격증 공부 14일", 14, capacity, entryFee), HOST).id();
    }

    @Test
    void create_개설자는_참가자가_아니다() {
        ChallengeResponse created = challengeService.create(
                new ChallengeCreateRequest("  자격증 공부 14일  ", 14, 5, 100), HOST);

        assertThat(created.title()).isEqualTo("자격증 공부 14일"); // 앞뒤 공백은 정리해 저장
        assertThat(created.participantCount()).isZero();
        assertThat(created.remainingSeats()).isEqualTo(5);
        assertThat(created.full()).isFalse();
        assertThat(created.mine()).isTrue();
        assertThat(created.joined()).isFalse();
    }

    @Test
    void join_성공하면_참가비만큼_차감되고_인원이_1_늘어난다() {
        long id = open(5, 100);

        JoinResponse joined = challengeService.join(id, "guest-a-0001");

        assertThat(joined.balance()).isEqualTo(INITIAL_BALANCE - 100);
        assertThat(joined.challenge().participantCount()).isEqualTo(1);
        assertThat(joined.challenge().remainingSeats()).isEqualTo(4);
        assertThat(joined.challenge().joined()).isTrue();
    }

    @Test
    void join_정원이_찼으면_409_CHALLENGE_FULL() {
        long id = open(2, 100);
        challengeService.join(id, "guest-a-0001");
        challengeService.join(id, "guest-b-0001");

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
        challengeService.join(id, "guest-a-0001");

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
        challengeService.join(first, "guest-a-0001");

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
        assertThat(listed.challenges().getFirst().mine()).isFalse();
        assertThat(listed.balance()).isEqualTo(INITIAL_BALANCE); // 최초 조회 시 지갑이 생긴다
    }
}
