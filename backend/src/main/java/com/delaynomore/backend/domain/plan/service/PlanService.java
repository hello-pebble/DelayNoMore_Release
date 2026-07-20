package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.CarryOverResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.domain.plan.support.PlanDates;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PlanService {

    // 공유 데모 저장소 폭주 방지 — 익명 방문자 전원이 함께 쓰는 휘발성 보관함이므로 개수 상한을 둔다.
    private static final int MAX_PLANS = 50;

    private final PlanRepository planRepository;
    private final ReflectionRepository reflectionRepository;
    private final AuditEventService auditEventService;

    // synchronized: 한도 검사(count)와 저장(save)을 원자적으로 묶는다. 공유 데모 저장소라
    // 여러 방문자가 동시에 생성하면 각자 검사를 통과한 뒤 저장해 상한 50건을 넘길 수 있는데
    // (TOCTOU), 생성 경로를 직렬화해 이를 막는다. 생성만 개수를 늘리므로 create만 잠그면 충분하다.
    public synchronized PlanResponse create(PlanSaveRequest request, String sessionId) {
        if (planRepository.count() >= MAX_PLANS) {
            throw new BusinessException(ErrorCode.PLAN_LIMIT_EXCEEDED);
        }
        // 날짜 규칙은 서버 소유 — startDate는 tasks 최초 날짜 키로, duration은 [startDate, endDate]
        // 범위로 산출한다(클라이언트가 보낸 startDate/duration은 무시). endDate는 @ValidPlanDates가 검증.
        String startDate = PlanDates.minTaskKey(request.tasks());
        int duration = PlanDates.spanDays(startDate, request.endDate());
        Plan saved = planRepository.save(
                request.toPlan(null, System.currentTimeMillis(), startDate, duration));
        auditEventService.recordPlanCreated(saved, sessionId);
        return PlanResponse.from(saved);
    }

    public List<PlanResponse> getPlans() {
        return planRepository.findAll().stream()
                .map(PlanResponse::from)
                .toList();
    }

    public PlanResponse getPlan(long id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
        return PlanResponse.from(plan);
    }

    public PlanResponse update(long id, PlanSaveRequest request, String sessionId) {
        // startDate는 생성 시 산출된 뒤 불변이라 원자 구간 밖에서 읽어도 레이스가 없다(어떤 동시
        // 쓰기도 startDate를 바꾸지 못한다). 이 값을 보존하고 duration만 [startDate, endDate]로 재산출
        // 한다 — 클라이언트가 보낸 startDate/duration은 무시(규칙 소유권은 서버). DB 이관 시에도 이
        // 불변식(startDate 고정)을 유지해야 이 비원자 읽기가 안전하다.
        Plan current = planRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
        String startDate = current.startDate();
        int duration = PlanDates.spanDays(startDate, request.endDate());
        Plan updated = request.toPlan(id, System.currentTimeMillis(), startDate, duration);
        // 고정 가드는 저장소의 키 단위 원자 구간 안에서 실행된다 — 조회·검사·교체 사이에 다른
        // 쓰기(예: 다른 브라우저의 고정)가 끼어들 수 없어 check-then-act 레이스가 없다.
        Plan previous = planRepository.update(updated,
                c -> assertUnlockedOrToggleOnly(c, updated));
        if (previous == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        // 모든 변경(내용 수정·고정·완료 토글)이 이 PUT 하나로 들어오므로, 이전 상태와 diff해
        // 이벤트 종류(PLAN_CONFIRMED/TASK_*/PLAN_UPDATED)를 서버가 판별·기록한다.
        auditEventService.recordPlanUpdated(previous, updated, sessionId);
        return PlanResponse.from(updated);
    }

    // 고정(CONFIRMED)된 계획은 완료 체크(completed 토글)만 허용한다 — 예전엔 프론트만 지키던
    // 규칙이라 curl 등 직접 호출로 우회할 수 있었다. 구조 변경 판정은 변경 이력과 같은 기준
    // (PlanTaskDiff.hasStructuralChange: 스칼라 6종 + 날짜별 항목키→content 뷰, completed 제외)을
    // 공유한다. createdAt은 표시용 메타라 가드 대상이 아니다. 삭제는 계속 허용한다(프론트 탈출구).
    private static void assertUnlockedOrToggleOnly(Plan current, Plan incoming) {
        if (!current.isConfirmed()) {
            return; // DRAFT는 자유 수정 — DRAFT→CONFIRMED 고정(수정 동반 포함)도 이 분기로 허용된다.
        }
        boolean rolledBack = !incoming.isConfirmed();
        boolean confirmedAtChanged = !Objects.equals(current.confirmedAt(), incoming.confirmedAt());
        if (rolledBack || confirmedAtChanged || PlanTaskDiff.hasStructuralChange(
                current, incoming,
                PlanTaskDiff.parseTasks(current.tasks()), PlanTaskDiff.parseTasks(incoming.tasks()))) {
            throw new BusinessException(ErrorCode.PLAN_LOCKED);
        }
    }

    // 미완료 이월 도메인 액션 — 오늘(KST) 미완료를 내일로 옮긴다. 예전엔 프론트가 계산해 PUT으로
    // 보내고 서버가 diff에서 역감지했지만, 이제 날짜 규칙과 연산 모두 서버가 소유한다.
    // 가드·연산은 저장소의 키 단위 원자 구간(mutate) 안에서 실행돼 다른 쓰기와 경합하지 않는다.
    public CarryOverResponse carryOver(long id, String sessionId) {
        String fromDate = KstDates.today().toString();
        String toDate = KstDates.today().plusDays(1).toString();
        int[] movedCount = new int[1];
        Plan updated = planRepository.mutate(id, current -> {
            // 이월은 구조 변경(항목 이동·기간 연장)이므로 고정 계획에는 PUT 가드와 같은 판정을 적용한다.
            if (current.isConfirmed()) {
                throw new BusinessException(ErrorCode.PLAN_LOCKED);
            }
            PlanCarryOver.Result result = PlanCarryOver.apply(current.tasks(), fromDate, toDate);
            movedCount[0] = result.movedCount();
            if (result.movedCount() == 0) {
                return current; // 이월할 미완료 없음 — 계획 불변(savedAt 보존), 이력도 없다.
            }
            // 내일이 기간 밖이면 종료일을 내일로 연장한다(프론트 기존 동작 그대로). duration은
            // create/update와 같은 규칙([startDate, endDate] span)으로 산출해 계산을 일원화한다.
            boolean extendsRange = current.endDate() != null && current.endDate().compareTo(toDate) < 0;
            String newEndDate = extendsRange ? toDate : current.endDate();
            int newDuration = PlanDates.spanDays(current.startDate(), newEndDate);
            return new Plan(current.id(), current.goalName(),
                    newDuration,
                    current.dailyHours(), current.currentLevel(), result.tasks(), current.status(),
                    current.confirmedAt(), current.startDate(),
                    newEndDate, current.createdAt(),
                    System.currentTimeMillis());
        });
        if (updated == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        if (movedCount[0] > 0) {
            auditEventService.recordCarryOver(id, movedCount[0], toDate, sessionId);
        }
        return new CarryOverResponse(movedCount[0], toDate, PlanResponse.from(updated));
    }

    public void delete(long id, String sessionId) {
        Plan deleted = planRepository.deleteById(id);
        if (deleted == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        // 캐스케이드 — 계획이 사라지면 회고도 조회 경로가 없어지므로 함께 지운다(고아 방지).
        // 변경 이력은 지우지 않는다 — "언제 삭제됐는가"에 답해야 하므로 PLAN_DELETED와 함께
        // 남기고, 메모리는 이력 저장소의 전역 상한이 관리한다.
        reflectionRepository.deleteAllByPlanId(id);
        auditEventService.recordPlanDeleted(deleted, sessionId);
    }
}
