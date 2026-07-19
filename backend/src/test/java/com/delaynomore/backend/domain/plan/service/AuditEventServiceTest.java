package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.dto.ReflectionSaveRequest;
import com.delaynomore.backend.domain.plan.entity.AuditEvent;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 인메모리 저장소라 Mock 없이 실제 Repository를 주입해, 변경 서비스(PlanService·ReflectionService)를
// 통과한 변경이 어떤 이벤트로 기록되는지(diff 분류 포함)를 함께 검증한다.
class AuditEventServiceTest {

    private final PlanRepository planRepository = new PlanRepository();
    private final ReflectionRepository reflectionRepository = new ReflectionRepository();
    private final AuditEventRepository auditEventRepository = new AuditEventRepository();
    private final AuditEventService auditEventService = new AuditEventService(auditEventRepository);
    private final PlanService planService = new PlanService(planRepository, reflectionRepository, auditEventService);
    private final ReflectionService reflectionService =
            new ReflectionService(planRepository, reflectionRepository, auditEventService);

    private static Map<String, Object> task(String id, String content, boolean completed) {
        return Map.of("id", id, "content", content, "completed", completed);
    }

    private static PlanSaveRequest request(String goalName, Map<String, Object> tasks, String status,
                                           Integer duration, String endDate) {
        return new PlanSaveRequest(goalName, duration, 2, "완전 초보", tasks,
                status, null, "2026-07-16", endDate, "2026-07-16T00:00:00Z");
    }

    private static PlanSaveRequest request(String goalName, Map<String, Object> tasks, String status) {
        return request(goalName, tasks, status, 3, "2026-07-18");
    }

    private static final Map<String, Object> BASE_TASKS = Map.of(
            "2026-07-16", List.of(task("t-1", "단어 암기", false), task("t-2", "문법 정리", true)),
            "2026-07-17", List.of(task("t-3", "듣기 연습", false)));

    private PlanResponse createBasePlan() {
        return planService.create(request("토익 900", BASE_TASKS, null), "session-a");
    }

    private List<AuditEventResponse> events(long planId) {
        return auditEventService.getEvents(planId);
    }

    @Test
    void create_계획생성_PLAN_CREATED기록및세션ID보존() {
        // when
        PlanResponse saved = createBasePlan();

        // then
        List<AuditEventResponse> events = events(saved.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("PLAN_CREATED");
        assertThat(events.get(0).sessionId()).isEqualTo("session-a");
        assertThat(events.get(0).planId()).isEqualTo(saved.id());
    }

    @Test
    void update_고정PUT_PLAN_CONFIRMED기록() {
        // given
        PlanResponse saved = createBasePlan();

        // when — 프론트의 "계획 저장(고정)"은 status만 바뀐 전체 PUT으로 온다
        planService.update(saved.id(), request("토익 900", BASE_TASKS, "CONFIRMED"), "session-a");

        // then
        assertThat(events(saved.id()).get(0).type()).isEqualTo("PLAN_CONFIRMED");
    }

    @Test
    void update_완료토글만_TASK_COMPLETED기록_detail에내용과날짜() {
        // given
        PlanResponse saved = createBasePlan();
        Map<String, Object> toggled = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", true), task("t-2", "문법 정리", true)),
                "2026-07-17", List.of(task("t-3", "듣기 연습", false)));

        // when
        planService.update(saved.id(), request("토익 900", toggled, null), "session-b");

        // then — 토글만 있는 PUT은 PLAN_UPDATED 없이 TASK_COMPLETED 한 건만
        List<AuditEventResponse> events = events(saved.id());
        assertThat(events).hasSize(2); // PLAN_CREATED + TASK_COMPLETED
        assertThat(events.get(0).type()).isEqualTo("TASK_COMPLETED");
        assertThat(events.get(0).detail()).isEqualTo("\"단어 암기\" · 2026-07-16");
        assertThat(events.get(0).sessionId()).isEqualTo("session-b");
    }

    @Test
    void update_고정계획_완료토글_가드통과후TASK_COMPLETED기록() {
        // given — 고정(CONFIRMED)된 계획 (서버 가드는 완료 토글만 허용)
        PlanResponse saved = createBasePlan();
        planService.update(saved.id(), request("토익 900", BASE_TASKS, "CONFIRMED"), "session-a");
        Map<String, Object> toggled = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", true), task("t-2", "문법 정리", true)),
                "2026-07-17", List.of(task("t-3", "듣기 연습", false)));

        // when — 토글만 있는 PUT은 가드를 통과하고 감사 흐름도 기존대로 동작해야 한다
        planService.update(saved.id(), request("토익 900", toggled, "CONFIRMED"), "session-b");

        // then
        List<AuditEventResponse> events = events(saved.id());
        assertThat(events.get(0).type()).isEqualTo("TASK_COMPLETED");
        assertThat(events.get(0).detail()).isEqualTo("\"단어 암기\" · 2026-07-16");
    }

    @Test
    void update_한PUT에여러토글_뒤집힌개수만큼기록() {
        // given — 디바운스(600ms) 배칭으로 완료 1건 + 해제 1건이 한 PUT에 몰린 상황
        PlanResponse saved = createBasePlan();
        Map<String, Object> toggled = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", true), task("t-2", "문법 정리", false)),
                "2026-07-17", List.of(task("t-3", "듣기 연습", false)));

        // when
        planService.update(saved.id(), request("토익 900", toggled, null), null);

        // then
        List<String> types = events(saved.id()).stream().map(AuditEventResponse::type).toList();
        assertThat(types).containsExactlyInAnyOrder("TASK_COMPLETED", "TASK_REOPENED", "PLAN_CREATED");
    }

    @Test
    void update_내용변경_PLAN_UPDATED기록() {
        // given
        PlanResponse saved = createBasePlan();

        // when — 목표 이름 변경(구조 변경, 이월 아님)
        planService.update(saved.id(), request("토익 950", BASE_TASKS, null), null);

        // then
        AuditEventResponse latest = events(saved.id()).get(0);
        assertThat(latest.type()).isEqualTo("PLAN_UPDATED");
        assertThat(latest.detail()).isEqualTo("계획 내용 변경");
    }

    @Test
    void update_이월패턴_PLAN_UPDATED에이동detail() {
        // given
        PlanResponse saved = createBasePlan();
        // 2026-07-16의 미완료 t-1을 ID 보존한 채 2026-07-17 뒤에 붙인 이월 PUT(완료 t-2는 잔류)
        Map<String, Object> carried = Map.of(
                "2026-07-16", List.of(task("t-2", "문법 정리", true)),
                "2026-07-17", List.of(task("t-3", "듣기 연습", false), task("t-1", "단어 암기", false)));

        // when
        planService.update(saved.id(), request("토익 900", carried, null), null);

        // then
        AuditEventResponse latest = events(saved.id()).get(0);
        assertThat(latest.type()).isEqualTo("PLAN_UPDATED");
        assertThat(latest.detail()).isEqualTo("미완료 1건을 2026-07-17로 이동");
    }

    @Test
    void update_이월로마지막날넘김_기간연장허용() {
        // given — 마지막 날(2026-07-18)의 미완료를 다음 날로 넘기면 endDate/duration이 함께 늘어난다
        Map<String, Object> tasks = Map.of("2026-07-18", List.of(task("t-9", "총정리", false)));
        PlanResponse saved = planService.create(request("막판 계획", tasks, null), null);
        Map<String, Object> carried = Map.of("2026-07-19", List.of(task("t-9", "총정리", false)));

        // when
        planService.update(saved.id(), request("막판 계획", carried, null, 4, "2026-07-19"), null);

        // then — endDate·duration 연장은 이월 감지를 깨지 않는다
        assertThat(events(saved.id()).get(0).detail()).isEqualTo("미완료 1건을 2026-07-19로 이동");
    }

    @Test
    void update_이월과유사하지만완료항목이동_generic_detail() {
        // given — 완료된 t-2가 함께 이동하면 이월(미완료만)이 아니다
        PlanResponse saved = createBasePlan();
        Map<String, Object> moved = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", false)),
                "2026-07-17", List.of(task("t-3", "듣기 연습", false), task("t-2", "문법 정리", true)));

        // when
        planService.update(saved.id(), request("토익 900", moved, null), null);

        // then
        AuditEventResponse latest = events(saved.id()).get(0);
        assertThat(latest.type()).isEqualTo("PLAN_UPDATED");
        assertThat(latest.detail()).isEqualTo("계획 내용 변경");
    }

    @Test
    void update_동일내용PUT_아무것도기록안함() {
        // given
        PlanResponse saved = createBasePlan();

        // when — 내용이 완전히 같은 no-op PUT(프론트는 억제하지만 curl로는 가능)
        planService.update(saved.id(), request("토익 900", BASE_TASKS, null), null);

        // then — PLAN_CREATED 외에 아무것도 늘지 않는다
        assertThat(events(saved.id())).hasSize(1);
    }

    @Test
    void update_토글과내용변경혼합_TASK와PLAN_UPDATED모두기록() {
        // given — 토글 + 목표 이름 변경이 한 PUT에 섞임
        PlanResponse saved = createBasePlan();
        Map<String, Object> mixed = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", true), task("t-2", "문법 정리", true)),
                "2026-07-17", List.of(task("t-3", "듣기 연습", false)));

        // when
        planService.update(saved.id(), request("토익 950", mixed, null), null);

        // then
        List<String> types = events(saved.id()).stream().map(AuditEventResponse::type).toList();
        assertThat(types).containsExactly("PLAN_UPDATED", "TASK_COMPLETED", "PLAN_CREATED");
    }

    @Test
    void delete_계획삭제_PLAN_DELETED기록및이력보존조회가능() {
        // given
        PlanResponse saved = createBasePlan();

        // when
        planService.delete(saved.id(), "session-a");

        // then — 계획이 사라져도 이력은 남는다("언제 삭제됐는가"에 답해야 하므로 캐스케이드 없음)
        List<AuditEventResponse> events = events(saved.id());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("PLAN_DELETED");
        assertThat(events.get(0).detail()).contains("토익 900");
    }

    @Test
    void reflection_저장_REFLECTION_SAVED기록() {
        // given — 회고는 오늘(Asia/Seoul) 날짜만 허용되므로 오늘 키로 계획을 만든다
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        Map<String, Object> tasks = Map.of(today, List.of(
                task("t-1", "단어 암기", true), task("t-2", "문법 정리", false)));
        PlanResponse saved = planService.create(request("토익 900", tasks, null), null);

        // when
        reflectionService.save(saved.id(), today, new ReflectionSaveRequest("HARD", "NOT_ENOUGH_TIME"), "session-c");

        // then
        AuditEventResponse latest = events(saved.id()).get(0);
        assertThat(latest.type()).isEqualTo("REFLECTION_SAVED");
        assertThat(latest.detail()).isEqualTo(today + " 회고 저장 (1/2 완료)");
        assertThat(latest.sessionId()).isEqualTo("session-c");
    }

    @Test
    void getEvents_최신순정렬() {
        // given
        PlanResponse saved = createBasePlan();
        planService.update(saved.id(), request("토익 950", BASE_TASKS, null), null);

        // when
        List<AuditEventResponse> events = events(saved.id());

        // then — id 내림차순(최신 먼저)
        assertThat(events).hasSize(2);
        assertThat(events.get(0).id()).isGreaterThan(events.get(1).id());
        assertThat(events.get(0).type()).isEqualTo("PLAN_UPDATED");
        assertThat(events.get(1).type()).isEqualTo("PLAN_CREATED");
    }

    @Test
    void record_세션ID_공백은null_초과분은절단() {
        // given
        PlanResponse saved = planService.create(request("토익 900", BASE_TASKS, null), "   ");
        planService.update(saved.id(), request("토익 950", BASE_TASKS, null), "x".repeat(80));

        // then — 공백뿐인 헤더는 null(알 수 없음), 임의 장문 헤더는 64자로 절단해 저장
        List<AuditEventResponse> events = events(saved.id());
        assertThat(events.get(1).sessionId()).isNull();
        assertThat(events.get(0).sessionId()).isEqualTo("x".repeat(64));
    }

    @Test
    void append_상한초과_가장오래된것부터축출() {
        // given — 저장소 직접 검증: 상한을 넘겨 append하면 오래된 이벤트가 밀려난다
        for (int i = 0; i < 1010; i++) {
            auditEventRepository.append(new AuditEvent(0, 1L, "PLAN_UPDATED", null, null, "t" + i));
        }

        // then
        assertThat(auditEventRepository.count()).isEqualTo(1000);
        List<AuditEvent> remaining = auditEventRepository.findAllByPlanId(1L);
        assertThat(remaining.get(remaining.size() - 1).id()).isEqualTo(11); // 1~10번은 축출됨
    }
}
