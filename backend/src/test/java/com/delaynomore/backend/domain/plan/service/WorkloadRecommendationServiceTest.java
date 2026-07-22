package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.ai.service.AiService;
import com.delaynomore.backend.domain.ai.service.RecommendationReasonWriter;
import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.RecommendationConfirmRequest;
import com.delaynomore.backend.domain.plan.dto.RecommendationDraftResponse;
import com.delaynomore.backend.domain.plan.dto.RecommendationResponse;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.InMemoryAuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryReflectionRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.domain.plan.support.TemplatePlanGenerator;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 추천 오케스트레이터 — 인메모리 저장소 + AI 목으로 recommend/draft/confirm 3단계, 소유권 404,
// Audit 기록, 원본 불변, AI 실패 시 서버 템플릿 폴백을 Docker 없이 검증한다.
class WorkloadRecommendationServiceTest {

    // 관찰일수·완료율이 실행 시각(KstDates.today)에 흔들리지 않도록 충분히 과거 날짜를 쓴다.
    private static final String START = "2020-01-01";

    private PlanRepository planRepository;
    private ReflectionRepository reflectionRepository;
    private AuditEventService auditEventService;
    private AiService aiService;
    private RecommendationReasonWriter reasonWriter;
    private WorkloadRecommendationService service;

    @BeforeEach
    void setUp() {
        planRepository = new InMemoryPlanRepository();
        reflectionRepository = new InMemoryReflectionRepository();
        auditEventService = new AuditEventService(new InMemoryAuditEventRepository());
        PlanService planService = new PlanService(planRepository, reflectionRepository, auditEventService);
        aiService = mock(AiService.class);
        reasonWriter = mock(RecommendationReasonWriter.class);
        service = new WorkloadRecommendationService(planRepository, reflectionRepository, auditEventService,
                aiService, reasonWriter, new TemplatePlanGenerator(), planService);
    }

    @Test
    void recommend_소유계획_통계와추천분량_그리고VIEWED기록() {
        when(reasonWriter.write(any())).thenReturn(new RecommendationReasonWriter.ReasonResult("이유 설명", true));
        Plan plan = seedPlan("owner", 5, 3, 1); // 5일 × 3개, 하루 1개 완료 → 33%

        RecommendationResponse response = service.recommend(plan.id(), "owner", "s1");

        assertThat(response.currentTasksPerDay()).isEqualTo(3);
        assertThat(response.recommendedTasksPerDay()).isEqualTo(2); // 완료율 낮음 → -1
        assertThat(response.completionRate()).isLessThan(50);
        assertThat(response.reason()).isEqualTo("이유 설명");
        assertThat(response.aiReasonUsed()).isTrue();
        assertThat(types(auditEventService.getEvents(plan.id(), "owner")))
                .contains("WORKLOAD_RECOMMENDATION_VIEWED");
    }

    @Test
    void recommend_타소유자_404() {
        Plan plan = seedPlan("owner", 5, 3, 1);

        assertThatThrownBy(() -> service.recommend(plan.id(), "intruder", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void draft_AI성공_객체형tasks반환_미저장() {
        Plan plan = seedPlan("owner", 5, 3, 1);
        long before = planRepository.count();
        when(aiService.createDraft(any())).thenReturn(new LinkedHashMap<>(Map.of(
                "2020-02-01", List.of("할 일 A", "할 일 B"))));

        RecommendationDraftResponse draft = service.draft(plan.id(), 2, "owner");

        assertThat(draft.aiUsed()).isTrue();
        assertThat(planRepository.count()).isEqualTo(before); // 저장 안 함(승인 전 미저장)
        Object firstDay = draft.tasks().get("2020-02-01");
        assertThat(firstDay).isInstanceOf(List.class);
        Object firstItem = ((List<?>) firstDay).get(0);
        assertThat(firstItem).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> itemMap = (Map<String, Object>) firstItem;
        assertThat(itemMap).containsKeys("id", "content", "completed");
    }

    @Test
    void draft_AI실패_서버템플릿폴백() {
        Plan plan = seedPlan("owner", 5, 3, 1);
        when(aiService.createDraft(any())).thenThrow(new BusinessException(ErrorCode.AI_UPSTREAM_ERROR));

        RecommendationDraftResponse draft = service.draft(plan.id(), 2, "owner");

        assertThat(draft.aiUsed()).isFalse();
        assertThat(draft.tasks()).hasSize(5); // 원본 duration(5)만큼 템플릿 생성
        draft.tasks().values().forEach(day -> assertThat((List<?>) day).hasSize(2)); // 정확히 2개
    }

    @Test
    void confirm_추천대로_새계획저장_ACCEPTED기록_원본불변() {
        Plan source = seedPlan("owner", 5, 3, 1);
        long before = planRepository.count();
        RecommendationConfirmRequest body = new RecommendationConfirmRequest(
                objectTasks(2, 2), 2, 2); // selected == recommended → accepted

        PlanResponse saved = service.confirm(source.id(), body, "owner", "s1");

        assertThat(planRepository.count()).isEqualTo(before + 1);
        assertThat(saved.id()).isNotEqualTo(source.id());
        List<String> newPlanEvents = types(auditEventService.getEvents(saved.id(), "owner"));
        assertThat(newPlanEvents).contains("PLAN_CREATED",
                "WORKLOAD_RECOMMENDATION_ACCEPTED", "PLAN_CREATED_FROM_RECOMMENDATION");
        // 원본 계획은 그대로 남아 있다.
        assertThat(planRepository.findById(source.id())).isPresent();
    }

    @Test
    void confirm_기존분량선택_OVERRIDDEN기록() {
        Plan source = seedPlan("owner", 5, 3, 1);
        RecommendationConfirmRequest body = new RecommendationConfirmRequest(
                objectTasks(2, 3), 3, 2); // selected != recommended → overridden

        PlanResponse saved = service.confirm(source.id(), body, "owner", "s1");

        assertThat(types(auditEventService.getEvents(saved.id(), "owner")))
                .contains("WORKLOAD_RECOMMENDATION_OVERRIDDEN")
                .doesNotContain("WORKLOAD_RECOMMENDATION_ACCEPTED");
    }

    // === 헬퍼 ===

    private Plan seedPlan(String owner, int days, int tasksPerDay, int completedPerDay) {
        LinkedHashMap<String, Object> tasks = new LinkedHashMap<>();
        java.time.LocalDate start = java.time.LocalDate.parse(START);
        for (int i = 0; i < days; i++) {
            tasks.put(start.plusDays(i).toString(), dayTasks(tasksPerDay, completedPerDay));
        }
        String end = start.plusDays(days - 1).toString();
        return planRepository.save(new Plan(null, owner, "목표", days, 2, "수준", tasks,
                "CONFIRMED", null, START, end, null, 0L));
    }

    // 확정 요청용 tasks({날짜:[{id,content,completed}]}) — days일 × perDay개, 2월 날짜(원본과 분리).
    private static Map<String, Object> objectTasks(int days, int perDay) {
        LinkedHashMap<String, Object> tasks = new LinkedHashMap<>();
        java.time.LocalDate start = java.time.LocalDate.parse("2020-02-01");
        for (int i = 0; i < days; i++) {
            tasks.put(start.plusDays(i).toString(), dayTasks(perDay, 0));
        }
        return tasks;
    }

    private static List<Map<String, Object>> dayTasks(int total, int completed) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            list.add(Map.of("id", "t" + i, "content", "할 일 " + i, "completed", i < completed));
        }
        return list;
    }

    private static List<String> types(List<AuditEventResponse> events) {
        return events.stream().map(AuditEventResponse::type).toList();
    }
}
