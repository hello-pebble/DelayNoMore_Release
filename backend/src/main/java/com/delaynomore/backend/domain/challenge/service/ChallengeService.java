package com.delaynomore.backend.domain.challenge.service;

import com.delaynomore.backend.domain.challenge.dto.ChallengeListResponse;
import com.delaynomore.backend.domain.challenge.dto.ChallengeParticipantResponse;
import com.delaynomore.backend.domain.challenge.dto.ChallengeResponse;
import com.delaynomore.backend.domain.challenge.dto.JoinResponse;
import com.delaynomore.backend.domain.challenge.entity.Challenge;
import com.delaynomore.backend.domain.challenge.entity.ChallengeParticipant;
import com.delaynomore.backend.domain.challenge.repository.ChallengeRepository;
import com.delaynomore.backend.domain.challenge.support.ChallengeCondition;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Goal Challenge — 챌린지 자동 생성·참가·정산.
// 이 서비스에는 정원·잔액·중복·이중 정산에 대한 검사 코드가 없다. 전부 저장소의 원자 구간 안에
// 있어야 하기 때문이다 — 여기서 "자리가 남았나?"를 먼저 확인하고 저장소를 호출하면, 확인과 참가
// 사이가 열려 동시 요청에서 정원을 넘긴다(고전적 TOCTOU). 근거는 docs/CONCURRENCY.md.
// 자동 생성의 "이미 있나?"도, 정산의 "이미 정산했나?"도 같은 이유로 여기서 확인하지 않는다.
@Service
@RequiredArgsConstructor
@Slf4j
public class ChallengeService {

    // 챌린지가 열리는 최소 인원 — 서로 다른 소유자 기준. 2명은 챌린지라기보다 약속이고,
    // 너무 크면 영영 안 열린다. 셋이 모이면 연다.
    private static final int SEED_THRESHOLD = 3;
    // 자동 생성 챌린지의 고정 조건. 개설자가 없으므로 사용자가 정할 값이 아니다.
    private static final int AUTO_CAPACITY = 5;
    private static final int AUTO_ENTRY_FEE = 100;
    // 개설자 자리에 들어가는 값 — 자동 생성 챌린지에는 주인이 없다는 표시다.
    private static final String AUTO_OWNER = "system";
    // 모집 미달 챌린지의 수명 — 생성 후 기간의 2배가 지나도 정원이 안 차면 전원 환불 후 마감한다.
    // 참가비가 영원히 잠기지 않게 하는 안전판이고, 2배는 "그 기간의 계획이 한 바퀴 돌고도 남을
    // 시간"이라는 감각적 여유값이다.
    static final int RECRUIT_EXPIRY_MULTIPLIER = 2;

    private final ChallengeRepository challengeRepository;
    // 서비스가 아니라 저장소를 주입한다 — PlanService → ChallengeService(onPlanConfirmed) 방향이
    // 이미 있어, PlanService를 물면 순환이 된다. 여기서 필요한 건 조회뿐이라 저장소로 충분하다.
    private final PlanRepository planRepository;

    // 챌린지 한 건의 정산을 **독립 트랜잭션**으로 여는 도구(v0.34.0). 자기 호출은 프록시를 거치지 않아
    // @Transactional로는 건별 트랜잭션을 만들 수 없고, 한 트랜잭션에서 여러 챌린지를 처리하면 첫 챌린지의
    // 지갑 잠금을 쥔 채 다음 챌린지를 잠그게 되어 잠금 순서가 뒤집힌다(CONCURRENCY.md 12장).
    // postgres 프로필에서는 반드시 주입돼야 한다 — SettlementTransactionGuard가 기동 단계에서 확인한다.
    // null(= 트랜잭션 없이 직접 실행)은 **인메모리 단위 테스트 전용**이다. JDBC에서 그렇게 돌리면 지급이
    // 실패해도 정산권 선점(claim)이 롤백되지 않는다.
    private TransactionOperations settlementTx;

    @Autowired(required = false)
    void setSettlementTx(@Qualifier("challengeSettlementTx") TransactionOperations settlementTx) {
        this.settlementTx = settlementTx;
    }

    // public이어야 한다 — CGLIB 프록시에서 패키지 전용 메서드는 대상 객체로 위임되지 않아(프록시 인스턴스의
    // 빈 필드를 읽는다) 항상 false가 된다. 가드가 프록시를 통해 부르므로 public으로 연다.
    public boolean hasSettlementTransaction() {
        return settlementTx != null;
    }

    // @Transactional인 이유는 읽기 때문이 아니라 쓰기 때문이다: 지갑이 없는 사람의 첫 조회가
    // 지갑을 만들고 발행을 기표한다(balanceOf). 그 둘은 한 단위여야 한다 — 갈라지면 "지갑은
    // 생겼는데 원장에 없는" 잔액이 만들어지고, 원장 검증이 영구히 어긋난다.
    @Transactional
    public ChallengeListResponse list(String viewer) {
        Map<Long, ChallengeParticipant> mine = challengeRepository.findMyParticipations(viewer);
        Set<Long> held = challengeRepository.findHeldChallengeIds();
        List<ChallengeResponse> challenges = challengeRepository.findAll().stream()
                .map(c -> toResponse(c, mine.get(c.id()), held.contains(c.id())))
                .toList();
        return new ChallengeListResponse(challengeRepository.balanceOf(viewer), challenges);
    }

    // 참가 — 검사·차감·예약·등록을 저장소가 한 덩어리로 처리한다. @Transactional은 JDBC 구현이
    // 조건부 UPDATE들을 하나의 원자 단위로 묶고, 실패 시(CHALLENGE_FULL) 앞선 차감·등록을
    // 롤백하기 위해 필요하다. 인메모리 구현은 맵의 키 단위 원자 구간이 같은 계약을 제공한다.
    //
    // 참가 자격(v0.25.0): 같은 조건의 CONFIRMED 계획이 있어야 한다 — 완주 판정의 근거가 될
    // 계획을 서버가 찾아 참가 레코드에 연결한다(같은 조건이 여럿이면 최신 저장분 —
    // findAllByOwner가 savedAt 내림차순이다).
    //
    // [목표 행을 잠근 채 참가한다 (v0.34.0)] 목표를 먼저 읽고 나중에 등록하면 ① 100%를 읽음 → ② 다른
    // 탭에서 체크 해제 → ③ 낡은 100%로 완주 기록이 남는 틈이 생긴다. 그래서 항등 mutate로 그 목표의
    // 행 잠금을 잡고, 그 안에서 자격 재검증 → 참가 → **방금 읽은 최신 상태로 그 참가 한 건만** 완주
    // 기록을 시드한다. 같은 목표를 바꾸는 쪽(PlanService)은 이 잠금 뒤에 줄을 선다. 전체 참가 기록 갱신
    // (markCompletion)을 여기서 하지 않는 이유는 저장소 주석(잠금 순서) 참고.
    @Transactional
    public JoinResponse join(long challengeId, String owner) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        Plan candidate = planRepository.findAllByOwner(owner).stream()
                .filter(p -> isEligible(p, owner, challenge))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_PLAN_REQUIRED));

        Challenge[] joined = new Challenge[1];
        Plan locked = planRepository.mutate(candidate.id(), current -> {
            if (!isEligible(current, owner, challenge)) { // 잠금 안에서 최신 상태로 다시 확인한다
                throw new BusinessException(ErrorCode.CHALLENGE_PLAN_REQUIRED);
            }
            joined[0] = challengeRepository.join(challengeId, owner, Instant.now().toString(), current.id());
            challengeRepository.seedCompletion(challengeId, owner, current.allTasksDone());
            return current;
        });
        if (locked == null) { // 조회와 잠금 사이에 목표가 삭제됐다
            throw new BusinessException(ErrorCode.CHALLENGE_PLAN_REQUIRED);
        }
        return new JoinResponse(
                toResponse(joined[0], challengeRepository.findMyParticipations(owner).get(challengeId),
                        challengeRepository.findHeldChallengeIds().contains(challengeId)),
                challengeRepository.balanceOf(owner));
    }

    private static boolean isEligible(Plan plan, String owner, Challenge challenge) {
        return owner.equals(plan.owner()) && plan.isConfirmed() && challenge.conditionKey() != null
                && challenge.conditionKey().equals(plan.conditionKey());
    }

    // 목표의 할 일이 바뀔 때마다 PlanService가 **목표 행 잠금 안에서** 부른다 — 이 목표가 걸린 챌린지들의
    // 완주 기록(마감 전까지만 갱신, 이후 동결)을 최신 상태로 맞춘다. 삭제는 allDone=false로 부른다.
    public void onPlanProgress(long planId, boolean allDone) {
        challengeRepository.markCompletion(planId, allDone);
    }

    /**
     * 만기 챌린지 정산 — 스케줄러(무접속 만기 정산)와 목록 조회 진입(lazy)이 함께 부른다(v0.34.0).
     * 두 경로가 있다:
     *   진행 중 + 기간 경과   → 완주 정산: 마감 전까지 100% 기록(completed_at)이 있는 참가자끼리 참가비
     *                          풀(참가비 × 참가 인원)을 1/n 균등 분배. 나머지와, 완주자가 없을 때의 풀
     *                          전액은 소각 계정으로 옮겨 소멸한다(예치 계정이 0으로 닫힌다).
     *   모집 중 + 수명 경과   → 미달 환불: 전원 환불 후 마감(기간이 없으므로 소멸 대상이 아니다)
     *
     * [챌린지 한 건 = 한 트랜잭션] 이 메서드 자체는 트랜잭션이 없다. 챌린지마다 독립 트랜잭션(REQUIRES_NEW)에서
     * 정산권 선점·지급·소멸 기표가 함께 커밋/롤백된다. 한 트랜잭션에 여러 챌린지를 묶으면 첫 챌린지의 지갑
     * 잠금을 쥔 채 다음 챌린지를 잠가 잠금 순서가 뒤집힌다. 한 건이 실패해도 나머지는 계속 정산하고, 첫
     * 예외는 마지막에 다시 던진다(삼키지 않는다 — 실패 건은 롤백돼 다음 턴에 재시도된다).
     *
     * 이중 정산 방지는 여기가 아니라 claimSettlement의 원자 구간이 한다 — 스케줄러와 lazy가 겹쳐 돌아도
     * 정확히 하나만 정산권을 얻고 나머지는 no-op으로 물러난다. 정산 보류(settlement_hold) 건은 건너뛴다.
     */
    public void settleDue() {
        Instant now = Instant.now();
        Set<Long> held = challengeRepository.findHeldChallengeIds();
        RuntimeException firstFailure = null;
        // ponytail: 전체 스캔 — 데모 규모(수십 건)라 만기 후보 인덱스를 두지 않는다. 느려지면
        // "미정산 + 만기 도달" 조회를 저장소로 내린다.
        for (Challenge challenge : challengeRepository.findAll()) {
            if (challenge.settled() || held.contains(challenge.id()) || !isDue(challenge, now)) {
                continue;
            }
            try {
                inSettlementTransaction(() -> settleOne(challenge.id()));
            } catch (RuntimeException e) {
                log.error("challenge settlement failed: id={}", challenge.id(), e);
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void inSettlementTransaction(Runnable work) {
        if (settlementTx == null) {
            work.run(); // 인메모리 단위 테스트 전용 경로 — postgres에서는 기동 시 SettlementTransactionGuard가 막는다
            return;
        }
        settlementTx.executeWithoutResult((TransactionStatus status) -> work.run());
    }

    private boolean isDue(Challenge challenge, Instant now) {
        return challenge.started()
                ? !now.isBefore(Instant.parse(challenge.endsAt()))
                : !now.isBefore(recruitExpiry(challenge));
    }

    // 트랜잭션 안에서 최신 상태를 다시 읽고(스캔 이후 바뀌었을 수 있다) 경로를 고른다.
    private void settleOne(long challengeId) {
        Challenge challenge = challengeRepository.findById(challengeId).orElse(null);
        Instant now = Instant.now();
        if (challenge == null || challenge.settled() || !isDue(challenge, now)) {
            return;
        }
        if (challenge.started()) {
            settleFinished(challenge, now.toString());
        } else {
            settleExpiredRecruitment(challenge, now.toString());
        }
    }

    private static Instant recruitExpiry(Challenge challenge) {
        return Instant.parse(challenge.createdAt())
                .plus(Duration.ofDays((long) challenge.durationDays() * RECRUIT_EXPIRY_MULTIPLIER));
    }

    // 완주 정산 — 승리 = 마감 전까지 100% 완료 기록(completed_at)이 있는 참가자. 정산 시점의 계획을 다시
    // 읽지 않는다: 마감 후 체크는 기록에 반영되지 않고(저장소가 마감·정산 이후 변경을 무시), 서버가 중단됐다
    // 재기동해도 판정 근거는 마감 시각의 기록이다. 계획이 마감 후에 삭제돼도 기록은 보존된다.
    // 참가자는 owner 오름차순으로 온다 — 서로 다른 챌린지의 동시 정산이 지갑을 같은 순서로 잡게 한다.
    private void settleFinished(Challenge challenge, String now) {
        if (!challengeRepository.claimSettlement(challenge.id(), now, true)) {
            return; // 다른 호출이 먼저 정산권을 땄다(또는 보류 중이다) — 물러난다.
        }
        List<ChallengeParticipant> participants = challengeRepository.findParticipants(challenge.id());
        int winners = (int) participants.stream().filter(p -> p.completedAt() != null).count();
        int pool = challenge.entryFee() * participants.size();
        int share = winners == 0 ? 0 : pool / winners;
        for (ChallengeParticipant participant : participants) {
            challengeRepository.recordPayout(challenge.id(), participant.owner(),
                    participant.completedAt() != null ? share : 0, PointTxKind.CHALLENGE_PAYOUT);
        }
        // 소멸 = 풀에서 지급하고 남은 몫(균등 분배 나머지, 완주자가 없으면 풀 전액). 0이면 기표하지 않는다.
        challengeRepository.recordForfeit(challenge.id(), pool - share * winners);
    }

    // 미달 환불 — 시작 전 조건(claim의 requireStarted=false)이 원자 구간에 있어, 그 사이 마지막
    // 참가로 시작된 챌린지를 잘못 마감하는 일이 없다(claim이 false를 돌려주고 완주 경로가 맡는다).
    private void settleExpiredRecruitment(Challenge challenge, String now) {
        if (!challengeRepository.claimSettlement(challenge.id(), now, false)) {
            return;
        }
        challengeRepository.findParticipants(challenge.id())
                .forEach(p -> challengeRepository.recordPayout(
                        challenge.id(), p.owner(), challenge.entryFee(), PointTxKind.CHALLENGE_REFUND));
    }

    // 응답 변환의 단일 지점 — list·join이 공유한다. 보류 건은 상태를 REVIEWING으로 덮어 "정산이 멈췄다"가
    // 아니라 "정산 확인 중"으로 보이게 한다. 참가자에게는 참가에 쓴 목표(myPlan)를 함께 내려준다.
    private ChallengeResponse toResponse(Challenge challenge, ChallengeParticipant mine, boolean held) {
        return ChallengeResponse.from(challenge, mine, held, mine == null ? null : myPlanOf(mine));
    }

    private ChallengeResponse.MyPlan myPlanOf(ChallengeParticipant mine) {
        boolean completed = mine.completedAt() != null;
        Optional<Plan> plan = mine.planId() == null ? Optional.empty() : planRepository.findById(mine.planId());
        return plan.map(p -> {
            Plan.TaskCounts counts = p.countAllTasks();
            int rate = counts.total() == 0 ? 0 : Math.round(counts.completed() * 100f / counts.total());
            return new ChallengeResponse.MyPlan(p.goalName(), p.statusOrDraft().name(), rate, false, completed);
        }).orElseGet(() -> new ChallengeResponse.MyPlan(null, null, 0, true, completed));
    }

    /**
     * 참가자 현황(리더보드) — 완료율 내림차순, 배열 순서가 곧 순위다. 완료율의 계산 소유권은
     * 서버(Plan.countAllTasks)이고, 소급 조작은 PAST_TASK_LOCKED가 막고 있어 순위 근거로 쓸 만하다.
     * 연결 계획이 삭제된 참가자는 완주 증명이 없으므로 0%로 선다(정산의 패배 판정과 같은 규칙).
     * 익명 응답인 이유는 DTO 주석 참고 — viewer는 me 플래그를 채우는 데만 쓴다.
     */
    public List<ChallengeParticipantResponse> participants(long challengeId, String viewer) {
        challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        return challengeRepository.findParticipants(challengeId).stream()
                .map(p -> {
                    Plan.TaskCounts counts = p.planId() == null ? new Plan.TaskCounts(0, 0)
                            : planRepository.findById(p.planId())
                                    .map(Plan::countAllTasks)
                                    .orElse(new Plan.TaskCounts(0, 0));
                    int rate = counts.total() == 0 ? 0
                            : Math.round(counts.completed() * 100f / counts.total());
                    return new ChallengeParticipantResponse(
                            rate, counts.completed(), counts.total(), p.owner().equals(viewer), p.payout());
                })
                .sorted(Comparator.comparingInt(ChallengeParticipantResponse::ratePercent).reversed())
                .toList();
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
                    AUTO_CAPACITY, AUTO_ENTRY_FEE, 0, now, condition.key(), null, null));
        });
    }
}
