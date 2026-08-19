package com.delaynomore.backend.domain.challenge.service;

import com.delaynomore.backend.domain.challenge.dto.ChallengeCreateRequest;
import com.delaynomore.backend.domain.challenge.dto.ChallengeListResponse;
import com.delaynomore.backend.domain.challenge.dto.ChallengeResponse;
import com.delaynomore.backend.domain.challenge.dto.JoinResponse;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

// Goal Challenge — 정원이 한정된 목표 챌린지 개설과 참가.
// 이 서비스에는 정원·잔액·중복에 대한 검사 코드가 없다. 전부 저장소의 원자 구간 안에 있어야
// 하기 때문이다 — 여기서 "자리가 남았나?"를 먼저 확인하고 저장소를 호출하면, 확인과 참가 사이가
// 열려 동시 요청에서 정원을 넘긴다(고전적 TOCTOU). 근거는 docs/CONCURRENCY.md.
@Service
@RequiredArgsConstructor
public class ChallengeService {

    private final ChallengeRepository challengeRepository;

    public ChallengeResponse create(ChallengeCreateRequest request, String owner) {
        Challenge saved = challengeRepository.save(request.toChallenge(owner, Instant.now().toString()));
        return ChallengeResponse.from(saved, owner, false);
    }

    public ChallengeListResponse list(String viewer) {
        Set<Long> joinedIds = challengeRepository.findJoinedChallengeIds(viewer);
        List<ChallengeResponse> challenges = challengeRepository.findAll().stream()
                .map(c -> ChallengeResponse.from(c, viewer, joinedIds.contains(c.id())))
                .toList();
        return new ChallengeListResponse(challengeRepository.balanceOf(viewer), challenges);
    }

    // 참가 — 검사·차감·예약·등록을 저장소가 한 덩어리로 처리한다. @Transactional은 JDBC 구현이
    // 조건부 UPDATE들을 하나의 원자 단위로 묶고, 실패 시(CHALLENGE_FULL) 앞선 차감·등록을
    // 롤백하기 위해 필요하다. 인메모리 구현은 맵의 키 단위 원자 구간이 같은 계약을 제공한다.
    @Transactional
    public JoinResponse join(long challengeId, String owner) {
        Challenge joined = challengeRepository.join(challengeId, owner, Instant.now().toString());
        return new JoinResponse(ChallengeResponse.from(joined, owner, true), challengeRepository.balanceOf(owner));
    }
}
