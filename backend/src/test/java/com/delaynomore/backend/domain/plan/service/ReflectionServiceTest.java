package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.dto.ReflectionResponse;
import com.delaynomore.backend.domain.plan.dto.ReflectionSaveRequest;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// 인메모리 저장소라 Mock 없이 실제 Repository를 주입해 Service+Repository를 함께 검증한다.
// "오늘"은 서비스와 같은 기준(Asia/Seoul)으로 만들어, UTC 컨테이너에서도 날짜가 어긋나지 않는다.
class ReflectionServiceTest {

    private static final long MISSING_ID = 999L;
    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();

    // 소유자(닉네임) 스코프 — 회고는 계획의 소유권을 상속하므로 테스트 기본 소유자를 고정한다.
    private static final String OWNER = "guest-a";
    private static final String OTHER_OWNER = "guest-b";

    private final PlanRepository planRepository = new PlanRepository();
    private final ReflectionRepository reflectionRepository = new ReflectionRepository();
    private final AuditEventService auditEventService =
            new AuditEventService(new AuditEventRepository());
    private final PlanService planService = new PlanService(planRepository, reflectionRepository, auditEventService);
    private final ReflectionService reflectionService = new ReflectionService(planRepository, reflectionRepository, auditEventService);

    // 오늘 5개 중 3개 완료된 계획을 보관한다(status 지정 가능 — CONFIRMED 회고 허용 검증용).
    private PlanResponse savePlan(String status) {
        Map<String, Object> tasks = Map.of(TODAY, List.of(
                Map.of("id", "t-1", "content", "단어 암기", "completed", true),
                Map.of("id", "t-2", "content", "문법 정리", "completed", true),
                Map.of("id", "t-3", "content", "듣기 연습", "completed", true),
                Map.of("id", "t-4", "content", "독해 문제", "completed", false),
                Map.of("id", "t-5", "content", "오답 노트", "completed", false)));
        return planService.create(new PlanSaveRequest("토익 900", 3, 2, "완전 초보", tasks,
                status, null, TODAY, TODAY, TODAY + "T00:00:00Z"), OWNER, null);
    }

    private ReflectionSaveRequest request() {
        return new ReflectionSaveRequest("HARD", "NOT_ENOUGH_TIME");
    }

    @Test
    void save_정상요청_완료개수는서버가재계산() {
        // given
        PlanResponse plan = savePlan(null);

        // when
        ReflectionResponse saved = reflectionService.save(plan.id(), TODAY, request(), OWNER, null);

        // then — 클라이언트는 개수를 보내지 않았지만 plan.tasks에서 3/5가 계산된다
        assertThat(saved.planId()).isEqualTo(plan.id());
        assertThat(saved.date()).isEqualTo(TODAY);
        assertThat(saved.completedCount()).isEqualTo(3);
        assertThat(saved.totalCount()).isEqualTo(5);
        assertThat(saved.difficulty()).isEqualTo("HARD");
        assertThat(saved.reason()).isEqualTo("NOT_ENOUGH_TIME");
        assertThat(saved.createdAt()).isEqualTo(saved.updatedAt()); // 최초 저장
    }

    @Test
    void save_재저장_createdAt보존및중복없음() throws InterruptedException {
        // given
        PlanResponse plan = savePlan(null);
        ReflectionResponse first = reflectionService.save(plan.id(), TODAY, request(), OWNER, null);

        // when — 잠깐 기다려 updatedAt이 확실히 달라지게 한 뒤 다른 선택으로 재저장
        Thread.sleep(5);
        ReflectionResponse second = reflectionService.save(plan.id(), TODAY,
                new ReflectionSaveRequest("NORMAL", "AS_PLANNED"), OWNER, null);

        // then — 업서트: 최초 저장 시각은 보존되고, 목록에 중복이 생기지 않는다
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.updatedAt()).isNotEqualTo(first.updatedAt());
        assertThat(second.difficulty()).isEqualTo("NORMAL");
        assertThat(reflectionService.getAll(plan.id(), OWNER)).hasSize(1);
    }

    @Test
    void save_오늘할일없는계획_0대0으로저장() {
        // given — tasks에 오늘 키가 없는 계획
        Map<String, Object> tasks = Map.of("2000-01-01", List.of(
                Map.of("id", "t-1", "content", "지난 할 일", "completed", false)));
        PlanResponse plan = planService.create(new PlanSaveRequest("옛 계획", 1, 1, "완전 초보", tasks,
                null, null, null, null, null), OWNER, null);

        // when
        ReflectionResponse saved = reflectionService.save(plan.id(), TODAY, request(), OWNER, null);

        // then
        assertThat(saved.completedCount()).isZero();
        assertThat(saved.totalCount()).isZero();
    }

    @Test
    void save_오늘이아닌날짜_REFLECTION_DATE_NOT_TODAY예외() {
        // given
        PlanResponse plan = savePlan(null);

        // when
        BusinessException exception = catchThrowableOfType(BusinessException.class,
                () -> reflectionService.save(plan.id(), "2099-01-01", request(), OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFLECTION_DATE_NOT_TODAY);
    }

    @Test
    void save_날짜형식오류_REFLECTION_DATE_INVALID예외() {
        // given
        PlanResponse plan = savePlan(null);

        // when
        BusinessException exception = catchThrowableOfType(BusinessException.class,
                () -> reflectionService.save(plan.id(), "abc", request(), OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFLECTION_DATE_INVALID);
    }

    @Test
    void save_없는계획_PLAN_NOT_FOUND예외() {
        // given — 아무 계획도 저장하지 않음

        // when
        BusinessException exception = catchThrowableOfType(BusinessException.class,
                () -> reflectionService.save(MISSING_ID, TODAY, request(), OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void save_고정된계획도회고저장가능() {
        // given — CONFIRMED는 대화 수정만 막고, 완료 체크·회고는 계속 허용된다
        PlanResponse plan = savePlan("CONFIRMED");

        // when
        ReflectionResponse saved = reflectionService.save(plan.id(), TODAY, request(), OWNER, null);

        // then
        assertThat(saved.completedCount()).isEqualTo(3);
    }

    @Test
    void get_회고미존재_REFLECTION_NOT_FOUND예외() {
        // given — 계획은 있지만 회고는 저장 전
        PlanResponse plan = savePlan(null);

        // when
        BusinessException exception = catchThrowableOfType(BusinessException.class,
                () -> reflectionService.get(plan.id(), TODAY, OWNER));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REFLECTION_NOT_FOUND);
    }

    @Test
    void get_저장된회고_그대로반환() {
        // given
        PlanResponse plan = savePlan(null);
        ReflectionResponse saved = reflectionService.save(plan.id(), TODAY, request(), OWNER, null);

        // when
        ReflectionResponse found = reflectionService.get(plan.id(), TODAY, OWNER);

        // then
        assertThat(found).isEqualTo(saved);
    }

    @Test
    void getAll_없는계획_PLAN_NOT_FOUND예외() {
        // when
        BusinessException exception = catchThrowableOfType(BusinessException.class,
                () -> reflectionService.getAll(MISSING_ID, OWNER));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void delete_계획삭제시_회고도캐스케이드삭제() {
        // given
        PlanResponse plan = savePlan(null);
        reflectionService.save(plan.id(), TODAY, request(), OWNER, null);

        // when
        planService.delete(plan.id(), OWNER, null);

        // then — 저장소에서 직접 확인(계획이 없어 서비스 조회는 PLAN_NOT_FOUND가 되므로)
        assertThat(reflectionRepository.findAllByPlanId(plan.id())).isEmpty();
    }

    // === 소유자(닉네임) 격리 — 회고는 계획 소유권을 상속, 남의 계획의 회고는 존재 자체를 숨긴다 ===

    @Test
    void save_다른소유자_PLAN_NOT_FOUND예외() {
        // given
        PlanResponse plan = savePlan(null);

        // when
        BusinessException exception = catchThrowableOfType(BusinessException.class,
                () -> reflectionService.save(plan.id(), TODAY, request(), OTHER_OWNER, null));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void get_다른소유자_PLAN_NOT_FOUND예외() {
        // given — 소유자가 회고를 저장해 뒀다
        PlanResponse plan = savePlan(null);
        reflectionService.save(plan.id(), TODAY, request(), OWNER, null);

        // when
        BusinessException exception = catchThrowableOfType(BusinessException.class,
                () -> reflectionService.get(plan.id(), TODAY, OTHER_OWNER));

        // then — REFLECTION_NOT_FOUND가 아니라 PLAN_NOT_FOUND(계획 존재 자체를 숨긴다)
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }

    @Test
    void getAll_다른소유자_PLAN_NOT_FOUND예외() {
        // given
        PlanResponse plan = savePlan(null);
        reflectionService.save(plan.id(), TODAY, request(), OWNER, null);

        // when
        BusinessException exception = catchThrowableOfType(BusinessException.class,
                () -> reflectionService.getAll(plan.id(), OTHER_OWNER));

        // then
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PLAN_NOT_FOUND);
    }
}
