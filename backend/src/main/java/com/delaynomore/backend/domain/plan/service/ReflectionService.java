package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.ReflectionResponse;
import com.delaynomore.backend.domain.plan.dto.ReflectionSaveRequest;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.Reflection;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReflectionService {

    // "오늘" 판정 기준 시간대 — 컨테이너 JVM은 UTC라 서버 로컬 날짜를 쓰면 한국 사용자의
    // 자정~오전 9시 회고가 어제 날짜로 거부된다. 회고는 한국 시간 기준 서비스로 명시한다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PlanRepository planRepository;
    private final ReflectionRepository reflectionRepository;

    // 저장(업서트) — 날짜 검증 → 계획 존재 확인 → 완료/전체 개수 서버 재계산 → 원자적 업서트.
    // 완료 개수를 클라이언트가 보내지 않는 이유: 공유 데모 저장소라 조작 가능하고, plan.tasks가
    // 이미 서버에 있으므로 서버 계산이 항상 일관된 값을 만든다.
    public ReflectionResponse save(long planId, String date, ReflectionSaveRequest request) {
        LocalDate parsed = parseDate(date);
        if (!parsed.equals(LocalDate.now(KST))) {
            throw new BusinessException(ErrorCode.REFLECTION_DATE_NOT_TODAY);
        }
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));

        // parsed.toString()으로 정규화된 날짜를 키로 쓴다 — "2026-7-1" 같은 비정규 표기가
        // 별도 키로 저장되는 것을 막는다(조회는 항상 YYYY-MM-DD로 온다).
        String normalizedDate = parsed.toString();
        int[] counts = countTodayTasks(plan.tasks(), normalizedDate);
        String now = Instant.now().toString();

        Reflection saved = reflectionRepository.upsert(planId, normalizedDate, existing -> new Reflection(
                planId, normalizedDate, counts[0], counts[1],
                request.difficulty(), request.reason(),
                existing == null ? now : existing.createdAt(), // 재저장 시 최초 저장 시각 보존
                now));
        return ReflectionResponse.from(saved);
    }

    public ReflectionResponse get(long planId, String date) {
        requirePlan(planId);
        Reflection reflection = reflectionRepository.findByPlanIdAndDate(planId, parseDate(date).toString())
                .orElseThrow(() -> new BusinessException(ErrorCode.REFLECTION_NOT_FOUND));
        return ReflectionResponse.from(reflection);
    }

    public List<ReflectionResponse> getAll(long planId) {
        requirePlan(planId);
        return reflectionRepository.findAllByPlanId(planId).stream()
                .map(ReflectionResponse::from)
                .toList();
    }

    private void requirePlan(long planId) {
        if (planRepository.findById(planId).isEmpty()) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
    }

    private LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.REFLECTION_DATE_INVALID);
        }
    }

    // {완료, 전체} 개수 — tasks 내부 구조는 프론트 원본 그대로라 서버가 방어적으로 센다:
    // 해당 날짜 값이 List가 아니면 빈 것으로(0/0 허용 — 오늘 할 일 없는 날의 회고도 유효),
    // 항목은 Map이면서 completed == Boolean.TRUE인 것만 완료로 센다.
    private int[] countTodayTasks(Map<String, Object> tasks, String date) {
        Object dayTasks = tasks == null ? null : tasks.get(date);
        if (!(dayTasks instanceof List<?> list)) {
            return new int[]{0, 0};
        }
        int completed = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> task && Boolean.TRUE.equals(task.get("completed"))) {
                completed++;
            }
        }
        return new int[]{completed, list.size()};
    }
}
