package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
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

    public PlanResponse create(PlanSaveRequest request) {
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
    }
}
