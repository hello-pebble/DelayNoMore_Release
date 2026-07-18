package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlanService {

    // 공유 데모 저장소 폭주 방지 — 익명 방문자 전원이 함께 쓰는 휘발성 보관함이므로 개수 상한을 둔다.
    private static final int MAX_PLANS = 50;

    private final PlanRepository planRepository;
    private final ReflectionRepository reflectionRepository;
    private final AuditEventService auditEventService;

    // synchronized: 한도 검사(count)와 저장(save)을 원자적으로 묶는다. 공유 데모 저장소라
    // 여러 방문자가 동시에 생성하면 각자 검사를 통과한 뒤 저장해 상한 50건을 넘길 수 있는데
    // (TOCTOU), 생성 경로를 직렬화해 이를 막는다. 생성만 개수를 늘리므로 create만 잠그면 충분하다.
    public synchronized PlanResponse create(PlanSaveRequest request, String sessionId) {
        if (planRepository.count() >= MAX_PLANS) {
            throw new BusinessException(ErrorCode.PLAN_LIMIT_EXCEEDED);
        }
        Plan saved = planRepository.save(request.toPlan(null, System.currentTimeMillis()));
        auditEventService.recordPlanCreated(saved, sessionId);
        return PlanResponse.from(saved);
    }

    public List<PlanResponse> getPlans() {
        return planRepository.findAll().stream()
                .map(PlanResponse::from)
                .toList();
    }

    public PlanResponse getPlan(long id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
        return PlanResponse.from(plan);
    }

    public PlanResponse update(long id, PlanSaveRequest request, String sessionId) {
        Plan updated = request.toPlan(id, System.currentTimeMillis());
        Plan previous = planRepository.update(updated);
        if (previous == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        // 모든 변경(내용 수정·고정·완료 토글)이 이 PUT 하나로 들어오므로, 이전 상태와 diff해
        // 이벤트 종류(PLAN_CONFIRMED/TASK_*/PLAN_UPDATED)를 서버가 판별·기록한다.
        auditEventService.recordPlanUpdated(previous, updated, sessionId);
        return PlanResponse.from(updated);
    }

    public void delete(long id, String sessionId) {
        Plan deleted = planRepository.deleteById(id);
        if (deleted == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        // 캐스케이드 — 계획이 사라지면 회고도 조회 경로가 없어지므로 함께 지운다(고아 방지).
        // 변경 이력은 지우지 않는다 — "언제 삭제됐는가"에 답해야 하므로 PLAN_DELETED와 함께
        // 남기고, 메모리는 이력 저장소의 전역 상한이 관리한다.
        reflectionRepository.deleteAllByPlanId(id);
        auditEventService.recordPlanDeleted(deleted, sessionId);
    }
}
