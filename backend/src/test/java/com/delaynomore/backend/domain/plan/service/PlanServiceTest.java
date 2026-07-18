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

    private final PlanService planService = new PlanService(new PlanRepository(), new ReflectionRepository(),
            new AuditEventService(new AuditEventRepository()));

    private PlanSaveRequest request(String goalName) {
        Map<String, Object> tasks = Map.of(
                "2026-07-16", List.of(Map.of("id", "t-1", "content", "단어 암기", "completed", false)));
        return new PlanSaveRequest(goalName, 3, 2, "완전 초보", tasks,
                null, null, "2026-07-16", "2026-07-18", "2026-07-16T00:00:00Z");
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
