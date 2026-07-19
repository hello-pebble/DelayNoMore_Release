package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// 인메모리 저장소라 Mock 없이 실제 PlanRepository를 주입해 Service+Repository를 함께 검증한다.
class PlanServiceTest {

    private static final int MAX_PLANS = 50;
    private static final long MISSING_ID = 999L;

    private final AuditEventService auditEventService = new AuditEventService(new AuditEventRepository());
    private final PlanService planService = new PlanService(new PlanRepository(), new ReflectionRepository(),
            auditEventService);

    private PlanSaveRequest request(String goalName) {
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(Map.of("id", "t-1", "content", "단어 암기", "completed", false)));
        return new PlanSaveRequest(goalName, 3, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z");
    }

    // 고정(CONFIRMED) 가드 테스트용 — 필드 하나만 바꾼 변형 요청을 쉽게 만들기 위한 헬퍼들.
    private static final String CONFIRMED_AT = "2026-07-16T12:00:00Z";

    private static Map<String, Object> tasksOf(boolean completed) {
        return Map.of("2026-07-16", List.of(Map.of("id", "t-1", "content", "단어 암기", "completed", completed)));
    }

    private static PlanSaveRequest confirmedRequest(String goalName, Map<String, Object> tasks,
                                                    String status, String confirmedAt,
                                                    Integer duration, String endDate) {
        return new PlanSaveRequest(goalName, duration, 2, "완전 초보", tasks,
                status, confirmedAt, "2026-07-16", endDate, "2026-07-16T00:00:00Z");
    }

    // 생성 → 고정 PUT을 거쳐 CONFIRMED 상태의 계획을 만든다(프론트의 "계획 저장(고정)" 경로 재현).
    private PlanResponse createConfirmedPlan() {
        PlanResponse saved = planService.create(request("토익 900"), null);
        return planService.update(saved.id(),
                confirmedRequest("토익 900", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), null);
    }

    @Test
    void create_정상요청_ID부여및내용보존() {
        // given
        PlanSaveRequest request = request("토익 900");

        // when
        PlanResponse saved = planService.create(request, null);

        // then
        assertThat(saved.id()).isPositive();
        assertThat(saved.goalName()).isEqualTo("토익 900");
        assertThat(saved.tasks()).isEqualTo(request.tasks());
        assertThat(saved.status()).isEqualTo("DRAFT"); // status 미지정 시 기본값
    }

    @Test
    void create_보관한도초과_PLAN_LIMIT_EXCEEDED예외() {
        // given
        for (int i = 0; i < MAX_PLANS; i++) {
            planService.create(request("목표 " + i), null);
        }

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.create(request("한도 초과"), null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
    }

    @Test
    void getPlans_여러건저장_최근저장순정렬() throws InterruptedException {
        // given
        PlanResponse first = planService.create(request("첫 번째"), null);
        PlanResponse second = planService.create(request("두 번째"), null);

        // when — 첫 번째를 다시 수정하면 savedAt이 갱신되어 목록 선두로 온다
        // (연속 호출은 같은 밀리초에 몰릴 수 있어, savedAt이 확실히 커지도록 잠깐 기다린다)
        Thread.sleep(5);
        planService.update(first.id(), request("첫 번째(수정)"), null);
        List<PlanResponse> plans = planService.getPlans();

        // then
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).goalName()).isEqualTo("첫 번째(수정)");
        assertThat(plans.get(1).id()).isEqualTo(second.id());
    }

    @Test
    void getPlan_존재하는ID_해당계획반환() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), null);

        // when
        PlanResponse found = planService.getPlan(saved.id());

        // then
        assertThat(found).isEqualTo(saved);
    }

    @Test
    void getPlan_없는ID_PLAN_NOT_FOUND예외() {
        // given — 아무것도 저장하지 않음

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.getPlan(MISSING_ID));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void update_존재하는계획_내용과savedAt갱신() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), null);
        PlanSaveRequest updateRequest = new PlanSaveRequest("토익 950", 5, 3, "실전 경험 있음",
                saved.tasks(), "CONFIRMED", "2026-07-16T12:00:00Z",
                saved.startDate(), saved.endDate(), saved.createdAt());

        // when
        PlanResponse updated = planService.update(saved.id(), updateRequest, null);

        // then
        assertThat(updated.id()).isEqualTo(saved.id());
        assertThat(updated.goalName()).isEqualTo("토익 950");
        assertThat(updated.status()).isEqualTo("CONFIRMED"); // 고정 상태도 그대로 왕복
        assertThat(updated.savedAt()).isGreaterThanOrEqualTo(saved.savedAt());
        assertThat(planService.getPlan(saved.id()).goalName()).isEqualTo("토익 950");
    }

    @Test
    void update_없는ID_PLAN_NOT_FOUND예외() {
        // given
        PlanSaveRequest updateRequest = request("토익 900");

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(MISSING_ID, updateRequest, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    // === 고정(CONFIRMED) 계획 수정 가드 — 예전엔 프론트만 지키던 규칙을 서버가 강제한다 ===

    @Test
    void update_고정계획_완료토글만_정상반영() {
        // given
        PlanResponse confirmed = createConfirmedPlan();

        // when — completed만 플립한 전체 PUT (프론트 완료 체크 경로)
        PlanResponse updated = planService.update(confirmed.id(),
                confirmedRequest("토익 900", tasksOf(true), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), null);

        // then
        assertThat(updated.status()).isEqualTo("CONFIRMED");
        assertThat(updated.tasks()).isEqualTo(tasksOf(true));
    }

    @Test
    void update_고정계획_동일페이로드_허용() {
        // given
        PlanResponse confirmed = createConfirmedPlan();

        // when — no-op PUT(완전 동일)은 구조 변경이 아니므로 통과해야 한다
        PlanResponse updated = planService.update(confirmed.id(),
                confirmedRequest("토익 900", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), null);

        // then
        assertThat(updated.goalName()).isEqualTo("토익 900");
    }

    @Test
    void update_고정계획_목표변경_PLAN_LOCKED예외_저장소원상태유지() {
        // given
        PlanResponse confirmed = createConfirmedPlan();
        int eventCountBefore = auditEventService.getEvents(confirmed.id()).size();

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        confirmedRequest("토익 990", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"),
                        null));

        // then — 거부되고, 저장소는 원상태이며, 감사 이벤트도 발행되지 않는다
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
        assertThat(planService.getPlan(confirmed.id()).goalName()).isEqualTo("토익 900");
        assertThat(auditEventService.getEvents(confirmed.id())).hasSize(eventCountBefore);
    }

    @Test
    void update_고정계획_기간연장_PLAN_LOCKED예외() {
        // given — 이월이 만드는 duration/endDate +1 연장 흉내(고정 계획은 이월도 불가)
        PlanResponse confirmed = createConfirmedPlan();

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        confirmedRequest("토익 900", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 4, "2026-07-19"),
                        null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
    }

    @Test
    void update_고정계획_할일구조변경_PLAN_LOCKED예외() {
        // given
        PlanResponse confirmed = createConfirmedPlan();
        Map<String, Object> rewritten = Map.of(
                "2026-07-16", List.of(Map.of("id", "t-1", "content", "듣기 연습", "completed", false)));

        // when — 내용(content)을 바꾼 PUT
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        confirmedRequest("토익 900", rewritten, "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"),
                        null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
    }

    @Test
    void update_고정계획_DRAFT롤백_PLAN_LOCKED예외() {
        // given
        PlanResponse confirmed = createConfirmedPlan();

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        confirmedRequest("토익 900", tasksOf(false), "DRAFT", null, 3, "2026-07-18"), null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
    }

    @Test
    void update_고정계획_confirmedAt변경_PLAN_LOCKED예외() {
        // given
        PlanResponse confirmed = createConfirmedPlan();

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        confirmedRequest("토익 900", tasksOf(false), "CONFIRMED", "2026-07-17T00:00:00Z",
                                3, "2026-07-18"), null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
    }

    @Test
    void update_DRAFT계획_구조변경_허용() {
        // given — 가드는 CONFIRMED에만 걸린다(회귀 확인)
        PlanResponse saved = planService.create(request("토익 900"), null);

        // when
        PlanResponse updated = planService.update(saved.id(), request("토익 990"), null);

        // then
        assertThat(updated.goalName()).isEqualTo("토익 990");
    }

    @Test
    void update_DRAFT에서_수정과고정이한PUT으로_허용() {
        // given — 600ms 디바운스 안에 내용 수정 + "계획 저장(고정)"이 한 PUT으로 합쳐지는 실제 시나리오
        PlanResponse saved = planService.create(request("토익 900"), null);

        // when
        PlanResponse updated = planService.update(saved.id(),
                confirmedRequest("토익 990", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), null);

        // then
        assertThat(updated.goalName()).isEqualTo("토익 990");
        assertThat(updated.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void delete_존재하는계획_목록에서제거() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), null);

        // when
        planService.delete(saved.id(), null);

        // then
        assertThat(planService.getPlans()).isEmpty();
    }

    @Test
    void delete_없는ID_PLAN_NOT_FOUND예외() {
        // given — 아무것도 저장하지 않음

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.delete(MISSING_ID, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }
}
