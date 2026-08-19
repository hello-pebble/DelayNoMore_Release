package com.delaynomore.backend.domain.challenge.repository;

import com.delaynomore.backend.domain.challenge.entity.Challenge;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// 챌린지 저장소 계약 — 구현은 프로필로 선택된다(PlanRepository와 같은 관례):
//   기본(!postgres) = InMemoryChallengeRepository(휘발성, 단위/동시성 테스트용)
//   postgres        = JdbcChallengeRepository(PostgreSQL 영속화)
//
// [지갑이 왜 여기 있는가] 참가 1회는 "중복 검사 + 참가비 차감 + 자리 예약 + 참가자 등록" 네 가지를
// 함께 성립시켜야 한다. 지갑을 별도 저장소로 쪼개면 원자 구간이 둘로 갈라져, 자리를 못 얻은 참가자의
// 포인트만 사라지는 상태가 만들어질 수 있다. 그래서 join은 저장소 하나가 통째로 책임진다.
//
// [원자성 계약] join은 "검사와 변경 사이에 다른 참가 요청이 끼어들 수 없는 구간" 안에서 실행된다.
//   - 인메모리: ConcurrentHashMap.computeIfPresent의 키 단위 원자 구간
//   - JDBC    : 호출자의 @Transactional + 조건부 UPDATE(WHERE participant_count < capacity)
// join이 BusinessException을 던지면 저장소는 변경되지 않은 채 예외가 전파된다
// (인메모리 = 모든 검사를 모든 변경보다 앞에 수행, JDBC = 트랜잭션 롤백). PlanRepository의
// 가드 람다 계약과 같은 규칙이다. 근거와 대안 비교는 docs/CONCURRENCY.md.
public interface ChallengeRepository {

    // 새 챌린지를 저장하고 저장소가 발급한 ID가 실린 사본을 돌려준다.
    Challenge save(Challenge challenge);

    // 전체 목록, createdAt 내림차순(동률은 id 내림차순). 참가하려면 남이 만든 챌린지가 보여야
    // 하므로 계획 보관함(findAllByOwner)과 달리 소유자 스코프가 없다 — 공개 모집 게시판이다.
    List<Challenge> findAll();

    Optional<Challenge> findById(long id);

    // 여러 챌린지의 참가자 여부를 한 번에 묻기 위한 조회 — 목록 응답의 joined 플래그용(N+1 방지).
    Set<Long> findJoinedChallengeIds(String owner);

    // 지갑 잔액. 지갑이 없으면 초기 잔액을 만들어 돌려준다(최초 조회 시 지연 생성).
    int balanceOf(String owner);

    // 참가 — 정원·중복·잔액 검사와 네 건의 변경을 하나의 원자 구간에서 처리하고 갱신된 챌린지를
    // 돌려준다. 실패는 BusinessException으로 던지며 그때 저장소는 변경되지 않는다:
    //   CHALLENGE_NOT_FOUND / CHALLENGE_ALREADY_JOINED / POINTS_INSUFFICIENT / CHALLENGE_FULL
    Challenge join(long challengeId, String owner, String joinedAt);
}
