package com.delaynomore.backend.domain.plan.service;

import com.delaynomore.backend.domain.challenge.service.ChallengeService;
import com.delaynomore.backend.domain.plan.dto.CarryOverResponse;
import com.delaynomore.backend.domain.plan.dto.PlanResponse;
import com.delaynomore.backend.domain.plan.dto.PlanSaveRequest;
import com.delaynomore.backend.domain.plan.dto.WeeklySummaryResponse;
import com.delaynomore.backend.domain.plan.entity.Plan;
import com.delaynomore.backend.domain.plan.entity.PlanStatus;
import com.delaynomore.backend.domain.plan.repository.PlanRepository;
import com.delaynomore.backend.domain.plan.repository.ReflectionRepository;
import com.delaynomore.backend.domain.plan.support.PlanDates;
import com.delaynomore.backend.global.error.BusinessException;
import com.delaynomore.backend.global.error.ErrorCode;
import com.delaynomore.backend.global.time.KstDates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PlanService {

    // 소유자당 한도 — 한 게스트가 보관할 수 있는 계획 수. 초과 시 "내 보관함 가득참"(사용자가
    // 직접 해소 가능하므로 PLAN_LIMIT_EXCEEDED, 400).
    private static final int MAX_PLANS_PER_OWNER = 10;
    // 전역 상한 — 소유자 격리와 무관하게 저장소는 한 서버 메모리이므로, 합산 폭주를 막는 안전판.
    // 초과 시 "서버 보관함 가득참"(사용자 잘못이 아니므로 PLAN_STORE_FULL, 503).
    private static final int MAX_PLANS_GLOBAL = 200;
    // 하루 생성 한도 — KST 자정 리셋. 소스는 감사 이력(PLAN_CREATED, 삭제 생존)이라 삭제-재생성
    // 우회 불가. 검사 순서상 맨 앞 — 보관함 한도와 동시 초과 시 "삭제하라"는 안내가 오답이므로
    // (지워도 오늘은 못 만듦) "내일 다시" 안내가 우선한다.
    private static final int MAX_PLANS_CREATED_PER_DAY = 5;

    private final PlanRepository planRepository;
    private final ReflectionRepository reflectionRepository;
    private final AuditEventService auditEventService;
    // 고정된 계획을 챌린지 자동 생성의 씨앗으로 넘기기 위한 단방향 의존 — 챌린지는 계획을 모른다.
    private final ChallengeService challengeService;

    // synchronized: 두 한도 검사(count·countByOwner)와 저장(save)을 원자적으로 묶는다. 동시에
    // 생성하면 각자 검사를 통과한 뒤 저장해 상한을 넘길 수 있는데(TOCTOU), 생성 경로를 직렬화해
    // 이를 막는다. 생성만 개수를 늘리므로 create만 잠그면 충분하다.
    // 검사 순서: 소유자당 한도를 먼저 본다 — 저장소가 전역으로도 가득 찬 상황에서도, 자기 보관함이
    // 꽉 찬 사용자에게는 "내 계획을 지워라"는 실행 가능한 안내가 우선 가도록.
    // @Transactional: 계획 저장 + 감사 append를 한 트랜잭션으로 커밋한다(JDBC 프로필). 한계 —
    // 트랜잭션 프록시는 이 메서드가 반환한 뒤(모니터 해제 후) 커밋하므로, READ COMMITTED에서 경합
    // 시 count 검사가 직전 insert를 못 봐 상한을 최대 1 초과할 수 있다. 다중 서버가 범위 밖인 단일
    // 서버 데모에서는 허용 가능한 오차이며, 강화(자기호출 내부 트랜잭션 or pg_advisory_xact_lock)는
    // 다중 서버 마일스톤으로 이연한다.
    // category는 요청 바디가 아니라 호출부가 넘긴다 — 초안 세션·추천 경로는 LLM이 판정한 값을,
    // 레거시 POST /plans는 null을 넘기고 Plan.conditionKey()의 키워드 폴백이 받는다.
    @Transactional
    public synchronized PlanResponse create(PlanSaveRequest request, String owner, String sessionId,
                                            String category) {
        Instant kstDayStart = KstDates.today().atStartOfDay(KstDates.KST).toInstant();
        if (auditEventService.countPlansCreatedSince(owner, kstDayStart) >= MAX_PLANS_CREATED_PER_DAY) {
            throw new BusinessException(ErrorCode.PLAN_DAILY_LIMIT_EXCEEDED);
        }
        if (planRepository.countByOwner(owner) >= MAX_PLANS_PER_OWNER) {
            throw new BusinessException(ErrorCode.PLAN_LIMIT_EXCEEDED);
        }
        if (planRepository.count() >= MAX_PLANS_GLOBAL) {
            throw new BusinessException(ErrorCode.PLAN_STORE_FULL);
        }
        // 날짜 규칙은 서버 소유 — startDate는 tasks 최초 날짜 키로, duration은 [startDate, endDate]
        // 범위로 산출한다(클라이언트가 보낸 startDate/duration은 무시). endDate는 @ValidPlanDates가 검증.
        String startDate = PlanDates.minTaskKey(request.tasks());
        int duration = PlanDates.spanDays(startDate, request.endDate());
        Plan saved = planRepository.save(
                request.toPlan(null, System.currentTimeMillis(), startDate, duration, owner, category));
        auditEventService.recordPlanCreated(saved, sessionId);
        return PlanResponse.from(saved);
    }

    public List<PlanResponse> getPlans(String owner) {
        return planRepository.findAllByOwner(owner).stream()
                .map(PlanResponse::from)
                .toList();
    }

    public PlanResponse getPlan(long id, String owner) {
        return PlanResponse.from(requireOwnedPlan(id, owner));
    }

    // 주간 완료율 요약 — 계획을 startDate 기준 7일 버킷으로 묶어 주별 완료율을 낸다. 읽기 전용이라
    // getPlan과 같은 조회·404 패턴. 완료 개수 계산은 서버 소유(plan.tasks 기준, WeeklySummaryResponse.from).
    public WeeklySummaryResponse getWeeklySummary(long id, String owner) {
        return WeeklySummaryResponse.from(requireOwnedPlan(id, owner));
    }

    // 소유자 확인 조회 — 남의 계획은 "존재 자체를 숨긴다"(404). 403이 아닌 이유: 닉네임은
    // 인증이 아니라 스코프 키라, 존재 여부 노출 자체가 정보 유출이다.
    private Plan requireOwnedPlan(long id, String owner) {
        return planRepository.findById(id)
                .filter(p -> owner.equals(p.owner()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    @Transactional
    public PlanResponse update(long id, PlanSaveRequest request, String owner, String sessionId) {
        // startDate는 생성 시 산출된 뒤 불변이라 원자 구간 밖에서 읽어도 레이스가 없다(어떤 동시
        // 쓰기도 startDate를 바꾸지 못한다). 이 값을 보존하고 duration만 [startDate, endDate]로 재산출
        // 한다 — 클라이언트가 보낸 startDate/duration은 무시(규칙 소유권은 서버). DB 이관 시에도 이
        // 불변식(startDate 고정)을 유지해야 이 비원자 읽기가 안전하다.
        Plan current = requireOwnedPlan(id, owner);
        String startDate = current.startDate();
        int duration = PlanDates.spanDays(startDate, request.endDate());
        // 카테고리는 startDate와 같은 이유로 보존한다 — 클라이언트가 보내지 않는 서버 소유 값이라,
        // 여기서 잇지 않으면 대화로 계획을 한 번 고칠 때마다 조용히 null이 된다.
        Plan updated = request.toPlan(id, System.currentTimeMillis(), startDate, duration, owner,
                current.category());
        // 고정·소유자 가드는 저장소의 키 단위 원자 구간 안에서 실행된다 — 조회·검사·교체 사이에 다른
        // 쓰기(예: 다른 브라우저의 고정)가 끼어들 수 없어 check-then-act 레이스가 없다.
        // (위 requireOwnedPlan은 startDate를 읽기 위한 사전 조회일 뿐, 판정은 이 원자 구간이 최종이다.)
        // "오늘"은 원자 구간 밖에서 한 번만 판정한다 — 콜백 재실행·자정 경계에서 기준이 흔들리지 않도록.
        String today = KstDates.today().toString();
        Plan previous = planRepository.update(updated,
                c -> {
                    if (!owner.equals(c.owner())) {
                        throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
                    }
                    assertUnlockedOrToggleOnly(c, updated, today);
                });
        if (previous == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        // 모든 변경(내용 수정·고정·완료 토글)이 이 PUT 하나로 들어오므로, 이전 상태와 diff해
        // 이벤트 종류(PLAN_CONFIRMED/TASK_*/PLAN_UPDATED)를 서버가 판별·기록한다.
        auditEventService.recordPlanUpdated(previous, updated, sessionId);
        // PUT으로도 DRAFT→CONFIRMED 고정이 가능하므로(레거시 경로) 여기서도 씨앗을 남긴다.
        // recordSeed가 멱등이라 confirm 엔드포인트와 겹쳐 불려도 안전하다.
        if (!previous.isConfirmed() && updated.isConfirmed()) {
            challengeService.onPlanConfirmed(owner, updated.conditionKey());
        }
        return PlanResponse.from(updated);
    }

    /**
     * A task completion is an execution command, not a replacement of the plan document. The server resolves the
     * task date from the stored plan so a client cannot bypass the confirmed-plan past-date lock by sending another date.
     */
    @Transactional
    public PlanResponse updateTaskCompletion(long id, String taskId, boolean completed, String owner, String sessionId) {
        Plan[] previous = new Plan[1];
        String today = KstDates.today().toString();
        Plan updated = planRepository.mutate(id, current -> {
            if (!owner.equals(current.owner())) {
                throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
            }
            PlanStatus status = current.statusOrDraft();
            if (!status.allowsStructuralEdit() && !status.allowsCompletionToggle()) {
                throw new BusinessException(ErrorCode.PLAN_LOCKED);
            }
            TaskMutation mutation = updateTask(current.tasks(), taskId, completed, status, today);
            previous[0] = current;
            return new Plan(current.id(), current.owner(), current.goalName(), current.duration(),
                    current.dailyHours(), current.currentLevel(), mutation.tasks(), current.status(),
                    current.confirmedAt(), current.completedAt(), current.startDate(), current.endDate(),
                    current.createdAt(), System.currentTimeMillis(), current.category());
        });
        if (updated == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        auditEventService.recordPlanUpdated(previous[0], updated, sessionId);
        return PlanResponse.from(updated);
    }

    private static TaskMutation updateTask(Map<String, Object> source, String taskId, boolean completed,
                                           PlanStatus status, String today) {
        Map<String, Object> copied = new LinkedHashMap<>();
        boolean found = false;
        if (source != null) {
            for (Map.Entry<String, Object> day : source.entrySet()) {
                if (!(day.getValue() instanceof List<?> list)) {
                    copied.put(day.getKey(), day.getValue());
                    continue;
                }
                List<Object> nextDay = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> task && taskId.equals(String.valueOf(task.get("id")))) {
                        if (found) throw new BusinessException(ErrorCode.INVALID_INPUT);
                        if (status.allowsCompletionToggle() && day.getKey().compareTo(today) < 0) {
                            throw new BusinessException(ErrorCode.PAST_TASK_LOCKED);
                        }
                        Map<String, Object> nextTask = new LinkedHashMap<>();
                        task.forEach((key, value) -> nextTask.put(String.valueOf(key), value));
                        nextTask.put("completed", completed);
                        nextDay.add(nextTask);
                        found = true;
                    } else {
                        nextDay.add(item);
                    }
                }
                copied.put(day.getKey(), nextDay);
            }
        }
        if (!found) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return new TaskMutation(copied);
    }

    private record TaskMutation(Map<String, Object> tasks) {
    }

    // 상태별 PUT 허용 범위 — 규칙은 PlanStatus의 전이표·능력 플래그가 소유하고, 여기서는 표를
    // 참조해 판정만 한다. 예전엔 프론트만 지키던 규칙이라 curl 등 직접 호출로 우회할 수 있었다.
    //  · DRAFT: 자유 수정(allowsStructuralEdit) — DRAFT→CONFIRMED 고정 PUT(수정 동반 포함)도 허용
    //    (레거시 경로 — 프론트가 전이 엔드포인트로 이전하기 전까지 유지).
    //  · CONFIRMED: completed 토글만(allowsCompletionToggle) — 구조 변경 판정은 변경 이력과 같은
    //    기준(PlanTaskDiff.hasStructuralChange: 스칼라 6종 + 날짜별 항목키→content 뷰, completed 제외).
    //    토글도 오늘(KST)·미래 날짜만 — 이월이 "오늘 → 내일"뿐이라 미루지 않은 지난 항목은 놓친
    //    것으로 확정되며, 지난 날짜는 체크·해제 모두 거부해 완료율 소급 조작을 막는다(PAST_TASK_LOCKED).
    //  · COMPLETED·CANCELLED(종결): 모든 PUT 거부 — 상태 자체가 DRAFT|CONFIRMED 외로는 요청
    //    바디에 실릴 수 없어(@Pattern) 상태 불일치로 걸러진다.
    // 위반은 기존 PLAN_LOCKED(409) — 프론트가 error.code로 분기하므로 코드 호환을 유지한다.
    // 지난 날짜 토글만 별도 코드(PAST_TASK_LOCKED, 409)로 구분한다(안내 문구가 달라야 하므로).
    // createdAt은 표시용 메타라 가드 대상이 아니다. 삭제는 계속 허용한다(프론트 탈출구).
    private static void assertUnlockedOrToggleOnly(Plan current, Plan incoming, String today) {
        PlanStatus from = current.statusOrDraft();
        PlanStatus to = incoming.statusOrDraft();
        // 상태 변경을 동반한 PUT은 전이표가 허용하면서 목적지가 CONFIRMED인 경우(레거시 고정)만
        // 통과한다 — 롤백(CONFIRMED→DRAFT)·종결 상태 이탈이 전부 여기서 걸러진다.
        if (from != to && !(from.canTransitionTo(to) && to == PlanStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.PLAN_LOCKED);
        }
        if (from.allowsStructuralEdit()) {
            return; // DRAFT는 자유 수정.
        }
        if (!from.allowsCompletionToggle()) {
            throw new BusinessException(ErrorCode.PLAN_LOCKED); // 종결 상태는 전면 잠금.
        }
        boolean confirmedAtChanged = !Objects.equals(current.confirmedAt(), incoming.confirmedAt());
        var prevTasks = PlanTaskDiff.parseTasks(current.tasks());
        var nextTasks = PlanTaskDiff.parseTasks(incoming.tasks());
        if (confirmedAtChanged || PlanTaskDiff.hasStructuralChange(current, incoming, prevTasks, nextTasks)) {
            throw new BusinessException(ErrorCode.PLAN_LOCKED);
        }
        // 날짜 키는 YYYY-MM-DD라 사전순 == 시간순 — 오늘보다 작은 키의 토글은 소급 변경이다.
        for (String date : PlanTaskDiff.completedChangedDates(prevTasks, nextTasks)) {
            if (date.compareTo(today) < 0) {
                throw new BusinessException(ErrorCode.PAST_TASK_LOCKED);
            }
        }
    }

    // === 상태 전이 엔드포인트 (POST /plans/{id}/confirm·complete·cancel) ===
    // PUT 전체 교체와 달리 전이 자체가 일급 명령이다 — 허용 여부는 PlanStatus 전이표가 판정하고,
    // 시각(confirmedAt·completedAt)은 서버가 발급하며(클라이언트 시각을 믿지 않음), 이력은 diff
    // 역추론 없이 전이명 그대로 발행된다(recordCarryOver와 같은 직접 발행 관례).

    // 고정은 챌린지 자동 생성의 유일한 트리거다 — 비슷한 조건(기간 + 목적)의 계획이 셋 모이면
    // 그 자리에서 챌린지가 열린다. 스케줄러를 두지 않은 이유: 조건이 채워지는 순간이 바로 여기다.
    // 조건은 계획이 이미 들고 있는 값(conditionKey)을 그대로 넘긴다 — 분류가 두 군데서 일어나지 않게.
    @Transactional
    public PlanResponse confirm(long id, String owner, String sessionId) {
        Plan confirmed = transition(id, owner, PlanStatus.CONFIRMED, sessionId);
        challengeService.onPlanConfirmed(owner, confirmed.conditionKey());
        return PlanResponse.from(confirmed);
    }

    @Transactional
    public PlanResponse complete(long id, String owner, String sessionId) {
        return PlanResponse.from(transition(id, owner, PlanStatus.COMPLETED, sessionId));
    }

    @Transactional
    public PlanResponse cancel(long id, String owner, String sessionId) {
        return PlanResponse.from(transition(id, owner, PlanStatus.CANCELLED, sessionId));
    }

    // 공통 전이 실행기 — 가드·판정·교체가 저장소의 키 단위 원자 구간(mutate) 안에서 실행돼
    // check-then-act 레이스가 없다(carryOver와 같은 계약). 소유자 불일치·부재는 404로 존재를
    // 숨기고(requireOwnedPlan과 같은 기준), 전이표에 없는 전이는 INVALID_STATUS_TRANSITION(409).
    // PlanResponse가 아니라 Plan을 돌려준다 — confirm이 응답에 없는 내부 값(conditionKey)을 써야 하기
    // 때문이다. 응답 변환은 호출부가 한 줄로 한다.
    private Plan transition(long id, String owner, PlanStatus target, String sessionId) {
        Plan updated = planRepository.mutate(id, current -> {
            if (!owner.equals(current.owner())) {
                throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
            }
            if (!current.statusOrDraft().canTransitionTo(target)) {
                throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
            }
            return applyTransition(current, target);
        });
        if (updated == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        auditEventService.recordTransition(updated, target, sessionId);
        return updated;
    }

    // 전이 결과 조립 — 상태와 해당 전이의 타임스탬프만 바꾸고 내용(tasks 등)은 그대로 둔다.
    // CANCELLED는 시각을 남기지 않는다(언제 중단했는지는 감사 이력(PLAN_CANCELLED)이 담당).
    private static Plan applyTransition(Plan current, PlanStatus target) {
        String now = Instant.now().toString();
        String confirmedAt = target == PlanStatus.CONFIRMED ? now : current.confirmedAt();
        String completedAt = target == PlanStatus.COMPLETED ? now : current.completedAt();
        return new Plan(current.id(), current.owner(), current.goalName(), current.duration(),
                current.dailyHours(), current.currentLevel(), current.tasks(),
                target.name(), confirmedAt, completedAt, current.startDate(), current.endDate(),
                current.createdAt(), System.currentTimeMillis(), current.category());
    }

    // 미완료 이월 도메인 액션 — 오늘(KST) 미완료를 내일로 옮긴다. 예전엔 프론트가 계산해 PUT으로
    // 보내고 서버가 diff에서 역감지했지만, 이제 날짜 규칙과 연산 모두 서버가 소유한다.
    // 가드·연산은 저장소의 키 단위 원자 구간(mutate) 안에서 실행돼 다른 쓰기와 경합하지 않는다.
    @Transactional
    public CarryOverResponse carryOver(long id, String owner, String sessionId) {
        String fromDate = KstDates.today().toString();
        String toDate = KstDates.today().plusDays(1).toString();
        int[] movedCount = new int[1];
        Plan updated = planRepository.mutate(id, current -> {
            // 소유자 불일치는 존재 자체를 숨긴다(404) — requireOwnedPlan과 같은 기준을 원자 구간 안에서.
            if (!owner.equals(current.owner())) {
                throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
            }
            // 이월은 실행 단계 액션이라 고정(CONFIRMED) 후에도 허용한다(allowsCarryOver) —
            // 형태는 구조 변경(항목 이동·기간 연장)이지만 내용 재협상이 아니라 서버 소유 규칙의
            // 통제된 이동이고, PUT 가드를 거치지 않는 도메인 액션이라 고정 잠금과 충돌하지 않는다.
            // 종결(COMPLETED·CANCELLED) 상태만 거부한다(전면 잠금과 동일 코드 PLAN_LOCKED).
            if (!current.statusOrDraft().allowsCarryOver()) {
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
            return new Plan(current.id(), current.owner(), current.goalName(),
                    newDuration,
                    current.dailyHours(), current.currentLevel(), result.tasks(), current.status(),
                    current.confirmedAt(), current.completedAt(), current.startDate(),
                    newEndDate, current.createdAt(),
                    System.currentTimeMillis(), current.category());
        });
        if (updated == null) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        if (movedCount[0] > 0) {
            auditEventService.recordCarryOver(id, owner, movedCount[0], toDate, sessionId);
        }
        return new CarryOverResponse(movedCount[0], toDate, PlanResponse.from(updated));
    }

    @Transactional
    public void delete(long id, String owner, String sessionId) {
        // 소유자 가드는 저장소의 키 단위 원자 구간 안에서 실행된다 — 검사와 제거 사이에 끼어들 수 없다.
        Plan deleted = planRepository.deleteById(id,
                c -> {
                    if (!owner.equals(c.owner())) {
                        throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
                    }
                });
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
