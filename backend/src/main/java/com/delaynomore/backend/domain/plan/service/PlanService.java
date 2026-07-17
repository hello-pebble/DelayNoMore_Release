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

    // synchronized: 한도 검사(count)와 저장(save)을 원자적으로 묶는다. 공유 데모 저장소라
    // 여러 방문자가 동시에 생성하면 각자 검사를 통과한 뒤 저장해 상한 50건을 넘길 수 있는데
    // (TOCTOU), 생성 경로를 직렬화해 이를 막는다. 생성만 개수를 늘리므로 create만 잠그면 충분하다.
    public synchronized PlanResponse create(PlanSaveRequest request) {
        if (planRepository.count() >= MAX_PLANS) {
            throw new BusinessException(ErrorCode.PLAN_LIMIT_EXCEEDED);
        }
        Plan saved = planRepository.save(request.toPlan(null, System.currentTimeMillis()));
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

    public PlanResponse update(long id, PlanSaveRequest request) {
        Plan updated = request.toPlan(id, System.currentTimeMillis());
        if (!planRepository.update(updated)) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        return PlanResponse.from(updated);
    }

    public void delete(long id) {
        if (!planRepository.deleteById(id)) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        // 캐스케이드 — 계획이 사라지면 회고도 조회 경로가 없어지므로 함께 지운다(고아 방지).
        reflectionRepository.deleteAllByPlanId(id);
    }
}
