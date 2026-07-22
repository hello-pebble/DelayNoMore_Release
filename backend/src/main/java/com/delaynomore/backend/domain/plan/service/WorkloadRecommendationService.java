package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.ai.dto.AiDraftRequest;
import com.delaynomore.backend.domain.ai.service.AiService;
import com.delaynomore.backend.domain.ai.service.RecommendationReasonWriter;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.dto.RecommendationConfirmRequest;
import com.delaynomore.backend.domain.plan.dto.RecommendationDraftResponse;
import com.delaynomore.backend.domain.plan.dto.RecommendationResponse;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.domain.plan.support.PlanDates;
import com.delaynomore.backend.domain.plan.support.TemplatePlanGenerator;
import com.delaynomore.backend.domain.plan.support.WorkloadRecommendation;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다음 계획 분량 추천 흐름의 오케스트레이터 — 계산·규칙·AI 호출·정확개수 검증·폴백·저장·Audit을
 * 서버가 소유한다("최대한 백엔드에서"). 3단계로 나뉜다:
 *   recommend  : 수행 기록 계산 + 규칙 분량 + 이유(AI/템플릿) + VIEWED 기록
 *   draft      : 선택 분량으로 초안 생성(AI → 실패 시 서버 템플릿), 저장·결정 기록 없음(미리보기)
 *   confirm    : 승인된 초안을 새 계획으로 저장(PlanService.create 재사용) + 결정·생성 Audit(원자)
 * 원본 계획·회고는 어느 단계에서도 변경하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class WorkloadRecommendationService {

    private final PlanRepository planRepository;
    private final ReflectionRepository reflectionRepository;
    private final AuditEventService auditEventService;
    private final AiService aiService;
    private final RecommendationReasonWriter reasonWriter;
    private final TemplatePlanGenerator templatePlanGenerator;
    private final PlanService planService;

    // 합산 표본 상한 — 같은 목표의 최근 계획을 최대 이만큼 모아 추천을 안정화한다.
    private static final int MAX_HISTORY_PLANS = 3;

    // (a) 수행 기록 계산 + 규칙 분량 + 이유 + VIEWED 기록. 같은 목표(goalName)의 최근 계획을 최대
    //     3건 합산해 표본을 키운다(계획 1건이면 v0.13.0과 동일 결과).
    @Transactional
    public RecommendationResponse recommend(long id, String owner, String sessionId) {
        Plan plan = requireOwnedPlan(id, owner);
        WorkloadRecommendation.Recommendation rec =
                WorkloadRecommendation.compute(collectHistory(plan, owner), KstDates.today());
        RecommendationReasonWriter.ReasonResult reason = reasonWriter.write(rec);
        auditEventService.recordRecommendationViewed(plan.id(), owner,
                rec.currentTasksPerDay(), rec.recommendedTasksPerDay(), sessionId);
        return RecommendationResponse.from(plan, rec, reason.text(), reason.aiUsed());
    }

    // 같은 목표의 최근 계획을 최대 3건 모은다 — 클릭한 계획을 맨 앞(현재 분량 기준)에 두고, 나머지는
    // findAllByOwner(savedAt DESC) 순서대로 같은 goalName만 채운다. 다른 목표·타 owner는 제외된다
    // (findAllByOwner가 owner로 이미 걸러 소유자 격리를 유지). 각 계획의 회고를 함께 실어 반환한다.
    private List<WorkloadRecommendation.PlanHistory> collectHistory(Plan clicked, String owner) {
        List<Plan> group = new ArrayList<>();
        group.add(clicked);
        for (Plan candidate : planRepository.findAllByOwner(owner)) {
            if (group.size() >= MAX_HISTORY_PLANS) {
                break;
            }
            if (!candidate.id().equals(clicked.id()) && clicked.goalName().equals(candidate.goalName())) {
                group.add(candidate);
            }
        }
        List<WorkloadRecommendation.PlanHistory> history = new ArrayList<>();
        for (Plan plan : group) {
            history.add(new WorkloadRecommendation.PlanHistory(plan,
                    reflectionRepository.findAllByPlanId(plan.id())));
        }
        return history;
    }

    // (b) 선택 분량으로 초안 생성 — 저장하지 않는다(승인 전 미저장). AI가 실패하면 서버 템플릿으로
    //     폴백해 항상 미리보기 가능한 초안을 돌려준다.
    @Transactional(readOnly = true)
    public RecommendationDraftResponse draft(long id, int selectedTasksPerDay, String owner) {
        Plan source = requireOwnedPlan(id, owner);
        Map<String, Object> tasks;
        boolean aiUsed;
        try {
            Object generated = aiService.createDraft(AiDraftRequest.fromSource(source, selectedTasksPerDay));
            tasks = toTaskObjects(asMap(generated));   // {날짜:[문자열]} → {날짜:[{id,content,completed}]}
            aiUsed = true;
        } catch (BusinessException e) {
            // AI_UPSTREAM_ERROR(키 미설정·업스트림 오류) 또는 AI_RESPONSE_INVALID(정확개수 불일치) —
            // 재시도·잘라내기 대신 서버 템플릿으로 폴백한다.
            tasks = templatePlanGenerator.generate(source, selectedTasksPerDay);
            aiUsed = false;
        }
        return RecommendationDraftResponse.from(source, selectedTasksPerDay, tasks, aiUsed);
    }

    // (c) 승인된 초안을 새 계획으로 저장 + 결정/생성 Audit(한 트랜잭션). 목표·수준은 원본에서 다시
    //     승계하고 날짜·기간은 tasks에서 서버가 산출한다. 원본 계획은 건드리지 않는다.
    @Transactional
    public PlanResponse confirm(long id, RecommendationConfirmRequest body, String owner, String sessionId) {
        Plan source = requireOwnedPlan(id, owner);
        String endDate = PlanDates.maxTaskKey(body.tasks());
        PlanSaveRequest request = new PlanSaveRequest(
                source.goalName(), source.duration(), source.dailyHours(), source.currentLevel(),
                body.tasks(), "DRAFT", null, null, endDate, Instant.now().toString());
        // 한도 검사·synchronized·PLAN_CREATED는 PlanService.create를 그대로 재사용(수정 없음).
        PlanResponse saved = planService.create(request, owner, sessionId);
        auditEventService.recordRecommendationDecision(saved.id(), owner,
                body.selectedTasksPerDay(), body.recommendedTasksPerDay(), body.accepted(), sessionId);
        auditEventService.recordPlanCreatedFromRecommendation(saved.id(), owner, source.id(),
                body.selectedTasksPerDay(), sessionId);
        return saved;
    }

    // PlanService.requireOwnedPlan과 동일 기준 — 남의 계획은 존재를 숨긴다(404).
    private Plan requireOwnedPlan(long id, String owner) {
        return planRepository.findById(id)
                .filter(p -> owner.equals(p.owner()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    // AI 초안의 {날짜:[문자열]}을 계획 저장 형식 {날짜:[{id,content,completed}]}로 승격한다
    // (ChatPatchMerger.liftItems와 같은 흡수 규칙 — 문자열/‌{content} 객체 모두 content만 취한다).
    private static Map<String, Object> toTaskObjects(Map<String, Object> byDate) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : byDate.entrySet()) {
            List<Map<String, Object>> items = new ArrayList<>();
            if (entry.getValue() instanceof List<?> list) {
                int index = 0;
                for (Object task : list) {
                    String content = null;
                    if (task instanceof String s) {
                        content = s;
                    } else if (task instanceof Map<?, ?> m && m.get("content") instanceof String s) {
                        content = s;
                    }
                    if (content != null && !content.isBlank()) {
                        items.add(Map.of("id", "t-" + entry.getKey() + "-" + index,
                                "content", content.trim(), "completed", false));
                        index++;
                    }
                }
            }
            out.put(entry.getKey(), items);
        }
        return out;
    }
}
