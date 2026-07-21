package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.dto.ReflectionSaveRequest;
import com.delaynomore.backend.domain.plan.entity.AuditEvent;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryAuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryPlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.domain.plan.repository.InMemoryReflectionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 인메모리 저장소라 Mock 없이 실제 Repository를 주입해, 변경 서비스(PlanService·ReflectionService)를
// 통과한 변경이 어떤 이벤트로 기록되는지(diff 분류 포함)를 함께 검증한다.
class AuditEventServiceTest {

    // 소유자(닉네임) 스코프 — 이력 조회는 계획 소유자 확인을 거치므로 테스트 기본 소유자를 고정한다.
    private static final String OWNER = "guest-a";
    private static final String OTHER_OWNER = "guest-b";

    private final PlanRepository planRepository = new InMemoryPlanRepository();
    private final ReflectionRepository reflectionRepository = new InMemoryReflectionRepository();
    private final AuditEventRepository auditEventRepository = new InMemoryAuditEventRepository();
    private final AuditEventService auditEventService =
            new AuditEventService(auditEventRepository);
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
        return planService.create(request("토익 900", BASE_TASKS, null), OWNER, "session-a");
    }

    private List<AuditEventResponse> events(long planId) {
        return auditEventService.getEvents(planId, OWNER);
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
        planService.update(saved.id(), request("토익 900", BASE_TASKS, "CONFIRMED"), OWNER, "session-a");

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
        planService.update(saved.id(), request("토익 900", toggled, null), OWNER, "session-b");

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
        planService.update(saved.id(), request("토익 900", BASE_TASKS, "CONFIRMED"), OWNER, "session-a");
        Map<String, Object> toggled = Map.of(
                "2026-07-16", List.of(task("t-1", "단어 암기", true), task("t-2", "문법 정리", true)),
                "2026-07-17", List.of(task("t-3", "듣기 연습", false)));

        // when — 토글만 있는 PUT은 가드를 통과하고 감사 흐름도 기존대로 동작해야 한다
        planService.update(saved.id(), request("토익 900", toggled, "CONFIRMED"), OWNER, "session-b");

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
        planService.update(saved.id(), request("토익 900", toggled, null), OWNER, null);

        // then
        List<String> types = events(saved.id()).stream().map(AuditEventResponse::type).toList();
        assertThat(types).containsExactlyInAnyOrder("TASK_COMPLETED", "TASK_REOPENED", "PLAN_CREATED");
    }

    @Test
    void update_내용변경_PLAN_UPDATED기록() {
        // given
        PlanResponse saved = createBasePlan();

        // when — 목표 이름 변경(구조 변경, 이월 아님)
        planService.update(saved.id(), request("토익 950", BASE_TASKS, null), OWNER, null);

        // then
        AuditEventResponse latest = events(saved.id()).get(0);
        assertThat(latest.type()).isEqualTo("PLAN_UPDATED");
        assertThat(latest.detail()).isEqualTo("계획 내용 변경");
    }

    @Test
    void update_항목이동PUT_generic_detail() {
        // given — 항목을 다른 날짜로 옮긴 PUT. 이월은 도메인 액션(recordCarryOver)이 직접
        // 발행하므로 PUT 경로의 구조 변경은 항상 일반 detail이다(이월 역감지 제거됨).
        PlanResponse saved = createBasePlan();
        Map<String, Object> moved = Map.of(
                "2026-07-16", List.of(task("t-2", "문법 정리", true)),
                "2026-07-17", List.of(task("t-3", "듣기 연습", false), task("t-1", "단어 암기", false)));

        // when
        planService.update(saved.id(), request("토익 900", moved, null), OWNER, null);

        // then
        AuditEventResponse latest = events(saved.id()).get(0);
        assertThat(latest.type()).isEqualTo("PLAN_UPDATED");
        assertThat(latest.detail()).isEqualTo("계획 내용 변경");
    }

    @Test
    void recordCarryOver_PLAN_UPDATED에이동detail() {
        // given
        PlanResponse saved = createBasePlan();

        // when — 이월 도메인 액션이 수행 직후 직접 발행하는 경로
        auditEventService.recordCarryOver(saved.id(), OWNER, 2, "2026-07-17", "session-a");

        // then — detail 형식은 예전 역감지 시절과 동일(이력 화면 연속성)
        AuditEventResponse latest = events(saved.id()).get(0);
        assertThat(latest.type()).isEqualTo("PLAN_UPDATED");
        assertThat(latest.detail()).isEqualTo("미완료 2건을 2026-07-17로 이동");
        assertThat(latest.sessionId()).isEqualTo("session-a");
    }

    @Test
    void update_동일내용PUT_아무것도기록안함() {
        // given
        PlanResponse saved = createBasePlan();

        // when — 내용이 완전히 같은 no-op PUT(프론트는 억제하지만 curl로는 가능)
        planService.update(saved.id(), request("토익 900", BASE_TASKS, null), OWNER, null);

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
        planService.update(saved.id(), request("토익 950", mixed, null), OWNER, null);

        // then
        List<String> types = events(saved.id()).stream().map(AuditEventResponse::type).toList();
        assertThat(types).containsExactly("PLAN_UPDATED", "TASK_COMPLETED", "PLAN_CREATED");
    }

    @Test
    void delete_계획삭제_소유자는PLAN_DELETED포함이력조회가능() {
        // given
        PlanResponse saved = createBasePlan();

        // when
        planService.delete(saved.id(), OWNER, "session-a");

        // then — 이벤트에 소유자가 박혀 있어, 계획이 사라져도 소유자는 이력을 그대로 조회한다
        // ("언제 삭제됐는가" 계약 복원). 캐스케이드 삭제 없음.
        List<AuditEventResponse> events = events(saved.id());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("PLAN_DELETED");
        assertThat(events.get(0).detail()).contains("토익 900");
    }

    @Test
    void getEvents_다른소유자_빈목록_삭제후에도유지() {
        // given
        PlanResponse saved = createBasePlan();
        assertThat(events(saved.id())).isNotEmpty();

        // when / then — 다른 소유자는 404가 아니라 빈 목록(존재 여부 은닉)
        assertThat(auditEventService.getEvents(saved.id(), OTHER_OWNER)).isEmpty();

        // 계획을 삭제해도 타인에게는 여전히 빈 목록, 소유자에게는 보인다
        planService.delete(saved.id(), OWNER, "session-a");
        assertThat(auditEventService.getEvents(saved.id(), OTHER_OWNER)).isEmpty();
        assertThat(auditEventService.getEvents(saved.id(), OWNER)).isNotEmpty();
    }

    @Test
    void reflection_저장_REFLECTION_SAVED기록() {
        // given — 회고는 오늘(Asia/Seoul) 날짜만 허용되므로 오늘 키로 계획을 만든다
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        Map<String, Object> tasks = Map.of(today, List.of(
                task("t-1", "단어 암기", true), task("t-2", "문법 정리", false)));
        PlanResponse saved = planService.create(request("토익 900", tasks, null), OWNER, null);

        // when
        reflectionService.save(saved.id(), today, new ReflectionSaveRequest("HARD", "NOT_ENOUGH_TIME"), OWNER, "session-c");

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
        planService.update(saved.id(), request("토익 950", BASE_TASKS, null), OWNER, null);

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
        PlanResponse saved = planService.create(request("토익 900", BASE_TASKS, null), OWNER, "   ");
        planService.update(saved.id(), request("토익 950", BASE_TASKS, null), OWNER, "x".repeat(80));

        // then — 공백뿐인 헤더는 null(알 수 없음), 임의 장문 헤더는 64자로 절단해 저장
        List<AuditEventResponse> events = events(saved.id());
        assertThat(events.get(1).sessionId()).isNull();
        assertThat(events.get(0).sessionId()).isEqualTo("x".repeat(64));
    }

    @Test
    void append_상한초과_가장오래된것부터축출() {
        // given — 저장소 직접 검증: 상한을 넘겨 append하면 오래된 이벤트가 밀려난다
        for (int i = 0; i < 1010; i++) {
            auditEventRepository.append(new AuditEvent(0, 1L, OWNER, "PLAN_UPDATED", null, null, "t" + i));
        }

        // then
        assertThat(auditEventRepository.count()).isEqualTo(1000);
        List<AuditEvent> remaining = auditEventRepository.findAllByPlanIdAndOwner(1L, OWNER);
        assertThat(remaining.get(remaining.size() - 1).id()).isEqualTo(11); // 1~10번은 축출됨
    }
}
