// 계획 도메인의 순수 헬퍼 — 서버 응답↔화면 상태 변환, 상태 판정, 표시 문자열, "마지막으로 보던
// 계획" 포인터. React 상태에 의존하지 않으므로 화면 코드(chat_coach.jsx)와 분리해 둔다.
import { getSessionId } from './session_id';
import { getGuestId } from './guest_id';
import { todayStr } from './date_utils';

// "마지막으로 보던 계획"의 서버 ID 포인터 — 계획 데이터가 아니라 새로고침 복원 UX용 표식만
// localStorage에 남긴다(계획 자체는 서버 보관함에 있고 게스트 ID별로 격리된다). 소유자(게스트 ID)
// 별로 키를 분리해, 로그인 전환(guestId→memberId) 시에도 다른 소유자의 포인터가 새지 않게 한다.
const lastViewedPlanKey = () => `delaynomore:lastViewedPlanId:${getGuestId()}`;
// 소유자 스코프 이전(v0.11.0)의 전역 포인터 — 다른 소유자의 계획 id일 수 있어 1회 정리만 한다.
const LEGACY_LAST_VIEWED_PLAN_KEY = 'delaynomore:lastViewedPlanId';
// 저장된 회고의 enum 코드 → 화면 라벨. 알 수 없는 코드는 그대로 노출(화면이 죽지 않게).
export function reflectionLabel(options, code) {
  return options.find((o) => o.code === code)?.label || code;
}

// 포인터 read/write — 프라이빗 모드 등 localStorage가 막힌 환경에서도 앱이 죽지 않게 try/catch.
export function readLastViewedPlanId() {
  try {
    // 레거시 전역 포인터는 소유자 스코프가 없던 시절 값이라(타인 계획 id일 수 있음) 한 번 정리한다.
    localStorage.removeItem(LEGACY_LAST_VIEWED_PLAN_KEY);
    return localStorage.getItem(lastViewedPlanKey());
  } catch {
    return null;
  }
}

export function writeLastViewedPlanId(id) {
  try {
    localStorage.setItem(lastViewedPlanKey(), String(id));
  } catch {
    // 무시 — 포인터가 없으면 새로고침 복원만 안 될 뿐이다.
  }
}

export function clearLastViewedPlanId() {
  try {
    localStorage.removeItem(lastViewedPlanKey());
  } catch {
    // 무시
  }
}

// 현재 화면 상태 → 서버 보관 요청 본문. slots는 draftChecklist의 4개 필드와 완전 중복이라
// 별도로 보내지 않는다(복원 시 응답에서 재구성).
// startDate/duration은 서버가 tasks 날짜 키로 산출하므로 여기서 보내도 무시된다. endDate는
// 서버가 검증만 한다(형식·범위). 그래도 세 필드를 계속 보내는 이유는 배포 스큐 안전성이다 —
// 신클라이언트가 구서버(pass-through)로 요청해도 동작이 깨지지 않게. 응답에는 서버 산출값이
// 담겨 fromPlanResponse가 그대로 채택한다.
export function toPlanPayload(draftChecklist) {
  const {
    goalName, duration, dailyHours, currentLevel, tasks,
    status, confirmedAt, startDate, endDate, createdAt
  } = draftChecklist;
  return { goalName, duration, dailyHours, currentLevel, tasks, status, confirmedAt, startDate, endDate, createdAt };
}

// 서버 보관함 응답 → 화면 상태(slots + draftChecklist). 클라이언트 id는 서버 발급 숫자와
// 구분되게 chk-srv- 프리픽스를 붙인다(고정 상태 status/confirmedAt도 그대로 복원).
// startDate/endDate/duration은 서버 산출·검증값을 그대로 채택한다(규칙 소유권은 서버).
export function fromPlanResponse(plan) {
  const draftChecklist = {
    id: `chk-srv-${plan.id}`,
    goalName: plan.goalName,
    duration: plan.duration,
    dailyHours: plan.dailyHours,
    currentLevel: plan.currentLevel,
    tasks: plan.tasks || {},
    status: plan.status || 'DRAFT',
    confirmedAt: plan.confirmedAt || undefined,
    startDate: plan.startDate,
    endDate: plan.endDate,
    createdAt: plan.createdAt
  };
  const slots = {
    goalName: plan.goalName,
    duration: plan.duration,
    dailyHours: plan.dailyHours,
    currentLevel: plan.currentLevel
  };
  return { slots, draftChecklist };
}

// 완료 진행률(완료/전체 개수) — 라이브 draft 전용 UX 계산. 서버 스냅샷의 진행률은 서버가
// 계산한 progress 필드가 소스이고, 이 함수는 600ms 디바운스 동기화 전의 라이브 상태
// (draftChecklist)를 즉시 반영하기 위해서만 남아 있다. 방어적 계산(비정상 tasks 무시)은 유지.
export function getPlanProgress(tasks) {
  const all = Object.values(tasks || {}).flatMap((list) => (Array.isArray(list) ? list : []));
  return { done: all.filter((t) => t.completed).length, total: all.length };
}

// 보관함 목록 행의 저장 시각 표기(M/D 저장). 비정상 값이면 빈 문자열.
export function formatSavedAt(ts) {
  const d = new Date(ts);
  return Number.isFinite(d.getTime()) ? `${d.getMonth() + 1}/${d.getDate()} 저장` : '';
}

// 주간 요약 행의 날짜 범위 — 서버가 준 YYYY-MM-DD(startDate/endDate)를 M.D~M.D로 압축 표기한다.
// 하루짜리 주(startDate==endDate)면 한쪽만 보여 준다. 비ISO는 방어적으로 원문 그대로.
export function formatWeekRange(startDate, endDate) {
  const short = (iso) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
    return m ? `${Number(m[2])}.${Number(m[3])}` : iso;
  };
  return startDate === endDate ? short(startDate) : `${short(startDate)}~${short(endDate)}`;
}

// 오늘의 미완료 개수 — 이월 확인창(UX)용 로컬 카운트. 이월 연산 자체는 서버 도메인 액션
// (POST /plans/{id}/carry-over)이 수행하므로, 프론트는 "옮길 게 있는가"만 미리 세어
// 불필요한 요청과 빈 확인창을 막는다.
export function countTodayIncomplete(tasks, date) {
  const dayTasks = tasks?.[date];
  if (!Array.isArray(dayTasks)) return 0;
  return dayTasks.filter((t) => !t.completed).length;
}

// === 계획 상태(PlanStatus) 헬퍼 — 상태 집합·전이 규칙의 소스오브트루스는 서버 PlanStatus
// enum(선언적 전이표)이다. 프론트는 전이를 판정하지 않고(전이는 confirm/complete/cancel API가
// 수행·거부한다) 버튼 노출·잠금 표시용 분류만 한다.
export const planStatusOf = (source) => source?.status || 'DRAFT';
// 구조 변경(대화 수정·이월·기간 연장) 허용 여부 — 서버 allowsStructuralEdit와 같은 기준(DRAFT만).
export const isEditableStatus = (status) => status === 'DRAFT';
// 종결 상태 — 완료 토글을 포함한 모든 변경 PUT을 서버가 거부하므로 프론트도 입력을 막는다.
export const isTerminalStatus = (status) => status === 'COMPLETED' || status === 'CANCELLED';
// 지난 날짜 잠금 — 고정(CONFIRMED) 계획의 완료 체크는 오늘·미래만(서버 PAST_TASK_LOCKED와 같은
// 기준). 이월이 "오늘 → 내일"뿐이라 미루지 않은 지난 항목은 놓친 것으로 확정된다(체크·해제 불가).
// 날짜 키는 YYYY-MM-DD라 문자열 비교로 충분하다. DRAFT는 자유 수정 단계라 제외.
export const isPastLockedDate = (status, date) => status === 'CONFIRMED' && date < todayStr();
// 상태 코드 → 화면 라벨 폴백 사본 — 소스오브트루스는 GET /meta/plan-statuses(서버 enum 라벨).
export const DEFAULT_PLAN_STATUS_LABELS = {
  DRAFT: '초안',
  CONFIRMED: '고정',
  COMPLETED: '완료',
  CANCELLED: '중단'
};

// 변경 이력 이벤트 타입 → 화면 라벨. 알 수 없는 타입은 코드 그대로 노출(회고 라벨과 같은 방어).
// 소스오브트루스는 서버 enum(메타 API로 수신)이고, 이 상수는 백엔드 미가용 시 폴백 사본이다.
export const DEFAULT_AUDIT_EVENT_LABELS = {
  PLAN_CREATED: '계획 생성',
  PLAN_UPDATED: '계획 수정',
  PLAN_CONFIRMED: '계획 고정',
  PLAN_COMPLETED: '계획 완료',
  PLAN_CANCELLED: '계획 중단',
  TASK_COMPLETED: '할 일 완료',
  TASK_REOPENED: '완료 해제',
  REFLECTION_SAVED: '회고 저장',
  PLAN_DELETED: '계획 삭제',
  WORKLOAD_RECOMMENDATION_VIEWED: '다음 분량 추천 조회',
  WORKLOAD_RECOMMENDATION_ACCEPTED: '추천 분량 채택',
  WORKLOAD_RECOMMENDATION_OVERRIDDEN: '추천 분량 변경',
  PLAN_CREATED_FROM_RECOMMENDATION: '추천 기반 계획 생성'
};

// 이력 행의 세션 배지 — 내 세션 ID와 비교해 "다른 세션에서 발생한 변경인가?"에 답한다.
// sessionId가 없으면(구형 클라이언트·curl) "알 수 없음".
export function auditSessionBadge(sessionId) {
  if (!sessionId) return '알 수 없음';
  return sessionId === getSessionId() ? '이 브라우저' : '다른 세션';
}

// 이력 행의 발생 시각 — 가까운 과거는 상대 표기, 오래되면 절대 표기(M/D HH:mm).
export function formatEventTime(iso) {
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return '';
  const diffMs = Date.now() - d.getTime();
  if (diffMs < 60 * 1000) return '방금 전';
  if (diffMs < 60 * 60 * 1000) return `${Math.floor(diffMs / (60 * 1000))}분 전`;
  if (diffMs < 24 * 60 * 60 * 1000) return `${Math.floor(diffMs / (60 * 60 * 1000))}시간 전`;
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${hh}:${mm}`;
}

// 에이전트 도구 이름 → 화면 라벨. 서버가 내려주는 이름은 모델용 영문 snake_case라 그대로
// 노출하면 읽기 어렵다. 모르는 이름(서버에 도구가 추가됐는데 프론트가 아직 모르는 경우)은
// 이름 그대로 보여준다 — 추적 패널이 빈칸이 되는 것보다 낫다.
export const AGENT_TOOL_LABELS = {
  get_today_tasks: '오늘 할 일 조회',
  get_weekly_summary: '주간 완료율 조회',
  get_reflection_history: '회고 기록 조회',
  get_workload_recommendation: '다음 분량 추천 조회',
  get_progress: '전체 진행 조회',
  get_plan_history: '변경 이력 조회',
  get_challenge_status: '챌린지 현황 조회',
  update_plan_tasks: '계획 수정',
  carry_over_tasks: '미완료 이월'
};

export const agentToolLabel = (name) => AGENT_TOOL_LABELS[name] || name;
// 에이전트 도구 이름 → 추천 질문 칩 문구. 어떤 칩을 보여줄지는 여기가 아니라 도구 카탈로그
// API(fetchAgentTools)가 정한다 — 서버가 그 상태에서 실제로 노출한 도구만 칩이 되므로,
// 권한 표(PlanStatus)를 프론트에 재선언하지 않는다. 여기는 문구 사전일 뿐이고, 사전에 없는
// 도구는 칩을 만들지 않는다(질문으로 표현할 수 없는 도구까지 억지로 칩을 만들지 않는다).
export const AGENT_TOOL_QUESTIONS = {
  get_today_tasks: '오늘 뭐부터 할까?',
  get_progress: '지금 어디까지 왔어?',
  get_weekly_summary: '이번 주 어땠어?',
  get_plan_history: '그동안 뭐가 바뀌었어?',
  get_reflection_history: '내 회고에서 보이는 패턴은?',
  get_workload_recommendation: '다음 계획 분량 추천해줘',
  get_challenge_status: '내 챌린지 몇 등이야?'
};
