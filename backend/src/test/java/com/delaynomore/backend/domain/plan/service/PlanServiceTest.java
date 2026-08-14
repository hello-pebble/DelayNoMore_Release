package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.CarryOverResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.dto.WeeklySummaryResponse;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryAuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryReflectionRepository;
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

    private static final int MAX_PLANS_PER_OWNER = 10;
    private static final int MAX_PLANS_GLOBAL = 200;
    private static final long MISSING_ID = 999L;

    // 소유자(닉네임) 스코프 — 서비스 시그니처가 owner를 받으므로 테스트 기본 소유자를 고정한다.
    private static final String OWNER = "guest-a";
    private static final String OTHER_OWNER = "guest-b";

    // AuditEventService.getEvents가 계획 소유자를 확인하므로 PlanService와 같은 저장소를 공유해야 한다.
    private final PlanRepository planRepository = new InMemoryPlanRepository();
    private final AuditEventService auditEventService =
            new AuditEventService(new InMemoryAuditEventRepository());
    private final PlanService planService = new PlanService(planRepository, new InMemoryReflectionRepository(),
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
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        return planService.update(saved.id(),
                confirmedRequest("토익 900", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), OWNER, null);
    }

    @Test
    void create_정상요청_ID부여및내용보존() {
        // given
        PlanSaveRequest request = request("토익 900");

        // when
        PlanResponse saved = planService.create(request, OWNER, null);

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
        PlanResponse saved = planService.create(request, OWNER, null);

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
        PlanResponse saved = planService.create(request, OWNER, null);

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
        PlanResponse saved = planService.create(request, OWNER, null);

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
        PlanResponse saved = planService.create(request, OWNER, null);

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
        PlanResponse saved = planService.create(request, OWNER, null);

        // then
        assertThat(saved.duration()).isEqualTo(1);
    }

    @Test
    void update_startDate불변_클라이언트값무시() {
        // given — 생성 시 startDate=2026-07-16, 이후 클라이언트가 다른 값을 보내도 보존
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        PlanSaveRequest updateRequest = new PlanSaveRequest("토익 900", 3, 2, "완전 초보",
                saved.tasks(), null, null, "2000-01-01", "2026-07-18", saved.createdAt());

        // when
        PlanResponse updated = planService.update(saved.id(), updateRequest, OWNER, null);

        // then
        assertThat(updated.startDate()).isEqualTo("2026-07-16");
    }

    @Test
    void update_duration재산출_클라이언트값무시() {
        // given — endDate를 07-20으로 늘리고 어긋난 duration(99)을 보낸다
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        PlanSaveRequest updateRequest = new PlanSaveRequest("토익 900", 99, 2, "완전 초보",
                saved.tasks(), null, null, saved.startDate(), "2026-07-20", saved.createdAt());

        // when
        PlanResponse updated = planService.update(saved.id(), updateRequest, OWNER, null);

        // then — span(07-16, 07-20) = 5
        assertThat(updated.duration()).isEqualTo(5);
    }

    // 보관 개수 한도 테스트용 시딩 — 리포지토리에 직접 저장해 감사 이벤트(PLAN_CREATED)를 남기지
    // 않는다. 일일 생성 한도(5) < 보관 한도(10)라, create로 채우면 일일 한도에 먼저 걸린다.
    private void seedPlan(String goalName, String owner) {
        planRepository.save(request(goalName).toPlan(null, System.currentTimeMillis(), "2026-07-16", 3, owner));
    }

    @Test
    void create_소유자한도초과_PLAN_LIMIT_EXCEEDED예외_타소유자는영향없음() {
        // given — OWNER가 자기 한도(10)를 채운다(직접 시딩 — 일일 생성 한도 미소모)
        for (int i = 0; i < MAX_PLANS_PER_OWNER; i++) {
            seedPlan("목표 " + i, OWNER);
        }

        // when — OWNER의 11번째는 거부되지만
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.create(request("한도 초과"), OWNER, null));

        // then — 소유자당 한도(내 보관함 가득참), 다른 소유자는 여전히 생성 가능(격리 증명)
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
        assertThat(planService.create(request("타인 계획"), OTHER_OWNER, null).id()).isPositive();
    }

    @Test
    void create_전역상한초과_PLAN_STORE_FULL예외() {
        // given — 20명이 각 10건씩 = 200건으로 저장소를 전역 상한까지 채운다(직접 시딩)
        int owners = MAX_PLANS_GLOBAL / MAX_PLANS_PER_OWNER;
        for (int o = 0; o < owners; o++) {
            for (int i = 0; i < MAX_PLANS_PER_OWNER; i++) {
                seedPlan("g" + o + "-" + i, "guest-" + o);
            }
        }

        // when — 자기 보관함은 0건인 신규 소유자라도 전역이 가득 차 저장 불가
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.create(request("전역 초과"), "guest-new", null));

        // then — 서버 메모리 보호(503 성격)
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_STORE_FULL);
    }

    @Test
    void create_일일한도5회초과_PLAN_DAILY_LIMIT_EXCEEDED예외_타소유자는영향없음() {
        // given — OWNER가 오늘의 생성 한도(5)를 채운다
        for (int i = 0; i < 5; i++) {
            planService.create(request("목표 " + i), OWNER, null);
        }

        // when — 6번째는 거부
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.create(request("6번째"), OWNER, null));

        // then — 일일 한도(429 성격), 다른 소유자는 여전히 생성 가능(격리 증명)
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_DAILY_LIMIT_EXCEEDED);
        assertThat(planService.create(request("타인 계획"), OTHER_OWNER, null).id()).isPositive();
    }

    @Test
    void create_일일한도_삭제해도카운트유지_재생성우회불가() {
        // given — 생성→삭제를 5회 반복(보관함은 항상 비어 있음)
        for (int i = 0; i < 5; i++) {
            PlanResponse saved = planService.create(request("목표 " + i), OWNER, null);
            planService.delete(saved.id(), OWNER, null);
        }

        // when/then — 카운트 소스는 삭제를 살아남는 감사 이력이라 6번째도 차단
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.create(request("우회 시도"), OWNER, null));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_DAILY_LIMIT_EXCEEDED);
    }

    @Test
    void getPlans_여러건저장_최근저장순정렬() throws InterruptedException {
        // given
        PlanResponse first = planService.create(request("첫 번째"), OWNER, null);
        PlanResponse second = planService.create(request("두 번째"), OWNER, null);

        // when — 첫 번째를 다시 수정하면 savedAt이 갱신되어 목록 선두로 온다
        // (연속 호출은 같은 밀리초에 몰릴 수 있어, savedAt이 확실히 커지도록 잠깐 기다린다)
        Thread.sleep(5);
        planService.update(first.id(), request("첫 번째(수정)"), OWNER, null);
        List<PlanResponse> plans = planService.getPlans(OWNER);

        // then
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).goalName()).isEqualTo("첫 번째(수정)");
        assertThat(plans.get(1).id()).isEqualTo(second.id());
    }

    @Test
    void getPlan_존재하는ID_해당계획반환() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        PlanResponse found = planService.getPlan(saved.id(), OWNER);

        // then
        assertThat(found).isEqualTo(saved);
    }

    @Test
    void getPlan_없는ID_PLAN_NOT_FOUND예외() {
        // given — 아무것도 저장하지 않음

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.getPlan(MISSING_ID, OWNER));

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
        PlanResponse saved = planService.create(request, OWNER, null);

        // when
        WeeklySummaryResponse summary = planService.getWeeklySummary(saved.id(), OWNER);

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
        PlanResponse saved = planService.create(request, OWNER, null);

        // when
        WeeklySummaryResponse summary = planService.getWeeklySummary(saved.id(), OWNER);

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
        PlanResponse saved = planService.create(request, OWNER, null);

        // when
        WeeklySummaryResponse summary = planService.getWeeklySummary(saved.id(), OWNER);

        // then
        assertThat(summary.weeks()).hasSize(1);
        assertThat(summary.weeks().get(0).rate()).isEqualTo(33);
    }

    @Test
    void getWeeklySummary_없는ID_PLAN_NOT_FOUND예외() {
        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.getWeeklySummary(MISSING_ID, OWNER));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void update_존재하는계획_내용과savedAt갱신() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        PlanSaveRequest updateRequest = new PlanSaveRequest("토익 950", 5, 3, "실전 경험 있음",
                saved.tasks(), "CONFIRMED", "2026-07-16T12:00:00Z",
                saved.startDate(), saved.endDate(), saved.createdAt());

        // when
        PlanResponse updated = planService.update(saved.id(), updateRequest, OWNER, null);

        // then
        assertThat(updated.id()).isEqualTo(saved.id());
        assertThat(updated.goalName()).isEqualTo("토익 950");
        assertThat(updated.status()).isEqualTo("CONFIRMED"); // 고정 상태도 그대로 왕복
        assertThat(updated.savedAt()).isGreaterThanOrEqualTo(saved.savedAt());
        assertThat(planService.getPlan(saved.id(), OWNER).goalName()).isEqualTo("토익 950");
    }

    @Test
    void update_없는ID_PLAN_NOT_FOUND예외() {
        // given
        PlanSaveRequest updateRequest = request("토익 900");

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(MISSING_ID, updateRequest, OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    // === 고정(CONFIRMED) 계획 수정 가드 — 예전엔 프론트만 지키던 규칙을 서버가 강제한다 ===

    @Test
    void update_고정계획_완료토글만_정상반영() {
        // given — 오늘 날짜 항목(지난 날짜는 PAST_TASK_LOCKED로 별도 잠금 — 아래 섹션)
        PlanResponse confirmed = confirmedPlanWithTask(TODAY, false);

        // when — completed만 플립한 전체 PUT (프론트 완료 체크 경로)
        PlanResponse updated = planService.update(confirmed.id(),
                toggleRequest(confirmed, TODAY, true), OWNER, null);

        // then
        assertThat(updated.status()).isEqualTo("CONFIRMED");
        assertThat(updated.tasks()).isEqualTo(taskMapOf(TODAY, true));
    }

    @Test
    void update_고정계획_동일페이로드_허용() {
        // given
        PlanResponse confirmed = createConfirmedPlan();

        // when — no-op PUT(완전 동일)은 구조 변경이 아니므로 통과해야 한다
        PlanResponse updated = planService.update(confirmed.id(),
                confirmedRequest("토익 900", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), OWNER, null);

        // then
        assertThat(updated.goalName()).isEqualTo("토익 900");
    }

    @Test
    void update_고정계획_목표변경_PLAN_LOCKED예외_저장소원상태유지() {
        // given
        PlanResponse confirmed = createConfirmedPlan();
        int eventCountBefore = auditEventService.getEvents(confirmed.id(), OWNER).size();

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        confirmedRequest("토익 990", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), OWNER, null));

        // then — 거부되고, 저장소는 원상태이며, 감사 이벤트도 발행되지 않는다
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
        assertThat(planService.getPlan(confirmed.id(), OWNER).goalName()).isEqualTo("토익 900");
        assertThat(auditEventService.getEvents(confirmed.id(), OWNER)).hasSize(eventCountBefore);
    }

    @Test
    void update_고정계획_기간연장_PLAN_LOCKED예외() {
        // given — 이월이 만드는 duration/endDate +1 연장 흉내(고정 계획은 이월도 불가)
        PlanResponse confirmed = createConfirmedPlan();

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        confirmedRequest("토익 900", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 4, "2026-07-19"), OWNER, null));

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
                        confirmedRequest("토익 900", rewritten, "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), OWNER, null));

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
                        confirmedRequest("토익 900", tasksOf(false), "DRAFT", null, 3, "2026-07-18"), OWNER, null));

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
                                3, "2026-07-18"), OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
    }

    // === 지난 날짜 완료 체크 잠금 — 이월이 "오늘 → 내일"뿐이라 미루지 않은 지난 항목은 놓친 것으로
    // 확정된다. 고정(CONFIRMED) 계획의 지난 날짜는 체크·해제 모두 거부(PAST_TASK_LOCKED)해
    // 완료율("N/M 완료" 이력 포함) 소급 조작을 막는다. 오늘·미래는 양방향 허용. ===

    private static final String YESTERDAY = KstDates.today().minusDays(1).toString();

    private static Map<String, Object> taskMapOf(String date, boolean completed) {
        return Map.of(date, List.of(Map.of("id", "t-1", "content", "단어 암기", "completed", completed)));
    }

    // date 하루짜리 항목을 가진 CONFIRMED 계획 — 전이 엔드포인트로 고정한다(confirmedAt 서버 발급).
    private PlanResponse confirmedPlanWithTask(String date, boolean completed) {
        PlanSaveRequest request = new PlanSaveRequest("토익 900", null, 2, "완전 초보",
                taskMapOf(date, completed), null, null, date, TOMORROW, date + "T00:00:00Z");
        PlanResponse saved = planService.create(request, OWNER, null);
        return planService.confirm(saved.id(), OWNER, null);
    }

    // completed만 플립한 전체 PUT — 상태·confirmedAt·기간은 현재값 그대로 실어 토글-only로 만든다.
    private static PlanSaveRequest toggleRequest(PlanResponse plan, String date, boolean completed) {
        return new PlanSaveRequest(plan.goalName(), plan.duration(), plan.dailyHours(), plan.currentLevel(),
                taskMapOf(date, completed), plan.status(), plan.confirmedAt(),
                plan.startDate(), plan.endDate(), plan.createdAt());
    }

    @Test
    void update_고정계획_지난날짜체크_PAST_TASK_LOCKED예외_저장소원상태유지() {
        // given — 어제 미완료 항목(어제 미루지도 않았다 — 놓친 항목)
        PlanResponse confirmed = confirmedPlanWithTask(YESTERDAY, false);

        // when — 오늘 와서 사후 체크 시도
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        toggleRequest(confirmed, YESTERDAY, true), OWNER, null));

        // then — 거부되고 저장소는 원상태(완료율 소급 조작 불가)
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAST_TASK_LOCKED);
        assertThat(planService.getPlan(confirmed.id(), OWNER).tasks()).isEqualTo(taskMapOf(YESTERDAY, false));
    }

    @Test
    void update_고정계획_지난날짜체크해제_PAST_TASK_LOCKED예외() {
        // given — 어제 체크된 항목: 지난 기록은 방향 불문 확정(해제도 금지)
        PlanResponse confirmed = confirmedPlanWithTask(YESTERDAY, true);

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(confirmed.id(),
                        toggleRequest(confirmed, YESTERDAY, false), OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAST_TASK_LOCKED);
    }

    @Test
    void update_고정계획_내일미리체크_허용() {
        // given — 미래는 조작이 아니라 앞서 나가는 것: 미리 체크 허용(확정 규칙)
        PlanResponse confirmed = confirmedPlanWithTask(TOMORROW, false);

        // when
        PlanResponse updated = planService.update(confirmed.id(),
                toggleRequest(confirmed, TOMORROW, true), OWNER, null);

        // then
        assertThat(updated.tasks()).isEqualTo(taskMapOf(TOMORROW, true));
    }

    @Test
    void update_DRAFT계획_지난날짜체크_허용() {
        // given — DRAFT는 자유 수정 단계라 날짜 잠금 미적용(구조 변경도 되는데 토글만 막는 건 무의미)
        PlanSaveRequest request = new PlanSaveRequest("토익 900", null, 2, "완전 초보",
                taskMapOf(YESTERDAY, false), null, null, YESTERDAY, TOMORROW, YESTERDAY + "T00:00:00Z");
        PlanResponse saved = planService.create(request, OWNER, null);

        // when
        PlanResponse updated = planService.update(saved.id(),
                toggleRequest(saved, YESTERDAY, true), OWNER, null);

        // then
        assertThat(updated.tasks()).isEqualTo(taskMapOf(YESTERDAY, true));
    }

    @Test
    void update_DRAFT계획_구조변경_허용() {
        // given — 가드는 CONFIRMED에만 걸린다(회귀 확인)
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        PlanResponse updated = planService.update(saved.id(), request("토익 990"), OWNER, null);

        // then
        assertThat(updated.goalName()).isEqualTo("토익 990");
    }

    @Test
    void update_DRAFT에서_수정과고정이한PUT으로_허용() {
        // given — 600ms 디바운스 안에 내용 수정 + "계획 저장(고정)"이 한 PUT으로 합쳐지는 실제 시나리오
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        PlanResponse updated = planService.update(saved.id(),
                confirmedRequest("토익 990", tasksOf(false), "CONFIRMED", CONFIRMED_AT, 3, "2026-07-18"), OWNER, null);

        // then
        assertThat(updated.goalName()).isEqualTo("토익 990");
        assertThat(updated.status()).isEqualTo("CONFIRMED");
    }

    // === 상태 전이 도메인 액션(confirm·complete·cancel) — 전이 규칙은 PlanStatus 전이표 소유 ===

    @Test
    void confirm_DRAFT계획_CONFIRMED전환_서버시각발급_이력발행() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        PlanResponse confirmed = planService.confirm(saved.id(), OWNER, "session-a");

        // then — 상태 전환, confirmedAt은 서버 발급(클라이언트가 보낸 적 없음), 전이명 이력 발행
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(confirmed.completedAt()).isNull();
        assertThat(auditEventService.getEvents(saved.id(), OWNER).get(0).type()).isEqualTo("PLAN_CONFIRMED");
    }

    @Test
    void confirm_이미CONFIRMED_INVALID_STATUS_TRANSITION예외() {
        // given — self-loop는 전이표에 없다
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        planService.confirm(saved.id(), OWNER, null);

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.confirm(saved.id(), OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void complete_CONFIRMED계획_COMPLETED전환_완료시각과진행률이력발행() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        planService.confirm(saved.id(), OWNER, null);

        // when
        PlanResponse completed = planService.complete(saved.id(), OWNER, null);

        // then — 종결 상태 + completedAt 서버 발급 + 진행률 detail의 PLAN_COMPLETED
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.confirmedAt()).isNotNull(); // 고정 시각은 보존
        var latest = auditEventService.getEvents(saved.id(), OWNER).get(0);
        assertThat(latest.type()).isEqualTo("PLAN_COMPLETED");
        assertThat(latest.detail()).isEqualTo("0/1 완료");
    }

    @Test
    void complete_DRAFT계획_INVALID_STATUS_TRANSITION예외() {
        // given — DRAFT→COMPLETED 간선은 없다(고정을 거쳐야 완료할 수 있다)
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.complete(saved.id(), OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void cancel_DRAFT계획_CANCELLED전환_이력발행() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        PlanResponse cancelled = planService.cancel(saved.id(), OWNER, null);

        // then — 시각 필드는 건드리지 않는다(중단 시각은 이력이 담당)
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.confirmedAt()).isNull();
        assertThat(cancelled.completedAt()).isNull();
        var latest = auditEventService.getEvents(saved.id(), OWNER).get(0);
        assertThat(latest.type()).isEqualTo("PLAN_CANCELLED");
        assertThat(latest.detail()).isEqualTo("\"토익 900\" 중단");
    }

    @Test
    void cancel_CONFIRMED계획_CANCELLED전환() {
        // given — 실행 중(고정) 계획도 중단할 수 있다
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        planService.confirm(saved.id(), OWNER, null);

        // when
        PlanResponse cancelled = planService.cancel(saved.id(), OWNER, null);

        // then
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
    }

    @Test
    void 종결상태_모든전이거부_저장소원상태유지() {
        // given — COMPLETED는 나가는 간선이 없다
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        planService.confirm(saved.id(), OWNER, null);
        planService.complete(saved.id(), OWNER, null);
        int eventCountBefore = auditEventService.getEvents(saved.id(), OWNER).size();

        // when·then — confirm·complete·cancel 전부 409, 저장소·이력 불변
        for (var action : List.<Runnable>of(
                () -> planService.confirm(saved.id(), OWNER, null),
                () -> planService.complete(saved.id(), OWNER, null),
                () -> planService.cancel(saved.id(), OWNER, null))) {
            BusinessException exception = catchThrowableOfType(BusinessException.class, action::run);
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        assertThat(planService.getPlan(saved.id(), OWNER).status()).isEqualTo("COMPLETED");
        assertThat(auditEventService.getEvents(saved.id(), OWNER)).hasSize(eventCountBefore);
    }

    @Test
    void update_종결계획_완료토글PUT도_PLAN_LOCKED예외() {
        // given — COMPLETED는 전면 잠금: CONFIRMED에서 허용되던 토글-only PUT도 거부된다
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        planService.confirm(saved.id(), OWNER, null);
        String confirmedAt = planService.complete(saved.id(), OWNER, null).confirmedAt();

        // when — 상태/confirmedAt을 현재값 그대로 싣고 completed만 플립한 PUT
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.update(saved.id(),
                        new PlanSaveRequest("토익 900", 3, 2, "완전 초보", tasksOf(true),
                                "CONFIRMED", confirmedAt, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z"),
                        OWNER, null));

        // then — 상태 불일치(COMPLETED≠CONFIRMED)로 걸러진다(레거시 PUT 호환 코드 PLAN_LOCKED 유지)
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
        assertThat(planService.getPlan(saved.id(), OWNER).status()).isEqualTo("COMPLETED");
    }

    @Test
    void carryOver_종결계획_PLAN_LOCKED예외() {
        // given — 이월은 구조 변경이므로 종결 상태에도 고정과 같은 판정
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        planService.cancel(saved.id(), OWNER, null);

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.carryOver(saved.id(), OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_LOCKED);
    }

    @Test
    void confirm_다른소유자또는없는ID_PLAN_NOT_FOUND예외() {
        // given — 전이도 소유자 스코프: 남의 계획은 존재 자체를 숨긴다
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when·then
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> planService.confirm(saved.id(), OTHER_OWNER, null)).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_NOT_FOUND);
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> planService.confirm(MISSING_ID, OWNER, null)).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_NOT_FOUND);
        assertThat(planService.getPlan(saved.id(), OWNER).status()).isEqualTo("DRAFT");
    }

    @Test
    void planSaveRequest_status패턴_PlanStatus이름과정합_driftGuard() throws NoSuchFieldException {
        // PlanSaveRequest의 @Pattern은 컴파일 상수 제약으로 리터럴을 쓴다 — 그 값이 PlanStatus의
        // 실제 이름(비종결 상태 DRAFT|CONFIRMED)과 어긋나면 여기서 깨진다. 종결 상태는 저장 요청으로
        // 들어올 수 없어야 하므로 regex에 포함되면 안 된다.
        jakarta.validation.constraints.Pattern pattern = PlanSaveRequest.class
                .getDeclaredField("status")
                .getAnnotation(jakarta.validation.constraints.Pattern.class);
        assertThat(pattern).isNotNull();
        java.util.Set<String> allowed = java.util.Set.of(pattern.regexp().split("\\|"));
        java.util.Set<String> nonTerminal = java.util.Arrays.stream(
                        com.delaynomore.backend.domain.plan.entity.PlanStatus.values())
                .filter(s -> !s.isTerminal())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(allowed).isEqualTo(nonTerminal);
    }

    // === 미완료 이월(carry-over) 도메인 액션 — 날짜 규칙(오늘 KST → 내일)은 서버 소유 ===

    private static final String TODAY = KstDates.today().toString();
    private static final String TOMORROW = KstDates.today().plusDays(1).toString();

    private PlanResponse createPlanWithTasks(Map<String, Object> tasks, Integer duration, String endDate) {
        PlanSaveRequest request = new PlanSaveRequest("토익 900", duration, 2, "완전 초보", tasks,
                null, null, TODAY, endDate, TODAY + "T00:00:00Z");
        return planService.create(request, OWNER, null);
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
        CarryOverResponse result = planService.carryOver(saved.id(), OWNER, "session-a");

        // then — 미완료만 내일로, savedAt 갱신, "이동" detail의 PLAN_UPDATED 발행
        assertThat(result.movedCount()).isEqualTo(2);
        assertThat(result.targetDate()).isEqualTo(TOMORROW);
        assertThat(result.plan().tasks().get(TODAY))
                .isEqualTo(List.of(Map.of("id", "t-2", "content", "문법 정리", "completed", true)));
        assertThat(result.plan().tasks().get(TOMORROW)).isEqualTo(List.of(
                Map.of("id", "t-1", "content", "단어 암기", "completed", false),
                Map.of("id", "t-3", "content", "듣기 연습", "completed", false)));
        assertThat(result.plan().savedAt()).isGreaterThanOrEqualTo(saved.savedAt());
        assertThat(auditEventService.getEvents(saved.id(), OWNER).get(0).detail())
                .isEqualTo("미완료 2건을 " + TOMORROW + "로 이동");
    }

    @Test
    void carryOver_종료일이오늘_endDate와duration하루연장() {
        // given — 마지막 날의 미완료를 넘기면 기간이 하루 늘어난다(프론트 기존 동작 그대로)
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "총정리", "completed", false)));
        PlanResponse saved = createPlanWithTasks(tasks, 1, TODAY);

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), OWNER, null);

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
        CarryOverResponse result = planService.carryOver(saved.id(), OWNER, null);

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
        CarryOverResponse result = planService.carryOver(saved.id(), OWNER, null);

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
        int eventCountBefore = auditEventService.getEvents(saved.id(), OWNER).size();

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), OWNER, null);

        // then — 정상 no-op: savedAt 보존(계획 불변), 이력도 없다
        assertThat(result.movedCount()).isZero();
        assertThat(result.plan().tasks()).isEqualTo(tasks);
        assertThat(result.plan().savedAt()).isEqualTo(saved.savedAt());
        assertThat(auditEventService.getEvents(saved.id(), OWNER)).hasSize(eventCountBefore);
    }

    @Test
    void carryOver_고정계획_정상이월_상태유지() {
        // given — 이월은 실행 단계 액션이라 고정(CONFIRMED) 후에도 허용된다(v0.14.1).
        // 고정 상태에서 오늘 미완료 1건을 만든다(기간은 오늘 하루 → 이월 시 하루 연장).
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "총정리", "completed", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 1, 2, "완전 초보", tasks,
                null, null, TODAY, TODAY, TODAY + "T00:00:00Z");
        PlanResponse saved = planService.create(request, OWNER, null);
        planService.confirm(saved.id(), OWNER, null);

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), OWNER, null);

        // then — 미완료가 내일로 이동, 기간 하루 연장, 상태는 CONFIRMED 그대로, 이력 발행
        assertThat(result.movedCount()).isEqualTo(1);
        assertThat(result.plan().tasks().get(TOMORROW)).isEqualTo(List.of(
                Map.of("id", "t-1", "content", "총정리", "completed", false)));
        assertThat(result.plan().endDate()).isEqualTo(TOMORROW);
        assertThat(result.plan().status()).isEqualTo("CONFIRMED");
        assertThat(auditEventService.getEvents(saved.id(), OWNER).get(0).detail())
                .isEqualTo("미완료 1건을 " + TOMORROW + "로 이동");
    }

    @Test
    void carryOver_어제미완료_이동대상아님() {
        // given — 이월 규칙은 "오늘 → 내일"만이다. 어제로 밀린 미완료는 건드리지 않는다.
        String yesterday = KstDates.today().minusDays(1).toString();
        Map<String, Object> tasks = Map.of(
                yesterday, List.of(Map.of("id", "t-0", "content", "어제 미완료", "completed", false)),
                TODAY, List.of(Map.of("id", "t-1", "content", "오늘 미완료", "completed", false)));
        PlanSaveRequest request = new PlanSaveRequest("토익 900", 3, 2, "완전 초보", tasks,
                null, null, yesterday, TOMORROW, yesterday + "T00:00:00Z");
        PlanResponse saved = planService.create(request, OWNER, null);

        // when
        CarryOverResponse result = planService.carryOver(saved.id(), OWNER, null);

        // then — 오늘 것만 내일로 이동하고, 어제 키는 그대로 남는다
        assertThat(result.movedCount()).isEqualTo(1);
        assertThat(result.plan().tasks().get(yesterday)).isEqualTo(List.of(
                Map.of("id", "t-0", "content", "어제 미완료", "completed", false)));
        assertThat(result.plan().tasks()).doesNotContainKey(TODAY);
        assertThat(result.plan().tasks().get(TOMORROW)).isEqualTo(List.of(
                Map.of("id", "t-1", "content", "오늘 미완료", "completed", false)));
    }

    @Test
    void carryOver_없는ID_PLAN_NOT_FOUND예외() {
        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.carryOver(MISSING_ID, OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void delete_존재하는계획_목록에서제거() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        planService.delete(saved.id(), OWNER, null);

        // then
        assertThat(planService.getPlans(OWNER)).isEmpty();
    }

    @Test
    void delete_없는ID_PLAN_NOT_FOUND예외() {
        // given — 아무것도 저장하지 않음

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.delete(MISSING_ID, OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    // === 소유자(닉네임) 격리 — 닉네임은 로그인 전 간이 계정 키, 남의 계획은 존재 자체를 숨긴다 ===

    @Test
    void getPlans_다른소유자_빈목록() {
        // given
        planService.create(request("토익 900"), OWNER, null);

        // when — 다른 닉네임으로 목록 조회
        List<PlanResponse> othersPlans = planService.getPlans(OTHER_OWNER);

        // then — 격리: 남의 계획은 목록에 나타나지 않는다
        assertThat(othersPlans).isEmpty();
        assertThat(planService.getPlans(OWNER)).hasSize(1);
    }

    @Test
    void getPlan_다른소유자_PLAN_NOT_FOUND예외() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.getPlan(saved.id(), OTHER_OWNER));

        // then — 403이 아니라 404: 존재 여부 자체를 숨긴다
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void update_다른소유자_PLAN_NOT_FOUND예외_저장소원상태유지() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class,
                () -> planService.update(saved.id(), request("탈취 시도"), OTHER_OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
        assertThat(planService.getPlan(saved.id(), OWNER).goalName()).isEqualTo("토익 900");
    }

    @Test
    void carryOver_다른소유자_PLAN_NOT_FOUND예외() {
        // given — 오늘 미완료가 있어도 남의 계획이면 이월 불가
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "단어 암기", "completed", false)));
        PlanResponse saved = createPlanWithTasks(tasks, 2, TOMORROW);

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.carryOver(saved.id(), OTHER_OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void delete_다른소유자_PLAN_NOT_FOUND예외_계획유지() {
        // given
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);

        // when
        BusinessException exception = catchThrowableOfType(
                BusinessException.class, () -> planService.delete(saved.id(), OTHER_OWNER, null));

        // then — 삭제되지 않고 소유자에게는 그대로 보인다
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
        assertThat(planService.getPlans(OWNER)).hasSize(1);
    }

    @Test
    void getEvents_다른소유자_빈목록() {
        // given — 소유자에게는 PLAN_CREATED 이력이 보인다
        PlanResponse saved = planService.create(request("토익 900"), OWNER, null);
        assertThat(auditEventService.getEvents(saved.id(), OWNER)).isNotEmpty();

        // when — 다른 닉네임으로 이력 조회

        // then — 404가 아니라 빈 목록(기존 "모르는 planId" 계약과 동일)
        assertThat(auditEventService.getEvents(saved.id(), OTHER_OWNER)).isEmpty();
    }
}
