package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.CarryOverResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.dto.WeeklySummaryResponse;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.time.KstDates;
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
    void create_진행률계산_progress포함() {
        // given — 이틀에 걸쳐 완료 2건 / 전체 5건
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(
                        Map.of("id", "t-1", "content", "단어 암기", "completed", true),
                        Map.of("id", "t-2", "content", "듣기 연습", "completed", false),
                        Map.of("id", "t-3", "content", "문법 정리", "completed", true)),
                "2026-07-17", List.of(
                        Map.of("id", "t-4", "content", "모의고사", "completed", false),
                        Map.of("id", "t-5", "content", "오답 노트", "completed", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 3, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z");

        // when
        PlanResponse saved = planService.create(request, null);

        // then — 완료율 계산은 서버 소유
        assertThat(saved.progress()).isEqualTo(new PlanResponse.Progress(2, 5));
    }

    @Test
    void create_tasks비정상구조_progress방어계산() {
        // given — 날짜 값이 List가 아닌 항목이 섞여 있어도(프론트 원본 그대로 보관) 죽지 않고 무시
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(Map.of("id", "t-1", "content", "단어 암기", "completed", true)),
                "2026-07-17", "이상한 값");
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 3, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z");

        // when
        PlanResponse saved = planService.create(request, null);

        // then — 비정상 날짜는 0건 취급, 정상 날짜만 집계
        assertThat(saved.progress()).isEqualTo(new PlanResponse.Progress(1, 1));
    }

    // === 날짜 규칙 서버 이관 — startDate(산출·불변)·duration(산출)은 서버가 소유한다 ===

    @Test
    void create_startDate를_최초날짜키로_산출_클라이언트값무시() {
        // given — 여러 날에 걸친 tasks + 엉뚱한 클라이언트 startDate(2099-01-01)
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(Map.of("id", "t-1", "content", "단어 암기", "completed", false)),
                "2026-07-17", List.of(Map.of("id", "t-2", "content", "듣기 연습", "completed", false)),
                "2026-07-18", List.of(Map.of("id", "t-3", "content", "문법 정리", "completed", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 99, 2, "완전 초보", tasks,
                null, null, "2099-01-01", "2026-07-18", "2026-07-16T00:00:00Z");

        // when
        PlanResponse saved = planService.create(request, null);

        // then — 서버가 tasks 최초 날짜 키로 산출(클라이언트 startDate 무시)
        assertThat(saved.startDate()).isEqualTo("2026-07-16");
    }

    @Test
    void create_duration을_startDate와endDate범위로_산출_클라이언트값무시() {
        // given — 범위와 어긋난 클라이언트 duration(99)
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(Map.of("id", "t-1", "content", "단어 암기", "completed", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 99, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z");

        // when
        PlanResponse saved = planService.create(request, null);

        // then — span(07-16, 07-18) = 3
        assertThat(saved.duration()).isEqualTo(3);
    }

    @Test
    void create_단일일계획_duration1() {
        // given — 시작=종료(하루짜리)
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(Map.of("id", "t-1", "content", "총정리", "completed", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 5, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-16", "2026-07-16T00:00:00Z");

        // when
        PlanResponse saved = planService.create(request, null);

        // then
        assertThat(saved.duration()).isEqualTo(1);
    }

    @Test
    void update_startDate불변_클라이언트값무시() {
        // given — 생성 시 startDate=2026-07-16, 이후 클라이언트가 다른 값을 보내도 보존
        PlanResponse saved = planService.create(request("토익 900"), null);
        PlanSaveRequest updateRequest = new PlanSaveRequest("토익 900", 3, 2, "완전 초보",
                saved.tasks(), null, null, "2000-01-01", "2026-07-18", saved.createdAt());

        // when
        PlanResponse updated = planService.update(saved.id(), updateRequest, null);

        // then
        assertThat(updated.startDate()).isEqualTo("2026-07-16");
    }

    @Test
    void update_duration재산출_클라이언트값무시() {
        // given — endDate를 07-20으로 늘리고 어긋난 duration(99)을 보낸다
        PlanResponse saved = planService.create(request("토익 900"), null);
        PlanSaveRequest updateRequest = new PlanSaveRequest("토익 900", 99, 2, "완전 초보",
                saved.tasks(), null, null, saved.startDate(), "2026-07-20", saved.createdAt());

        // when
        PlanResponse updated = planService.update(saved.id(), updateRequest, null);

        // then — span(07-16, 07-20) = 5
        assertThat(updated.duration()).isEqualTo(5);
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

    // === 주간 완료율 요약 — 계획을 startDate 기준 7일 버킷으로 묶어 주별 완료율(서버 소유) ===

    @Test
    void getWeeklySummary_8일계획_2주차로분할_주별완료율산출() {
        // given — 8일(07-16~07-23): 1주차(07-16~07-22)에 완료 1/전체 2, 2주차(07-23)에 완료 1/전체 1
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(
                        Map.of("id", "t-1", "content", "단어 암기", "completed", true),
                        Map.of("id", "t-2", "content", "듣기 연습", "completed", false)),
                "2026-07-23", List.of(
                        Map.of("id", "t-3", "content", "총정리", "completed", true)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 8, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-23", "2026-07-16T00:00:00Z");
        PlanResponse saved = planService.create(request, null);

        // when
        WeeklySummaryResponse summary = planService.getWeeklySummary(saved.id());

        // then — 주 경계 포함 판정(07-23은 2주차)과 주별 done/total/rate
        assertThat(summary.planId()).isEqualTo(saved.id());
        assertThat(summary.startDate()).isEqualTo("2026-07-16");
        assertThat(summary.endDate()).isEqualTo("2026-07-23");
        assertThat(summary.totalDone()).isEqualTo(2);
        assertThat(summary.totalTotal()).isEqualTo(3);
        assertThat(summary.weeks()).containsExactly(
                new WeeklySummaryResponse.Week(1, "2026-07-16", "2026-07-22", 1, 2, 50),
                new WeeklySummaryResponse.Week(2, "2026-07-23", "2026-07-23", 1, 1, 100));
    }

    @Test
    void getWeeklySummary_주별합계_countAllTasks와일치() {
        // given — 주 경계에 걸친 완료/전체가 주별로 나뉘어도 합계는 전체 진행률과 같아야 한다
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(
                        Map.of("id", "t-1", "content", "a", "completed", true),
                        Map.of("id", "t-2", "content", "b", "completed", false)),
                "2026-07-24", List.of(
                        Map.of("id", "t-3", "content", "c", "completed", true),
                        Map.of("id", "t-4", "content", "d", "completed", true)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 9, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-24", "2026-07-16T00:00:00Z");
        PlanResponse saved = planService.create(request, null);

        // when
        WeeklySummaryResponse summary = planService.getWeeklySummary(saved.id());

        // then
        int weekDoneSum = summary.weeks().stream().mapToInt(WeeklySummaryResponse.Week::done).sum();
        int weekTotalSum = summary.weeks().stream().mapToInt(WeeklySummaryResponse.Week::total).sum();
        assertThat(weekDoneSum).isEqualTo(summary.totalDone()).isEqualTo(3);
        assertThat(weekTotalSum).isEqualTo(summary.totalTotal()).isEqualTo(4);
    }

    @Test
    void getWeeklySummary_rate_반올림() {
        // given — 완료 1/전체 3 → 33% (Math.round(33.33))
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(
                        Map.of("id", "t-1", "content", "a", "completed", true),
                        Map.of("id", "t-2", "content", "b", "completed", false),
                        Map.of("id", "t-3", "content", "c", "completed", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 1, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-16", "2026-07-16T00:00:00Z");
        PlanResponse saved = planService.create(request, null);

        // when
        WeeklySummaryResponse summary = planService.getWeeklySummary(saved.id());

        // then
        assertThat(summary.weeks()).hasSize(1);
        assertThat(summary.weeks().get(0).rate()).isEqualTo(33);
    }

    @Test
    void getWeeklySummary_없는ID_PLAN_NOT_FOUND예외() {
        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.getWeeklySummary(MISSING_ID));

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

    // === 미완료 이월(carry-over) 도메인 액션 — 날짜 규칙(오늘 KST → 내일)은 서버 소유 ===

    private static final String TODAY = KstDates.today().toString();
    private static final String TOMORROW = KstDates.today().plusDays(1).toString();

    private PlanResponse createPlanWithTasks(Map<String, Object> tasks, Integer duration, String endDate) {
        PlanSaveRequest request = new PlanSaveRequest("토익 900", duration, 2, "완전 초보", tasks,
                null, null, TODAY, endDate, TODAY + "T00:00:00Z");
        return planService.create(request, null);
    }

    @Test
    void carryOver_미완료있음_내일로이동및이력발행() {
        // given — 오늘 미완료 2건 + 완료 1건, 내일 키는 아직 없음(endDate는 내일이라 연장 없음)
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "단어 암기", "completed", false),
                Map.of("id", "t-2", "content", "문법 정리", "completed", true),
                Map.of("id", "t-3", "content", "듣기 연습", "completed", false)));
        PlanResponse saved = createPlanWithTasks(tasks, 2, TOMORROW);

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), "session-a");

        // then — 미완료만 내일로, savedAt 갱신, "이동" detail의 PLAN_UPDATED 발행
        assertThat(result.movedCount()).isEqualTo(2);
        assertThat(result.targetDate()).isEqualTo(TOMORROW);
        assertThat(result.plan().tasks().get(TODAY))
                .isEqualTo(List.of(Map.of("id", "t-2", "content", "문법 정리", "completed", true)));
        assertThat(result.plan().tasks().get(TOMORROW)).isEqualTo(List.of(
                Map.of("id", "t-1", "content", "단어 암기", "completed", false),
                Map.of("id", "t-3", "content", "듣기 연습", "completed", false)));
        assertThat(result.plan().savedAt()).isGreaterThanOrEqualTo(saved.savedAt());
        assertThat(auditEventService.getEvents(saved.id()).get(0).detail())
                .isEqualTo("미완료 2건을 " + TOMORROW + "로 이동");
    }

    @Test
    void carryOver_종료일이오늘_endDate와duration하루연장() {
        // given — 마지막 날의 미완료를 넘기면 기간이 하루 늘어난다(프론트 기존 동작 그대로)
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "총정리", "completed", false)));
        PlanResponse saved = createPlanWithTasks(tasks, 1, TODAY);

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), null);

        // then
        assertThat(result.plan().endDate()).isEqualTo(TOMORROW);
        assertThat(result.plan().duration()).isEqualTo(2);
    }

    @Test
    void carryOver_종료일이내일이후_기간연장없음() {
        // given
        String dayAfterTomorrow = KstDates.today().plusDays(2).toString();
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "단어 암기", "completed", false)));
        PlanResponse saved = createPlanWithTasks(tasks, 3, dayAfterTomorrow);

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), null);

        // then
        assertThat(result.plan().endDate()).isEqualTo(dayAfterTomorrow);
        assertThat(result.plan().duration()).isEqualTo(3);
    }

    @Test
    void carryOver_startDate불변_오늘키삭제돼도유지() {
        // given — 오늘 미완료 1건뿐 → 이월하면 오늘 키가 비어 삭제되고 최소 날짜 키가 내일로 이동한다
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "총정리", "completed", false)));
        PlanResponse saved = createPlanWithTasks(tasks, 1, TODAY); // startDate=TODAY

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), null);

        // then — 오늘 키는 사라졌지만 startDate는 생성 시 값(TODAY)으로 보존(min-key를 추적하지 않는다)
        assertThat(result.plan().tasks()).doesNotContainKey(TODAY);
        assertThat(result.plan().startDate()).isEqualTo(TODAY);
    }

    @Test
    void carryOver_이월할미완료없음_movedCount0_계획불변() {
        // given — 오늘 전부 완료
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "단어 암기", "completed", true)));
        PlanResponse saved = createPlanWithTasks(tasks, 2, TOMORROW);
        int eventCountBefore = auditEventService.getEvents(saved.id()).size();

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), null);

        // then — 정상 no-op: savedAt 보존(계획 불변), 이력도 없다
        assertThat(result.movedCount()).isZero();
        assertThat(result.plan().tasks()).isEqualTo(tasks);
        assertThat(result.plan().savedAt()).isEqualTo(saved.savedAt());
        assertThat(auditEventService.getEvents(saved.id())).hasSize(eventCountBefore);
    }

    @Test
    void carryOver_고정계획_PLAN_LOCKED예외() {
        // given — 이월은 구조 변경이므로 고정 계획에는 PUT 가드와 같은 판정이 걸린다
        PlanResponse confirmed = createConfirmedPlan();

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.carryOver(confirmed.id(), null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
    }

    @Test
    void carryOver_없는ID_PLAN_NOT_FOUND예외() {
        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.carryOver(MISSING_ID, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
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
