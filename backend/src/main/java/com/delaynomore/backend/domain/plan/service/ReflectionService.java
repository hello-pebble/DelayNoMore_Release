package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.ReflectionResponse;
import com.delaynomore.backend.domain.plan.dto.ReflectionSaveRequest;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.Reflection;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReflectionService {

    private final PlanRepository planRepository;
    private final ReflectionRepository reflectionRepository;
    private final AuditEventService auditEventService;

    // 저장(업서트) — 날짜 검증 → 계획 존재 확인 → 완료/전체 개수 서버 재계산 → 원자적 업서트.
    // 완료 개수를 클라이언트가 보내지 않는 이유: 공유 데모 저장소라 조작 가능하고, plan.tasks가
    // 이미 서버에 있으므로 서버 계산이 항상 일관된 값을 만든다.
    public ReflectionResponse save(long planId, String date, ReflectionSaveRequest request,
                                   String owner, String sessionId) {
        LocalDate parsed = parseDate(date);
        if (!parsed.equals(KstDates.today())) {
            throw new BusinessException(ErrorCode.REFLECTION_DATE_NOT_TODAY);
        }
        Plan plan = requireOwnedPlan(planId, owner);

        // parsed.toString()으로 정규화된 날짜를 키로 쓴다 — "2026-7-1" 같은 비정규 표기가
        // 별도 키로 저장되는 것을 막는다(조회는 항상 YYYY-MM-DD로 온다).
        String normalizedDate = parsed.toString();
        Plan.TaskCounts counts = plan.countTasksOn(normalizedDate);
        String now = Instant.now().toString();

        Reflection saved = reflectionRepository.upsert(planId, normalizedDate, existing -> new Reflection(
                planId, normalizedDate, counts.completed(), counts.total(),
                request.difficulty(), request.reason(),
                existing == null ? now : existing.createdAt(), // 재저장 시 최초 저장 시각 보존
                now));
        auditEventService.recordReflectionSaved(planId, normalizedDate, counts.completed(), counts.total(), sessionId);
        return ReflectionResponse.from(saved);
    }

    public ReflectionResponse get(long planId, String date, String owner) {
        requireOwnedPlan(planId, owner);
        Reflection reflection = reflectionRepository.findByPlanIdAndDate(planId, parseDate(date).toString())
                .orElseThrow(() -> new BusinessException(ErrorCode.REFLECTION_NOT_FOUND));
        return ReflectionResponse.from(reflection);
    }

    public List<ReflectionResponse> getAll(long planId, String owner) {
        requireOwnedPlan(planId, owner);
        return reflectionRepository.findAllByPlanId(planId).stream()
                .map(ReflectionResponse::from)
                .toList();
    }

    // 회고는 자체 owner 없이 계획의 소유권을 상속한다 — 항상 planId를 거쳐 접근되므로 이 가드가
    // 유일한 진입로다. 남의 계획은 존재 자체를 숨긴다(404, PlanService.requireOwnedPlan과 동일 기준).
    private Plan requireOwnedPlan(long planId, String owner) {
        return planRepository.findById(planId)
                .filter(p -> owner.equals(p.owner()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    private LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.REFLECTION_DATE_INVALID);
        }
    }
}
