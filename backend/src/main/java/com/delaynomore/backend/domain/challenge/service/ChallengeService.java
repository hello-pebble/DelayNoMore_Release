package com.delaynomore.backend.domain.challenge.service;

import com.delaynomore.backend.domain.challenge.dto.ChallengeListResponse;
import com.delaynomore.backend.domain.challenge.dto.ChallengeResponse;
import com.delaynomore.backend.domain.challenge.dto.JoinResponse;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.support.ChallengeCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

// Goal Challenge — 챌린지 자동 생성과 참가.
// 이 서비스에는 정원·잔액·중복에 대한 검사 코드가 없다. 전부 저장소의 원자 구간 안에 있어야
// 하기 때문이다 — 여기서 "자리가 남았나?"를 먼저 확인하고 저장소를 호출하면, 확인과 참가 사이가
// 열려 동시 요청에서 정원을 넘긴다(고전적 TOCTOU). 근거는 docs/CONCURRENCY.md.
// 자동 생성의 "이미 있나?"도 같은 이유로 여기서 확인하지 않는다.
@Service
@RequiredArgsConstructor
public class ChallengeService {

    // 챌린지가 열리는 최소 인원 — 서로 다른 소유자 기준. 2명은 챌린지라기보다 약속이고,
    // 너무 크면 영영 안 열린다. 셋이 모이면 연다.
    private static final int SEED_THRESHOLD = 3;
    // 자동 생성 챌린지의 고정 조건. 개설자가 없으므로 사용자가 정할 값이 아니다.
    private static final int AUTO_CAPACITY = 5;
    private static final int AUTO_ENTRY_FEE = 100;
    // 개설자 자리에 들어가는 값 — 자동 생성 챌린지에는 주인이 없다는 표시다.
    private static final String AUTO_OWNER = "system";

    private final ChallengeRepository challengeRepository;

    public ChallengeListResponse list(String viewer) {
        Set<Long> joinedIds = challengeRepository.findJoinedChallengeIds(viewer);
        List<ChallengeResponse> challenges = challengeRepository.findAll().stream()
                .map(c -> ChallengeResponse.from(c, joinedIds.contains(c.id())))
                .toList();
        return new ChallengeListResponse(challengeRepository.balanceOf(viewer), challenges);
    }

    // 참가 — 검사·차감·예약·등록을 저장소가 한 덩어리로 처리한다. @Transactional은 JDBC 구현이
    // 조건부 UPDATE들을 하나의 원자 단위로 묶고, 실패 시(CHALLENGE_FULL) 앞선 차감·등록을
    // 롤백하기 위해 필요하다. 인메모리 구현은 맵의 키 단위 원자 구간이 같은 계약을 제공한다.
    @Transactional
    public JoinResponse join(long challengeId, String owner) {
        Challenge joined = challengeRepository.join(challengeId, owner, Instant.now().toString());
        return new JoinResponse(ChallengeResponse.from(joined, true), challengeRepository.balanceOf(owner));
    }

    // 계획이 고정될 때마다 호출된다 — 비슷한 조건의 계획이 SEED_THRESHOLD명분 모이면 챌린지를 연다.
    // 계획 도메인 타입도, 목표명도 받지 않는다: 분류는 계획을 만들 때 이미 끝났고(plans.category),
    // 여기서는 그 결과인 조건 키만 있으면 된다. 분류가 두 군데서 일어나지 않게 하는 것이 요점이다.
    //
    // [실패를 삼키지 않는다] 이 메서드는 계획 고정 트랜잭션 안에서 실행되지만 try/catch가 없다.
    // 저장소의 두 메서드가 중복·경합을 예외 없이 no-op으로 흡수하도록 설계돼 있어서, 여기서 나는
    // 예외는 전부 진짜 버그다. 감싸면 그것을 조용히 잃는다.
    public void onPlanConfirmed(String owner, String conditionKey) {
        ChallengeCondition.parse(conditionKey).ifPresent(condition -> {
            String now = Instant.now().toString();
            if (challengeRepository.recordSeed(condition.key(), owner, now) < SEED_THRESHOLD) {
                return;
            }
            challengeRepository.createIfNoOpenCondition(new Challenge(
                    null, AUTO_OWNER, condition.title(), condition.durationDays(),
                    AUTO_CAPACITY, AUTO_ENTRY_FEE, 0, now, condition.key()));
        });
    }
}
