package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.plan.dto.AuditEventResponse;
import com.delaynomore.backend.domain.plan.entity.AuditEvent;
import com.delaynomore.backend.domain.plan.entity.AuditEventType;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 계획 변경 이력 발행기 — 계획을 바꾸는 서비스(PlanService·ReflectionService)가 변경 직후 호출한다.
 * 프론트의 모든 변경(내용 수정·고정·완료 토글)이 PUT 전체 교체 하나로 들어오므로, 서버가
 * 이전 상태와 새 상태를 비교(diff)해 이벤트 종류를 복원한다 — 회고의 완료 개수처럼
 * 클라이언트가 보낸 의미를 믿는 대신 서버가 계산한다.
 * append는 호출한 서비스 메서드의 트랜잭션(@Transactional)에 편승한다(propagation REQUIRED) —
 * JDBC 프로필에서는 감사 기록 실패가 본 변경까지 롤백시키므로 계획과 이력이 원자적으로 함께
 * 남는다(v0.12.0에서의 정합성 강화 — 인메모리 시절의 "append는 실패할 일이 없다" 전제를 대체).
 */
@Service
@RequiredArgsConstructor
public class AuditEventService {

    // 임의 헤더를 그대로 저장하지 않기 위한 방어 — 트림 후 비면 null, 길면 절단.
    private static final int MAX_SESSION_ID_LENGTH = 64;
    private static final String GENERIC_UPDATE_DETAIL = "계획 내용 변경";

    private final AuditEventRepository auditEventRepository;

    public void recordPlanCreated(Plan saved, String sessionId) {
        append(saved.id(), saved.owner(), AuditEventType.PLAN_CREATED, null, sessionId);
    }

    public void recordPlanDeleted(Plan deleted, String sessionId) {
        append(deleted.id(), deleted.owner(), AuditEventType.PLAN_DELETED,
                "\"" + deleted.goalName() + "\" 삭제", sessionId);
    }

    public void recordReflectionSaved(long planId, String owner, String date, int completedCount,
                                      int totalCount, String sessionId) {
        append(planId, owner, AuditEventType.REFLECTION_SAVED,
                date + " 회고 저장 (" + completedCount + "/" + totalCount + " 완료)", sessionId);
    }

    // 미완료 이월 — 서버 도메인 액션(POST /plans/{id}/carry-over)이 수행 직후 직접 발행한다.
    // 예전엔 프론트가 PUT으로 보낸 diff에서 이월 패턴을 역감지(detectCarryOver)했지만, 연산이
    // 서버로 이관되며 감지가 불필요해졌다. detail 문자열 형식은 기존과 동일하게 유지한다.
    public void recordCarryOver(long planId, String owner, int movedCount, String targetDate, String sessionId) {
        append(planId, owner, AuditEventType.PLAN_UPDATED,
                "미완료 " + movedCount + "건을 " + targetDate + "로 이동", sessionId);
    }

    // 갱신 diff 분류 — 한 PUT에서 0..n건을 발행한다(디바운스 배칭으로 토글 여러 개가 한 번에 올 수 있다).
    // 발행 순서: PLAN_CONFIRMED → TASK_*(날짜·항목 순) → PLAN_UPDATED. 완전 동일(no-op) PUT은 발행 없음.
    public void recordPlanUpdated(Plan previous, Plan updated, String sessionId) {
        String owner = updated.owner();
        if (!previous.isConfirmed() && updated.isConfirmed()) {
            append(updated.id(), owner, AuditEventType.PLAN_CONFIRMED, null, sessionId);
        }

        Map<String, Map<String, PlanTaskDiff.TaskView>> prevTasks = PlanTaskDiff.parseTasks(previous.tasks());
        Map<String, Map<String, PlanTaskDiff.TaskView>> nextTasks = PlanTaskDiff.parseTasks(updated.tasks());

        // 양쪽에 모두 존재하는 (날짜, 항목)의 completed 플립만 완료/해제로 센다 —
        // 항목의 추가·삭제·이동은 아래 구조 변경(PLAN_UPDATED)이 담당한다.
        for (Map.Entry<String, Map<String, PlanTaskDiff.TaskView>> dayEntry : nextTasks.entrySet()) {
            Map<String, PlanTaskDiff.TaskView> prevDay = prevTasks.get(dayEntry.getKey());
            if (prevDay == null) continue;
            for (Map.Entry<String, PlanTaskDiff.TaskView> taskEntry : dayEntry.getValue().entrySet()) {
                PlanTaskDiff.TaskView before = prevDay.get(taskEntry.getKey());
                PlanTaskDiff.TaskView after = taskEntry.getValue();
                if (before == null || before.completed() == after.completed()) continue;
                AuditEventType type = after.completed() ? AuditEventType.TASK_COMPLETED : AuditEventType.TASK_REOPENED;
                append(updated.id(), owner, type, "\"" + after.content() + "\" · " + dayEntry.getKey(), sessionId);
            }
        }

        // 구조 변경은 항상 일반 detail — 이월은 도메인 액션이 recordCarryOver로 직접 발행하므로
        // PUT diff에서 이월 패턴을 역감지할 필요가 없다.
        if (PlanTaskDiff.hasStructuralChange(previous, updated, prevTasks, nextTasks)) {
            append(updated.id(), owner, AuditEventType.PLAN_UPDATED, GENERIC_UPDATE_DETAIL, sessionId);
        }
    }

    // 이벤트에 소유자(ownerId)를 박아 두므로 계획 생존 여부와 무관하게 소유자 스코프로 조회한다 —
    // 삭제된 계획의 이력도 소유자에게는 다시 보인다("언제 삭제됐는가" 계약 복원). 남의 계획·모르는
    // planId는 404가 아니라 빈 목록(존재 여부 은닉). PlanRepository 조회가 필요 없어졌다.
    public List<AuditEventResponse> getEvents(long planId, String owner) {
        return auditEventRepository.findAllByPlanIdAndOwner(planId, owner).stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    // 발행은 enum으로만 받아 오타를 컴파일 타임에 막고, 저장(AuditEvent.type)은 String을
    // 유지한다(DB 행 1:1 관례·응답 형식 불변).
    private void append(long planId, String ownerId, AuditEventType type, String detail, String sessionId) {
        auditEventRepository.append(new AuditEvent(
                0, planId, ownerId, type.name(), detail, sanitizeSessionId(sessionId), Instant.now().toString()));
    }

    private static String sanitizeSessionId(String sessionId) {
        if (sessionId == null) return null;
        String trimmed = sessionId.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_SESSION_ID_LENGTH ? trimmed.substring(0, MAX_SESSION_ID_LENGTH) : trimmed;
    }

    // === diff 내부 표현 ===
    // TaskView/parseTasks/contentView/hasStructuralChange는 PlanTaskDiff로 추출됨 —
    // PlanService의 고정 계획 가드가 같은 "구조 변경" 판정 기준을 공유하기 위해서다.
}
