package com.delaynomore.backend.domain.points.service;

import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.points.entity.PointAccounts;
import com.delaynomore.backend.domain.points.entity.PointTransfer;
import com.delaynomore.backend.domain.points.entity.PointTxKind;
import com.delaynomore.backend.domain.points.repository.PointWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 계획 완주 보상(v0.32.0) — 포인트 경제에 <b>버는 경로</b>를 연다.
 *
 * <p>v0.31.0까지 포인트의 유입은 신규 지급 1,000P 한 번뿐이었고 나머지는 참가자 사이의
 * 재분배(제로섬)였다. 쓰기만 하고 벌 수 없는 경제는 결국 고갈된다.
 *
 * <p><b>왜 완주인가.</b> 트리거가 명확한 1회성 사건이고(COMPLETED 전이), 완주 판정은 이미
 * 서버가 소유한 계산이다(Plan.countAllTasks — 챌린지 정산과 같은 규칙). 연속 달성(스트릭)은
 * 일자별 상태를 새로 저장해야 해서 이번 범위 밖으로 뒀다.
 *
 * <p><b>왜 파밍되지 않는가.</b> 멱등 키가 {@code earn:plan:<id>}라 한 계획은 아무리 다시
 * 종결시켜도 한 번만 보상되고(전이표상 COMPLETED는 종결이라 애초에 재전이가 불가하지만,
 * 키가 그것에 기대지 않는다), 계획을 새로 만드는 쪽은 <b>기존 생성 한도(하루 5회, v0.20.0)</b>가
 * 상한이 된다 — 새 한도를 만들지 않고 이미 있는 규칙이 자연히 천장 역할을 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanRewardService {

    /** 기간 하루당 보상. 기간이 길수록 지키기 어려우므로 보상도 비례한다. */
    static final int PER_DAY = 10;
    /** 하한·상한 — 1일짜리도 의미 있게, 365일짜리도 한 방에 경제를 흔들지 않게. */
    static final int MIN_REWARD = 50;
    static final int MAX_REWARD = 200;

    private final PointWalletRepository wallets;

    /**
     * 완주 보상 규칙 — 서버가 소유하는 순수 함수. 100% 완료가 아니면 0이다.
     *
     * <p>할 일이 하나도 없는 계획은 "완주"라 부를 수 없으므로 0이다(예치의 환급 규칙이 같은
     * 경우를 전액 환급으로 보는 것과 방향이 반대인데, 의도적이다 — 돌려주는 것과 새로 주는 것의
     * 기준은 달라야 한다. 내 돈을 돌려받는 데는 근거가 필요 없지만, 새 포인트를 발행하는 데는
     * 필요하다).
     */
    public static int rewardFor(Plan plan) {
        Plan.TaskCounts counts = plan.countAllTasks();
        if (counts.total() == 0 || counts.completed() != counts.total()) {
            return 0;
        }
        int duration = plan.duration() == null ? 1 : plan.duration();
        return Math.clamp((long) duration * PER_DAY, MIN_REWARD, MAX_REWARD);
    }

    /**
     * 완주 보상 지급 — PlanService의 complete 전이 훅에서 호출된다(같은 트랜잭션).
     * 100% 완료가 아니면 아무 일도 하지 않는다.
     */
    public void rewardIfCompleted(Plan plan) {
        int reward = rewardFor(plan);
        if (reward == 0) {
            return;
        }
        wallets.credit(plan.owner(), reward, new PointTransfer("earn:plan:" + plan.id(),
                PointAccounts.ISSUANCE, plan.owner(), reward,
                PointTxKind.PLAN_COMPLETION_REWARD, "plan", String.valueOf(plan.id()),
                Instant.now().toString()));
        log.info("points.reward plan={} owner={} amount={}", plan.id(), plan.owner(), reward);
    }
}
