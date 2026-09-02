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
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

// Goal Challenge — 챌린지 자동 생성·참가·정산.
// 이 서비스에는 정원·잔액·중복·이중 정산에 대한 검사 코드가 없다. 전부 저장소의 원자 구간 안에
// 있어야 하기 때문이다 — 여기서 "자리가 남았나?"를 먼저 확인하고 저장소를 호출하면, 확인과 참가
// 사이가 열려 동시 요청에서 정원을 넘긴다(고전적 TOCTOU). 근거는 docs/CONCURRENCY.md.
// 자동 생성의 "이미 있나?"도, 정산의 "이미 정산했나?"도 같은 이유로 여기서 확인하지 않는다.
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
    // 모집 미달 챌린지의 수명 — 생성 후 기간의 2배가 지나도 정원이 안 차면 전원 환불 후 마감한다.
    // 참가비가 영원히 잠기지 않게 하는 안전판이고, 2배는 "그 기간의 계획이 한 바퀴 돌고도 남을
    // 시간"이라는 감각적 여유값이다.
    static final int RECRUIT_EXPIRY_MULTIPLIER = 2;

    private final ChallengeRepository challengeRepository;
    // 서비스가 아니라 저장소를 주입한다 — PlanService → ChallengeService(onPlanConfirmed) 방향이
    // 이미 있어, PlanService를 물면 순환이 된다. 여기서 필요한 건 조회뿐이라 저장소로 충분하다.
    private final PlanRepository planRepository;

    public ChallengeListResponse list(String viewer) {
        Map<Long, ChallengeParticipant> mine = challengeRepository.findMyParticipations(viewer);
        List<ChallengeResponse> challenges = challengeRepository.findAll().stream()
                .map(c -> ChallengeResponse.from(c, mine.get(c.id())))
                .toList();
        return new ChallengeListResponse(challengeRepository.balanceOf(viewer), challenges);
    }

    // 참가 — 검사·차감·예약·등록을 저장소가 한 덩어리로 처리한다. @Transactional은 JDBC 구현이
    // 조건부 UPDATE들을 하나의 원자 단위로 묶고, 실패 시(CHALLENGE_FULL) 앞선 차감·등록을
    // 롤백하기 위해 필요하다. 인메모리 구현은 맵의 키 단위 원자 구간이 같은 계약을 제공한다.
    //
    // 참가 자격(v0.25.0): 같은 조건의 CONFIRMED 계획이 있어야 한다 — 완주 판정의 근거가 될
    // 계획을 서버가 찾아 참가 레코드에 연결한다(같은 조건이 여럿이면 최신 저장분 —
    // findAllByOwner가 savedAt 내림차순이다). 조회와 join 사이에 계획이 삭제되는 좁은 창은
    // 수용한다: plan_id가 끊겨(SET NULL) 정산에서 패배로 수렴할 뿐 돈이 새지 않는다.
    @Transactional
    public JoinResponse join(long challengeId, String owner) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        Plan plan = planRepository.findAllByOwner(owner).stream()
                .filter(p -> p.isConfirmed() && challenge.conditionKey() != null
                        && challenge.conditionKey().equals(p.conditionKey()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_PLAN_REQUIRED));

        Challenge joined = challengeRepository.join(challengeId, owner, Instant.now().toString(), plan.id());
        return new JoinResponse(
                ChallengeResponse.from(joined, challengeRepository.findMyParticipations(owner).get(challengeId)),
                challengeRepository.balanceOf(owner));
    }

    /**
     * 만기 챌린지 lazy 정산 — 목록 조회 진입 시 컨트롤러가 호출한다(이 저장소에는 스케줄러를
     * 두지 않는다). 두 경로가 있다:
     *   진행 중 + 기간 경과   → 완주 정산: 연결 계획을 100% 완주한 참가자끼리 참가비 풀 균등 분배,
     *                          완주자가 없으면 전원 환불
     *   모집 중 + 수명 경과   → 미달 환불: 전원 환불 후 마감(같은 조건의 다음 챌린지가 열릴 수 있게)
     *
     * 이중 정산 방지는 여기가 아니라 claimSettlement의 원자 구간이 한다 — 동시에 여러 조회가
     * 같은 챌린지를 정산하려 해도 정확히 하나만 정산권을 얻고 나머지는 no-op으로 물러난다.
     *
     * [실패를 삼키지 않는다] onPlanConfirmed와 달리 여기서 나는 예외는 전부 버그다(저장소 계약이
     * 경합을 no-op으로 흡수하므로). 감싸면 지급 실패를 조용히 잃는다 — JDBC에서는 예외가 정산
     * 트랜잭션을 통째로 롤백해 claim째 되돌린다(부분 지급 없음).
     */
    @Transactional
    public void settleDue() {
        Instant now = Instant.now();
        String nowText = now.toString();
        // ponytail: 전체 스캔 — 데모 규모(수십 건)라 만기 후보 인덱스를 두지 않는다. 느려지면
        // "미정산 + 만기 도달" 조회를 저장소로 내린다.
        for (Challenge challenge : challengeRepository.findAll()) {
            if (challenge.settled()) {
                continue;
            }
            if (challenge.started() && !now.isBefore(Instant.parse(challenge.endsAt()))) {
                settleFinished(challenge, nowText);
            } else if (!challenge.started() && !now.isBefore(recruitExpiry(challenge))) {
                settleExpiredRecruitment(challenge, nowText);
            }
        }
    }

    private static Instant recruitExpiry(Challenge challenge) {
        return Instant.parse(challenge.createdAt())
                .plus(Duration.ofDays((long) challenge.durationDays() * RECRUIT_EXPIRY_MULTIPLIER));
    }

    // 완주 정산 — 승리 = 연결 계획이 존재하고(삭제 안 됨) 할 일이 있으며 전부 완료.
    // 지난 날짜 소급 체크는 PAST_TASK_LOCKED가 이미 막고 있어 완료율이 정산 근거로 쓸 만하다.
    private void settleFinished(Challenge challenge, String now) {
        if (!challengeRepository.claimSettlement(challenge.id(), now, true)) {
            return; // 다른 조회가 먼저 정산권을 땄다 — 물러난다.
        }
        List<ChallengeParticipant> participants = challengeRepository.findParticipants(challenge.id());
        List<ChallengeParticipant> winners = participants.stream()
                .filter(p -> p.planId() != null && planRepository.findById(p.planId())
                        .map(plan -> {
                            Plan.TaskCounts counts = plan.countAllTasks();
                            return counts.total() > 0 && counts.completed() == counts.total();
                        })
                        .orElse(false)) // 계획 삭제 = 완주 증명 소실 = 패배
                .toList();

        if (winners.isEmpty()) {
            // 완주자가 없으면 풀을 삼키지 않고 전원 환불한다 — 데모 포인트라도 "다 잃는 판"은
            // 참가 유인을 꺾는다.
            participants.forEach(p -> challengeRepository.recordPayout(challenge.id(), p.owner(), challenge.entryFee()));
            return;
        }
        int pool = challenge.entryFee() * participants.size();
        int share = pool / winners.size();
        // ponytail: 정수 나눗셈 나머지(pool % winners, 최대 승리자수-1 포인트)는 소멸한다.
        // 분배 규칙의 소유권은 서버고 데모 비금전 포인트라 수용 — 아까워지면 "가장 먼저 참가한
        // 완주자에게 몰아주기"로 바꾼다.
        for (ChallengeParticipant participant : participants) {
            boolean won = winners.stream().anyMatch(w -> w.owner().equals(participant.owner()));
            challengeRepository.recordPayout(challenge.id(), participant.owner(), won ? share : 0);
        }
    }

    // 미달 환불 — 시작 전 조건(claim의 requireStarted=false)이 원자 구간에 있어, 그 사이 마지막
    // 참가로 시작된 챌린지를 잘못 마감하는 일이 없다(claim이 false를 돌려주고 완주 경로가 맡는다).
    private void settleExpiredRecruitment(Challenge challenge, String now) {
        if (!challengeRepository.claimSettlement(challenge.id(), now, false)) {
            return;
        }
        challengeRepository.findParticipants(challenge.id())
                .forEach(p -> challengeRepository.recordPayout(challenge.id(), p.owner(), challenge.entryFee()));
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
