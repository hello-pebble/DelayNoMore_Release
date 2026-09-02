package com.delaynomore.backend.domain.challenge.repository;

import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.entity.ChallengeParticipant;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// 챌린지 저장소 계약 — 구현은 프로필로 선택된다(PlanRepository와 같은 관례):
//   기본(!postgres) = InMemoryChallengeRepository(휘발성, 단위/동시성 테스트용)
//   postgres        = JdbcChallengeRepository(PostgreSQL 영속화)
//
// [지갑이 왜 여기 있는가] 참가 1회는 "중복 검사 + 참가비 차감 + 자리 예약 + 참가자 등록" 네 가지를
// 함께 성립시켜야 한다. 지갑을 별도 저장소로 쪼개면 원자 구간이 둘로 갈라져, 자리를 못 얻은 참가자의
// 포인트만 사라지는 상태가 만들어질 수 있다. 그래서 join은 저장소 하나가 통째로 책임진다.
// 정산의 지급(recordPayout)도 같은 이유로 여기 있다.
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

    // 이 소유자의 참가 레코드를 챌린지 id로 색인해 한 번에 돌려준다 — 목록 응답의 joined 플래그와
    // 정산 결과(myPayout)용(N+1 방지). v0.25.0에서 Set<Long> findJoinedChallengeIds를 대체했다.
    Map<Long, ChallengeParticipant> findMyParticipations(String owner);

    // 한 챌린지의 참가자 전원 — 정산이 완주 판정·지급 대상을 읽는 용도.
    List<ChallengeParticipant> findParticipants(long challengeId);

    // 지갑 잔액. 지갑이 없으면 초기 잔액을 만들어 돌려준다(최초 조회 시 지연 생성).
    int balanceOf(String owner);

    // 참가 — 정원·중복·잔액·마감 검사와 변경들을 하나의 원자 구간에서 처리하고 갱신된 챌린지를
    // 돌려준다. planId는 완주 판정 근거 계획(호출자가 같은 조건의 CONFIRMED 계획을 찾아 넘긴다).
    // 마지막 자리를 채우는 참가가 started_at을 함께 기록한다 — "정원이 찬 순간 = 시작"이고,
    // 그 판정도 같은 원자 구간 안에 있다. 실패는 BusinessException으로 던지며 그때 저장소는
    // 변경되지 않는다:
    //   CHALLENGE_NOT_FOUND / CHALLENGE_ALREADY_JOINED / POINTS_INSUFFICIENT / CHALLENGE_FULL
    //   / CHALLENGE_CLOSED(정산 마감된 챌린지 — 미달 환불 마감 후 참가가 뚫리지 않게 가드)
    Challenge join(long challengeId, String owner, String joinedAt, long planId);

    // === 정산 (v0.25.0) ===

    // 정산권 청구 — "챌린지는 최대 한 번 정산된다" 불변식의 유일한 판정 지점.
    // true를 받은 호출자만 지급(recordPayout)을 진행한다. false = 이미 정산됐거나 그 사이 상태가
    // 바뀌었다(no-op으로 물러난다). requireStarted가 경로를 가른다:
    //   true  = 완주 정산(시작된 챌린지만) / false = 모집 미달 환불(시작 전 챌린지만)
    // 환불 경로의 "시작 전" 조건이 원자 구간 안에 있어야 하는 이유: 읽을 땐 미달이었는데 그 사이
    // 마지막 참가로 시작된 챌린지를 환불로 마감하면 "시작됐는데 환불"이 된다(TOCTOU).
    boolean claimSettlement(long challengeId, String settledAt, boolean requireStarted);

    // 정산 지급 — 지갑 잔액 증가 + 참가 행에 payout 기록. claimSettlement가 true를 돌려준
    // 정산 트랜잭션 안에서만 호출한다(JDBC는 중간 실패 시 claim째 롤백, 인메모리는 실패할 수
    // 없는 연산만으로 구성 — "검사를 변경 앞에" 규칙의 정산판).
    void recordPayout(long challengeId, String owner, int amount);

    // === 자동 생성 (v0.23.0) ===
    // 아래 둘은 계획 고정 트랜잭션 안에서 호출되므로 예외를 던지지 않는다 — 챌린지가 안 만들어지는
    // 것은 계획 고정을 실패시킬 이유가 아니다. 중복은 전부 no-op으로 흡수한다.

    // 조건 씨앗을 등록하고(같은 (조건, 소유자)는 멱등) 그 조건에 모인 서로 다른 소유자 수를 돌려준다.
    // 한 사람이 비슷한 계획을 여러 개 고정해도 1로 세야 하므로 카운트의 단위는 계획이 아니라 소유자다.
    int recordSeed(String conditionKey, String owner, String seededAt);

    // 같은 조건의 "모집 중"(participant_count < capacity, 미정산) 챌린지가 없을 때만 만든다.
    // 이미 있으면 아무 일도 하지 않는다. 동시 고정 두 건이 각자 "없네" 하고 만드는 TOCTOU를 막는
    // 것은 호출자의 사전 조회가 아니라 저장소의 원자 구간이다(JDBC = 부분 UNIQUE 인덱스, 인메모리 =
    // 맵 원자 구간). 미달인 채 환불 마감된 챌린지는 "모집 중"이 아니다 — 다음 챌린지가 열려야 한다.
    void createIfNoOpenCondition(Challenge challenge);
}
