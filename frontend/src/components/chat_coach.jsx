import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
  Send, Copy, Download, Check, Save, Lock, RotateCcw, CalendarPlus,
  FolderOpen, Trash2, ChevronDown, ChevronUp, Plus, RefreshCw, Sun, ArrowRight,
  CheckCircle2, History
} from 'lucide-react';
import {
  REQUIRED_SLOTS,
  getNextEmptySlot,
  getNextQuestion,
  parseUserMessage,
  streamChecklistDraft,
  streamChatWithCoach,
  formatChecklistAsText,
  isPlanModificationRequest,
  INITIAL_SLOTS
} from '../ai_engine';
import { createPlan, updatePlan, fetchPlans, fetchPlan, deletePlan, putReflection, fetchReflection, fetchAuditEvents } from '../db_service';
import { getSessionId } from '../session_id';
import { todayStr } from '../date_utils';

// "마지막으로 보던 계획"의 서버 ID 포인터 — 계획 데이터가 아니라 새로고침 복원 UX용 표식만
// localStorage에 남긴다(계획 자체는 서버 보관함에 있고 모든 방문자가 공유한다).
const LAST_VIEWED_PLAN_KEY = 'delaynomore:lastViewedPlanId';

// 서버 자동 동기화 디바운스 — 완료 토글 연타나 스트리밍 수정이 요청 폭주로 이어지지 않게 한다.
const SYNC_DEBOUNCE_MILLIS = 600;

// 슬롯필링 질문에 제공하는 빠른 선택지. 자유 입력도 계속 가능하다.
// 라벨 텍스트를 그대로 전송한다(기간/시간은 파서가 숫자만 추출).
const DURATION_PRESETS = ['3일', '5일', '7일'];
const DAILY_HOURS_PRESETS = ['1시간', '2시간', '4시간', '6시간'];
const LEVEL_PRESETS = ['완전 초보', '기본 개념은 아는 수준', '실전 경험 있음'];

// 오늘 마무리(회고) 선택지 — 자유 입력 메모는 두지 않는다(모든 방문자가 공유하는 데모
// 저장소라 개인 텍스트가 남지 않게). 코드는 서버 검증 enum과 1:1로 맞춘다.
const DIFFICULTY_OPTIONS = [
  { code: 'EASY', label: '여유로웠어요' },
  { code: 'NORMAL', label: '적당했어요' },
  { code: 'HARD', label: '벅찼어요' }
];
const REASON_OPTIONS = [
  { code: 'AS_PLANNED', label: '계획대로 진행됐어요' },
  { code: 'NOT_ENOUGH_TIME', label: '시간이 부족했어요' },
  { code: 'TOO_MUCH_WORK', label: '분량이 많았어요' },
  { code: 'HARD_TO_FOCUS', label: '집중이 잘 안 됐어요' },
  { code: 'HARDER_THAN_EXPECTED', label: '생각보다 어려웠어요' }
];

// 저장된 회고의 enum 코드 → 화면 라벨. 알 수 없는 코드는 그대로 노출(화면이 죽지 않게).
function reflectionLabel(options, code) {
  return options.find((o) => o.code === code)?.label || code;
}

// 포인터 read/write — 프라이빗 모드 등 localStorage가 막힌 환경에서도 앱이 죽지 않게 try/catch.
function readLastViewedPlanId() {
  try {
    return localStorage.getItem(LAST_VIEWED_PLAN_KEY);
  } catch {
    return null;
  }
}

function writeLastViewedPlanId(id) {
  try {
    localStorage.setItem(LAST_VIEWED_PLAN_KEY, String(id));
  } catch {
    // 무시 — 포인터가 없으면 새로고침 복원만 안 될 뿐이다.
  }
}

function clearLastViewedPlanId() {
  try {
    localStorage.removeItem(LAST_VIEWED_PLAN_KEY);
  } catch {
    // 무시
  }
}

// 현재 화면 상태 → 서버 보관 요청 본문. slots는 draftChecklist의 4개 필드와 완전 중복이라
// 별도로 보내지 않는다(복원 시 응답에서 재구성).
function toPlanPayload(draftChecklist) {
  const {
    goalName, duration, dailyHours, currentLevel, tasks,
    status, confirmedAt, startDate, endDate, createdAt
  } = draftChecklist;
  return { goalName, duration, dailyHours, currentLevel, tasks, status, confirmedAt, startDate, endDate, createdAt };
}

// 서버 보관함 응답 → 화면 상태(slots + draftChecklist). 클라이언트 id는 서버 발급 숫자와
// 구분되게 chk-srv- 프리픽스를 붙인다(고정 상태 status/confirmedAt도 그대로 복원).
function fromPlanResponse(plan) {
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

// 완료 진행률(완료/전체 개수) — 체크리스트 헤더 진행 바와 보관함 목록 행에서 함께 쓴다.
// tasks가 비정상이어도 화면이 죽지 않게 방어적으로 계산한다.
function getPlanProgress(tasks) {
  const all = Object.values(tasks || {}).flatMap((list) => (Array.isArray(list) ? list : []));
  return { done: all.filter((t) => t.completed).length, total: all.length };
}

// 보관함 목록 행의 저장 시각 표기(M/D 저장). 비정상 값이면 빈 문자열.
function formatSavedAt(ts) {
  const d = new Date(ts);
  return Number.isFinite(d.getTime()) ? `${d.getMonth() + 1}/${d.getDate()} 저장` : '';
}

// 미완료 이월 — fromDate의 미완료 항목을 toDate 배열 뒤에 붙인다. 항목 ID는 보존한다:
// 생성 시점에 계획 전체에서 유일하고, ID가 유지되어야 서버 변경 이력이 "같은 할 일의 이동"으로
// 인식한다. fromDate가 비면 키를 지우고, 새 키가 객체 끝에 붙어 Day 순서가 깨지지 않도록
// 날짜 키 오름차순(YYYY-MM-DD라 사전순=날짜순)으로 재조립한다.
function carryOverTasks(tasks, fromDate, toDate) {
  const dayTasks = tasks?.[fromDate];
  if (!Array.isArray(dayTasks)) return { tasks, movedCount: 0 };
  const remaining = dayTasks.filter((t) => t.completed);
  const moved = dayTasks.filter((t) => !t.completed);
  if (moved.length === 0) return { tasks, movedCount: 0 };

  const destination = Array.isArray(tasks[toDate]) ? tasks[toDate] : [];
  // 목적지에 같은 ID가 이미 있으면(비정상 데이터) 접미사로 회피 — 렌더 key 충돌 방지.
  const existingIds = new Set(destination.map((t) => t.id));
  const movedSafe = moved.map((t) => (existingIds.has(t.id) ? { ...t, id: `${t.id}-m${Date.now()}` } : t));

  const next = { ...tasks, [toDate]: [...destination, ...movedSafe] };
  if (remaining.length > 0) next[fromDate] = remaining;
  else delete next[fromDate];

  const sorted = {};
  Object.keys(next).sort().forEach((date) => { sorted[date] = next[date]; });
  return { tasks: sorted, movedCount: moved.length };
}

// 변경 이력 이벤트 타입 → 화면 라벨. 알 수 없는 타입은 코드 그대로 노출(회고 라벨과 같은 방어).
const AUDIT_EVENT_LABELS = {
  PLAN_CREATED: '계획 생성',
  PLAN_UPDATED: '계획 수정',
  PLAN_CONFIRMED: '계획 고정',
  TASK_COMPLETED: '할 일 완료',
  TASK_REOPENED: '완료 해제',
  REFLECTION_SAVED: '회고 저장',
  PLAN_DELETED: '계획 삭제'
};

// 이력 행의 세션 배지 — 내 세션 ID와 비교해 "다른 세션에서 발생한 변경인가?"에 답한다.
// sessionId가 없으면(구형 클라이언트·curl) "알 수 없음".
function auditSessionBadge(sessionId) {
  if (!sessionId) return '알 수 없음';
  return sessionId === getSessionId() ? '이 브라우저' : '다른 세션';
}

// 이력 행의 발생 시각 — 가까운 과거는 상대 표기, 오래되면 절대 표기(M/D HH:mm).
function formatEventTime(iso) {
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

// 마운트 시 최초 상태 — 항상 슬롯필링 첫 질문으로 시작한다. 계획은 서버 보관함에 있으므로
// "마지막으로 보던 계획" 복원은 마운트 후 목록 fetch가 끝난 시점에 비동기로 수행된다.
function buildInitialState() {
  const nextSlot = getNextEmptySlot(INITIAL_SLOTS);
  return {
    slots: { ...INITIAL_SLOTS },
    draftChecklist: null,
    currentSlot: nextSlot,
    messages: [{ id: 'bot-init-first', sender: 'bot', text: getNextQuestion(nextSlot) }]
  };
}


// 고유 ID 생성 유틸리티 (중복 Key 방지)
const generateUniqueId = (prefix = 'msg') => {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
};

// navigator.clipboard는 보안 컨텍스트(HTTPS/localhost)에서만 동작한다.
// 이 프로젝트의 데모 배포는 평문 HTTP라 그 API가 없을 수 있어, 구형 execCommand로 폴백한다.
async function copyTextToClipboard(text) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // 아래 폴백으로 계속 진행
    }
  }
  try {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    return ok;
  } catch {
    return false;
  }
}

const quickReplyButtonStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '4px',
  padding: '6px 10px',
  fontSize: '12px',
  border: '1px solid var(--border)',
  borderRadius: '999px',
  background: 'var(--bg-card)',
  color: 'var(--text-main)',
  cursor: 'pointer'
};

const exportButtonStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '4px',
  padding: '5px 9px',
  fontSize: '12px',
  border: '1px solid var(--border)',
  borderRadius: '6px',
  background: 'var(--bg-card)',
  color: 'var(--text-main)',
  cursor: 'pointer'
};

export default function ChatCoach() {
  // 지연 초기화 함수 하나로 최초 상태(저장된 계획 복원 또는 첫 질문)를 한 번만 계산한다.
  const [initial] = useState(buildInitialState);
  const [slots, setSlots] = useState(initial.slots);
  const [messages, setMessages] = useState(initial.messages);
  const [inputValue, setInputValue] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const [draftChecklist, setDraftChecklist] = useState(initial.draftChecklist);
  const [currentSlot, setCurrentSlot] = useState(initial.currentSlot);
  const [isThinking, setIsThinking] = useState(false);
  const [elapsedTime, setElapsedTime] = useState(0);
  const [thinkingStatus, setThinkingStatus] = useState('');
  const [copyFeedback, setCopyFeedback] = useState(null); // null | 'ok' | 'error'

  // 서버 보관함 상태 — 계획은 서버 인메모리(휘발성)에 보관되고 모든 방문자가 목록을 공유한다.
  const [activePlanId, setActivePlanId] = useState(null); // 현재 화면 계획의 서버 ID (null = 미보관)
  const [savedPlans, setSavedPlans] = useState([]); // GET /plans 결과 (최근 저장순)
  const [plansStatus, setPlansStatus] = useState('idle'); // 'idle' | 'loading' | 'ready' | 'error'
  const [showPlanList, setShowPlanList] = useState(false);
  // 변경 이력 뷰 — 한 번에 한 계획만 펼친다. null | { planId, status: 'loading'|'ready'|'error', events }
  const [auditView, setAuditView] = useState(null);

  // 오늘 마무리(회고) 상태 — 회고는 계획별·오늘 날짜 1건(서버 업서트, 계획 보관함과 같은
  // 휘발성 공유 저장소). reflections는 {planId: 회고|null} — null은 "오늘 회고 없음"이 서버로
  // 확인된 상태, 키 자체가 없으면 아직 안 불러온 상태다. reflectionErrors는 불러오기 실패한
  // planId 표시(재시도 행), reflectionDrafts는 작성/수정 중 선택({difficulty, reason, editing, saving}).
  const [showReflection, setShowReflection] = useState(false);
  const [reflections, setReflections] = useState({});
  const [reflectionErrors, setReflectionErrors] = useState({});
  const [reflectionDrafts, setReflectionDrafts] = useState({});

  const chatEndRef = useRef(null);
  const thinkingTimerRef = useRef(null);
  const statusTimerRef = useRef(null);
  const hasInteractedRef = useRef(false); // 비동기 자동 복원이 사용자의 새 입력을 덮어쓰지 않게
  const syncTimerRef = useRef(null); // 서버 자동 동기화 디바운스 타이머
  const dirtyRef = useRef(null); // 아직 서버에 반영 안 된 최신 변경 { id, payload }
  const lastSyncedRef = useRef(null); // 서버에 있는 것으로 아는 payload의 JSON — 불필요한 재전송(no-op PUT) 억제
  const archivePendingRef = useRef(false); // 초안이 아직 보관되지 못해(서버 미가용) 재시도가 필요한 상태

  // 계획 고정 여부 — "계획 저장"을 누르면 CONFIRMED가 되어, 이후에는 대화로 계획을
  // 수정할 수 없다(강제성 부여: 확정한 계획은 실행만, 재협상 없음). 완료 체크는 계속 가능.
  const isLocked = draftChecklist?.status === 'CONFIRMED';

  // 스크롤 자동으로 아래로 내리기
  const scrollToBottom = () => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages, isTyping, isThinking]);

  // 생각 중 타이머 해제 cleanup
  useEffect(() => {
    return () => {
      if (thinkingTimerRef.current) clearInterval(thinkingTimerRef.current);
      if (statusTimerRef.current) clearInterval(statusTimerRef.current);
    };
  }, []);

  // 보관함 목록 갱신 — 실패해도 앱은 계속 동작한다(목록만 error 표시, 생성/대화는 mock 폴백).
  const refreshPlans = async () => {
    setPlansStatus('loading');
    try {
      setSavedPlans(await fetchPlans());
      setPlansStatus('ready');
    } catch {
      setPlansStatus('error');
    }
  };

  // 대기 중인 자동 동기화(디바운스 타이머·미반영 변경·재보관 대기)를 모두 취소한다.
  // 활성 계획을 삭제할 때 호출해, 방금 지운 계획이 뒤늦은 PUT의 404→재생성으로 되살아나지 않게 한다.
  const cancelPendingSync = () => {
    if (syncTimerRef.current) {
      clearTimeout(syncTimerRef.current);
      syncTimerRef.current = null;
    }
    dirtyRef.current = null;
    archivePendingRef.current = false;
  };

  // 대기 중인 변경을 즉시 서버에 반영한다(디바운스를 기다리지 않고). 계획 전환·리셋 직전에
  // 호출해, 아직 PUT되지 않은 완료 토글/수정이 유실되지 않게 한다.
  // recreateIfMissing=false: 떠나는 계획이 이미 삭제됐어도 새로 만들지 않는다(orphan 방지).
  const syncActivePlan = async ({ recreateIfMissing }) => {
    if (syncTimerRef.current) {
      clearTimeout(syncTimerRef.current);
      syncTimerRef.current = null;
    }
    const pending = dirtyRef.current;
    if (!pending) return;
    dirtyRef.current = null;
    try {
      await updatePlan(pending.id, pending.payload);
      lastSyncedRef.current = JSON.stringify(pending.payload);
    } catch (err) {
      if (err.code !== 'PLAN_NOT_FOUND') {
        console.warn('계획 동기화 실패 — 다음 변경 때 다시 시도합니다:', err);
        dirtyRef.current = pending; // 일시 오류: 되돌려 놔 다음 변경/전환에서 재시도
        return;
      }
      if (!recreateIfMissing) return;
      // 백그라운드 동기화 중 대상이 사라짐(다른 방문자 삭제·서버 재시작) — 작업을 잃지 않게 새로 보관.
      try {
        const saved = await createPlan(pending.payload);
        lastSyncedRef.current = JSON.stringify(pending.payload);
        setActivePlanId(saved.id);
        writeLastViewedPlanId(saved.id);
      } catch {
        setActivePlanId(null);
        clearLastViewedPlanId();
      }
    }
  };

  // 보관함의 계획을 화면 상태로 복원한다 — 새로고침 복원(restored)과 목록 전환(switched) 공용.
  // messages를 안내 말풍선 하나로 교체하는 이유: 이전 계획에 대한 대화 이력이 LLM history
  // (최근 6턴)에 섞여 새 계획 수정을 오염시키는 것을 차단하기 위해서다.
  const restorePlan = (plan, kind) => {
    const { slots: restoredSlots, draftChecklist: restoredDraft } = fromPlanResponse(plan);
    const goalName = plan.goalName || '계획';
    const locked = restoredDraft.status === 'CONFIRMED';
    // 방금 서버에서 읽은 상태이므로 "이미 동기화됨"으로 기록 — 복원 직후 no-op PUT을 막는다.
    lastSyncedRef.current = JSON.stringify(toPlanPayload(restoredDraft));
    dirtyRef.current = null;
    archivePendingRef.current = false;
    setSlots(restoredSlots);
    setDraftChecklist(restoredDraft);
    setCurrentSlot(null);
    setInputValue('');
    setActivePlanId(plan.id);
    writeLastViewedPlanId(plan.id);
    setShowPlanList(false);
    setMessages([
      {
        id: generateUniqueId(kind === 'restored' ? 'bot-restored' : 'bot-switched'),
        sender: 'bot',
        text: kind === 'restored'
          ? (locked
            ? `저장(고정)된 "${goalName}" 계획을 서버 보관함에서 불러왔습니다. 고정된 계획은 대화로 수정할 수 없어요 — 오른쪽 체크리스트에서 완료 체크를 이어가세요.`
            : `이전에 보던 "${goalName}"을 서버 보관함에서 불러왔습니다. 오른쪽 체크리스트를 확인해 주세요. 계속 대화로 수정할 수 있어요.`)
          : (locked
            ? `보관함의 "${goalName}"을 불러왔습니다. 이 계획은 고정되어 대화로 수정할 수 없어요 — 완료 체크만 가능합니다.`
            : `보관함의 "${goalName}"을 불러왔습니다. 계속 대화로 수정할 수 있어요.`)
      }
    ]);
  };

  // 마운트 시 1회 — 보관함 목록을 불러오고, "마지막으로 보던 계획" 포인터가 유효하면 복원한다.
  // 서버가 죽어 있어도 새 계획 생성·대화는 mock 폴백으로 계속 가능해야 하므로 실패는 삼킨다.
  // StrictMode 이중 실행에도 멱등(같은 목록/같은 복원)이고, cancelled 플래그로 늦은 응답을 무시한다.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const plans = await fetchPlans();
        if (cancelled) return;
        setSavedPlans(plans);
        setPlansStatus('ready');
        const lastId = readLastViewedPlanId();
        const found = lastId != null && plans.find((p) => String(p.id) === lastId);
        if (found && !hasInteractedRef.current) {
          restorePlan(found, 'restored');
        } else if (lastId != null && !found) {
          clearLastViewedPlanId(); // 다른 방문자가 지웠거나 서버가 재시작된 경우
        }
      } catch {
        if (!cancelled) setPlansStatus('error');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // 초안이 완성되면(또는 서버 미가용으로 실패했던 보관을 재시도할 때) 서버 보관함에 등록한다.
  // 성공하면 활성 계획이 되고, 서버가 죽어 있으면 재시도 대기 상태로, 한도 초과면 안내만 한다.
  const archiveNewPlan = async (checklist) => {
    const payload = toPlanPayload(checklist);
    try {
      const saved = await createPlan(payload);
      archivePendingRef.current = false;
      lastSyncedRef.current = JSON.stringify(payload); // 방금 보관 → no-op PUT 억제
      setActivePlanId(saved.id);
      writeLastViewedPlanId(saved.id);
      refreshPlans();
    } catch (err) {
      if (err.code === 'PLAN_LIMIT_EXCEEDED') {
        archivePendingRef.current = false; // 한도 초과는 재시도해도 소용없으니 포기하고 안내만
        setMessages((prev) => [
          ...prev,
          {
            id: generateUniqueId('bot'),
            sender: 'bot',
            text: '⚠️ 보관함이 가득 차서 이 계획은 서버에 보관되지 않았어요. 오른쪽 "보관된 계획" 목록에서 오래된 계획을 삭제하면 다음 계획부터 다시 보관됩니다.'
          }
        ]);
      } else {
        // 서버 미가용 등 일시 오류 — 재시도 대기로 표시해, 서버 복구 후 다음 변경 때 다시 보관한다.
        archivePendingRef.current = true;
        console.warn('계획 보관 실패(서버 미가용?) — 다음 변경 때 재시도합니다:', err);
      }
    }
  };

  // 자동 동기화 — 계획 변경(대화 수정·완료 토글·고정)을 600ms 디바운스로 서버에 반영한다.
  // 다른 브라우저에서 목록을 열면 진행률·고정 상태가 갱신되어 보인다(원격 시연 핵심).
  // 이미 서버에 있는 내용과 같으면(복원/보관 직후) 아무것도 보내지 않는다(no-op PUT 억제).
  // cleanup에서 타이머를 지우지 않는 이유: dep 변경(전환 등)에 취소되면 대기 중 변경이 유실되기
  // 때문. 전환/리셋은 syncActivePlan으로 먼저 flush하고, 삭제는 cancelPendingSync로 취소한다.
  useEffect(() => {
    if (!draftChecklist) return;
    const payloadStr = JSON.stringify(toPlanPayload(draftChecklist));
    if (payloadStr === lastSyncedRef.current) {
      // 현재 상태가 서버와 동일(복원/보관 직후, 또는 토글을 되돌림) — 대기 중이던 이전 변경도
      // 무의미하니 함께 취소한다(낡은 상태가 뒤늦게 PUT되는 것을 막는다).
      if (syncTimerRef.current) {
        clearTimeout(syncTimerRef.current);
        syncTimerRef.current = null;
      }
      dirtyRef.current = null;
      return;
    }

    if (activePlanId != null) {
      dirtyRef.current = { id: activePlanId, payload: JSON.parse(payloadStr) };
      if (syncTimerRef.current) clearTimeout(syncTimerRef.current);
      syncTimerRef.current = setTimeout(() => {
        syncTimerRef.current = null;
        syncActivePlan({ recreateIfMissing: true });
      }, SYNC_DEBOUNCE_MILLIS);
    } else if (archivePendingRef.current) {
      // 미보관 초안(이전 보관 실패) — 변경이 생기면 서버가 살아났는지 다시 시도한다.
      if (syncTimerRef.current) clearTimeout(syncTimerRef.current);
      const snapshot = draftChecklist;
      syncTimerRef.current = setTimeout(() => {
        syncTimerRef.current = null;
        archiveNewPlan(snapshot);
      }, SYNC_DEBOUNCE_MILLIS);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftChecklist, activePlanId]);

  const startThinking = () => {
    setIsThinking(true);
    setElapsedTime(0);

    const statuses = [
      "요청 사항 분석 중...",
      "지연 행동 유형 분류 중...",
      "CBT 맞춤 극복 전략 수립 중...",
      "일자별 집중 분량 설계 중...",
      "하루 가용 시간 분배 중...",
      "일정표 조립 진행 중..."
    ];
    setThinkingStatus(statuses[0]);

    // 1초 주기 타이머
    thinkingTimerRef.current = setInterval(() => {
      setElapsedTime(prev => prev + 1);
    }, 1000);

    // 1.5초 주기 멘트 순환
    let index = 1;
    statusTimerRef.current = setInterval(() => {
      setThinkingStatus(statuses[index % statuses.length]);
      index++;
    }, 1500);
  };

  const stopThinking = () => {
    setIsThinking(false);
    if (thinkingTimerRef.current) {
      clearInterval(thinkingTimerRef.current);
      thinkingTimerRef.current = null;
    }
    if (statusTimerRef.current) {
      clearInterval(statusTimerRef.current);
      statusTimerRef.current = null;
    }
  };

  // 실제 전송 로직 — 입력창 텍스트뿐 아니라 빠른 선택 버튼(기간/시간 프리셋,
  // "기간 늘리기" 등)에서도 재사용한다.
  const sendMessage = async (rawText) => {
    const userText = (rawText || '').trim();
    if (!userText) return;

    // 사용자가 이미 대화를 시작했으면, 뒤늦게 도착한 자동 복원이 입력을 덮어쓰지 않게 한다.
    hasInteractedRef.current = true;

    const userMsgId = generateUniqueId('user');

    // 1. 유저 메시지 추가
    setMessages((prev) => [...prev, { id: userMsgId, sender: 'user', text: userText }]);
    setInputValue('');
    setIsTyping(true);

    try {
      // 초안 생성 이후에는 자유 대화 모드 — LLM이 의도(수정/질문/불명확)를 직접 판단한다.
      // 무조건 재생성하고 "반영했습니다"라고 답하던 이전 방식을 대체한다.
      if (draftChecklist) {
        // 고정(CONFIRMED) 계획의 수정 요청은 AI를 호출하기 전에 막는다 — 예전엔 AI를 먼저
        // 부르고 답변을 스트리밍한 뒤에야 🔒 안내를 띄워 호출이 낭비되고 답변과 안내가
        // 뒤섞였다. 확실한 수정 요청("주말은 빼줘" 등)만 차단하고, 질문은 그대로 통과시킨다.
        if (isLocked && isPlanModificationRequest(userText)) {
          setMessages((prev) => [
            ...prev,
            {
              id: generateUniqueId('bot'),
              sender: 'bot',
              text: '🔒 이 계획은 저장(고정)되어 수정이 반영되지 않았어요. 확정한 계획은 그대로 실행해 보세요! 정말 바꿔야 한다면 "처음부터 다시 만들기"로 새 계획을 세울 수 있어요.'
            }
          ]);
          return;
        }

        startThinking();

        // 최근 대화 이력(방금 보낸 메시지 제외)을 role/content 형태로 전달해
        // "반영 안됐는데?" 같은 맥락 의존 발화도 이해할 수 있게 한다. (토큰 절약: 최근 6턴)
        const history = messages.slice(-6).map((msg) => ({
          role: msg.sender === 'user' ? 'user' : 'assistant',
          content: msg.text
        }));

        // 답변 말풍선을 미리 만들지 않고, 첫 토큰이 도착하는 순간 생성해 실시간으로 채운다.
        // (thinking 표시 → 첫 토큰에서 말풍선으로 전환 → 이후 토큰마다 갱신)
        const botMsgId = generateUniqueId('bot');
        let botCreated = false;
        const onToken = (fullText) => {
          if (!botCreated) {
            botCreated = true;
            stopThinking();
            setMessages((prev) => [...prev, { id: botMsgId, sender: 'bot', text: fullText }]);
          } else {
            setMessages((prev) => prev.map((m) => (m.id === botMsgId ? { ...m, text: fullText } : m)));
          }
        };

        const { reply, updatedDraft } = await streamChatWithCoach(
          slots, draftChecklist, history, userText, onToken
        );

        stopThinking();
        // 최종 reply로 말풍선을 확정한다(스트림 미사용 mock 폴백이거나, 마지막에 기본 문구로
        // 대체된 경우까지 일관되게 반영).
        if (!botCreated) {
          setMessages((prev) => [...prev, { id: botMsgId, sender: 'bot', text: reply }]);
        } else {
          setMessages((prev) => prev.map((m) => (m.id === botMsgId ? { ...m, text: reply } : m)));
        }

        if (updatedDraft) {
          // 고정된 계획은 수정을 반영하지 않는다 — LLM/mock이 수정안을 만들어 와도 버리고,
          // 고정 사실을 안내한다(확정한 계획에 강제성 부여).
          if (isLocked) {
            setMessages((prev) => [
              ...prev,
              {
                id: generateUniqueId('bot'),
                sender: 'bot',
                text: '🔒 이 계획은 저장(고정)되어 수정이 반영되지 않았어요. 확정한 계획은 그대로 실행해 보세요! 정말 바꿔야 한다면 "처음부터 다시 만들기"로 새 계획을 세울 수 있어요.'
              }
            ]);
            return;
          }
          setDraftChecklist(updatedDraft);
          // 기간 연장/단축처럼 날짜 개수가 바뀌었을 수 있으니 슬롯도 함께 맞춘다
          // (다음 요청의 [Goal] Duration이 실제 계획과 어긋나지 않게).
          if (updatedDraft.duration && updatedDraft.duration !== slots.duration) {
            setSlots((prev) => ({ ...prev, duration: updatedDraft.duration }));
          }
        }
        return;
      }

      // 일반 슬롯필링 대화 상황인 경우
      const parseResult = parseUserMessage(userText, currentSlot);

      if (!parseResult.isValid) {
        // 입력 유효성 실패 -> 경고 메시지
        const replyText = `⚠️ ${parseResult.error || "입력 형식이 맞지 않습니다. 다시 한 번 적어주세요."}`;
        setMessages((prev) => [
          ...prev,
          {
            id: generateUniqueId('bot'),
            sender: 'bot',
            text: replyText
          }
        ]);
        return;
      }

      // 슬롯 값 업데이트
      const updatedSlots = { ...slots, [currentSlot]: parseResult.value };
      setSlots(updatedSlots);

      const nextSlot = getNextEmptySlot(updatedSlots);

      if (nextSlot) {
        // 다음 빈 슬롯이 있으면 질문
        setCurrentSlot(nextSlot);
        const replyText = getNextQuestion(nextSlot);
        setMessages((prev) => [
          ...prev,
          {
            id: generateUniqueId('bot'),
            sender: 'bot',
            text: replyText
          }
        ]);
        return;
      }

      // 모든 슬롯 완비 -> 드래프트 생성
      const botMsgId = generateUniqueId('stream');
      setMessages((prev) => [
        ...prev,
        {
          id: botMsgId,
          sender: 'bot',
          text: "입력하신 정보로 맞춤 계획표를 만들고 있습니다. 잠시만 기다려 주세요."
        }
      ]);

      startThinking();

      // 초안을 Day별로 스트리밍 — 하루가 도착할 때마다 체크리스트를 한 칸씩 채운다.
      let hasStartedStreaming = false;
      const checklist = await streamChecklistDraft(updatedSlots, (partialDraft, dayCount) => {
        if (!hasStartedStreaming) {
          hasStartedStreaming = true;
          stopThinking();
        }
        // 도착한 Day까지의 부분 계획을 즉시 반영(체크리스트가 Day1부터 순차적으로 나타남).
        setDraftChecklist(partialDraft);
        setMessages((prev) =>
          prev.map(msg =>
            msg.id === botMsgId
              ? { ...msg, text: `맞춤 계획표를 만들고 있어요... (Day ${dayCount})` }
              : msg
          )
        );
      });

      stopThinking();
      setDraftChecklist(checklist);
      // 완성된 초안을 서버 보관함에 자동 등록(부분 스트리밍 중에는 등록하지 않는다).
      archiveNewPlan(checklist);
      const replyText = "계획 초안을 완성했습니다. 오른쪽 체크리스트를 확인해 주세요. 수정하고 싶은 부분이 있으면 채팅으로 알려주세요.";
      setMessages((prev) =>
        prev.map(msg =>
          msg.id === botMsgId
            ? { ...msg, text: replyText }
            : msg
        )
      );
    } catch (error) {
      console.error("AI Response error:", error);
      stopThinking();
    } finally {
      setIsTyping(false);
    }
  };

  const handleSendMessage = (e) => {
    e.preventDefault();
    sendMessage(inputValue);
  };

  // 슬롯필링 중 숫자 프리셋 버튼 클릭 — 입력창에 채우는 대신 바로 전송한다.
  const sendQuickReply = (value) => {
    if (isTyping) return;
    sendMessage(String(value));
  };

  // 할 일 완료 토글 — 상태 변경은 자동 저장 effect가 localStorage에 반영한다(서버 저장 없음).
  const toggleTask = (date, taskId) => {
    setDraftChecklist((prev) => {
      if (!prev) return prev;
      const dayTasks = prev.tasks?.[date];
      if (!Array.isArray(dayTasks)) return prev;
      return {
        ...prev,
        tasks: {
          ...prev.tasks,
          [date]: dayTasks.map((t) => (t.id === taskId ? { ...t, completed: !t.completed } : t))
        }
      };
    });
  };

  // 오늘 보기 밴드에서의 완료 토글. 활성 계획이면 기존 toggleTask에 위임한다 —
  // draftChecklist가 단일 진실 원천으로 남아 디바운스 동기화 경로가 그대로 동작한다
  // (사본 분기·동기화 경합 없음). 비활성 계획이면 savedPlans 스냅샷을 낙관적으로 갱신하고
  // 전체 계획을 즉시 PUT한다(백엔드는 부분 갱신이 없음). 디바운스는 두지 않는다 —
  // 단발 클릭이고, 매 호출이 최신 낙관 상태를 읽으므로 연속 토글도 누적 반영된다.
  const toggleTodayTask = async (planId, taskId) => {
    if (planId === activePlanId) {
      toggleTask(todayStr(), taskId);
      return;
    }
    const plan = savedPlans.find((p) => p.id === planId);
    const today = todayStr();
    const dayTasks = plan?.tasks?.[today];
    if (!Array.isArray(dayTasks)) return;
    const updated = {
      ...plan,
      tasks: {
        ...plan.tasks,
        [today]: dayTasks.map((t) => (t.id === taskId ? { ...t, completed: !t.completed } : t))
      }
    };
    setSavedPlans((prev) => prev.map((p) => (p.id === planId ? updated : p)));
    try {
      // PlanResponse는 toPlanPayload가 뽑는 필드를 전부 가지므로 그대로 직렬화할 수 있다.
      // status/confirmedAt이 통과되므로 고정(CONFIRMED) 계획도 고정 상태를 잃지 않는다.
      await updatePlan(planId, toPlanPayload(updated));
    } catch (err) {
      setSavedPlans((prev) => prev.map((p) => (p.id === planId ? plan : p))); // 실패 시 롤백
      if (err.code === 'PLAN_NOT_FOUND') {
        window.alert('이미 삭제된 계획입니다.');
        refreshPlans();
      } else {
        window.alert('완료 상태를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.');
      }
    }
  };

  // 미완료 항목 내일로 이동 — 오늘(실행) 단계의 행동이지만 계획 구조를 바꾸므로 DRAFT 계획
  // 전용이다(고정 계획은 버튼 자체를 숨긴다 — 계획 저장/기간 +3일 버튼과 같은 잠금 관례).
  // 동기화는 toggleTodayTask와 같은 이원화: 활성 계획은 draftChecklist만 갱신하고 기존 600ms
  // 디바운스 PUT에 맡긴다(전환·삭제 시 flush/취소도 기존 경로가 처리). 비활성 계획은 savedPlans
  // 낙관 갱신 + 즉시 PUT + 실패 시 롤백. 내일이 계획 기간을 넘으면 endDate/duration을 하루 늘린다.
  const handleCarryOver = async (planId) => {
    const isCurrent = planId === activePlanId && !!draftChecklist;
    const source = isCurrent ? draftChecklist : savedPlans.find((p) => p.id === planId);
    if (!source || source.status === 'CONFIRMED') return;
    const today = todayStr();
    const tomorrow = todayStr(1);
    const { tasks: movedTasks, movedCount } = carryOverTasks(source.tasks, today, tomorrow);
    if (movedCount === 0) return;
    if (!window.confirm(`미완료 ${movedCount}건을 내일(${tomorrow})로 옮길까요?`)) return;

    const extendsRange = !!source.endDate && source.endDate < tomorrow; // YYYY-MM-DD라 문자열 비교로 충분
    const changes = {
      tasks: movedTasks,
      ...(extendsRange && {
        endDate: tomorrow,
        duration: typeof source.duration === 'number' ? source.duration + 1 : source.duration
      })
    };

    if (isCurrent) {
      setDraftChecklist((prev) => (prev ? { ...prev, ...changes } : prev));
      if (extendsRange) {
        // 요약 헤더·AI 슬롯의 "기간 N일"이 어긋나지 않게 함께 갱신(채팅 기간 수정과 동일 처리).
        setSlots((prev) => ({ ...prev, duration: changes.duration }));
      }
      return; // 서버 반영은 자동 동기화 effect가 담당
    }

    const updated = { ...source, ...changes };
    setSavedPlans((prev) => prev.map((p) => (p.id === planId ? updated : p)));
    try {
      await updatePlan(planId, toPlanPayload(updated));
    } catch (err) {
      setSavedPlans((prev) => prev.map((p) => (p.id === planId ? source : p))); // 실패 시 롤백
      if (err.code === 'PLAN_NOT_FOUND') {
        window.alert('이미 삭제된 계획입니다.');
        refreshPlans();
      } else {
        window.alert('미완료 항목을 옮기지 못했습니다. 잠시 후 다시 시도해 주세요.');
      }
    }
  };

  const handleCopyPlan = async () => {
    const text = formatChecklistAsText(draftChecklist);
    const ok = await copyTextToClipboard(text);
    setCopyFeedback(ok ? 'ok' : 'error');
    // 폴백(execCommand)까지 실패하면 버튼의 "복사 실패" 표시만으로는 다음 행동을 알 수 없다.
    // HTTP(비보안) 배포에서 브라우저가 복사를 막는 경우이므로, 대안을 봇 메시지로 명확히 안내한다.
    if (!ok) {
      setMessages((prev) => [
        ...prev,
        {
          id: generateUniqueId('bot'),
          sender: 'bot',
          text: '⚠️ 이 환경에서는 자동 복사가 제한돼요(HTTPS가 아닌 배포에서 브라우저가 클립보드를 막는 경우). 체크리스트의 "다운로드" 버튼으로 .txt 파일로 저장하거나, 계획 텍스트를 직접 선택해 복사해 주세요.'
        }
      ]);
    }
    setTimeout(() => setCopyFeedback(null), 1800);
  };

  const handleDownloadPlan = () => {
    const text = formatChecklistAsText(draftChecklist);
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const safeName = (draftChecklist?.goalName || 'plan').replace(/[\\/:*?"<>|]/g, '').slice(0, 30);
    a.href = url;
    a.download = `delaynomore-${safeName}.txt`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  // 계획 저장 = 고정(CONFIRMED) — 이후 대화로는 계획을 수정할 수 없게 된다.
  // 단순 보관이 아니라 "이 계획대로 실행하겠다"는 확정 행위라, 실수 방지 확인 창을 띄운다.
  // (영속화는 서버 자동 동기화 effect가 담당하므로 여기서는 상태 전환만 한다 — 고정 상태도
  //  서버 보관함에 반영되어 다른 방문자 목록에 🔒로 표시된다.)
  const handleSavePlan = () => {
    if (!draftChecklist || isLocked || isTyping) return;
    if (!window.confirm('계획을 저장하면 고정되어 대화로는 더 이상 수정할 수 없습니다. 이 계획으로 확정할까요?')) return;
    setDraftChecklist((prev) => (prev ? { ...prev, status: 'CONFIRMED', confirmedAt: new Date().toISOString() } : prev));
    setMessages((prev) => [
      ...prev,
      {
        id: generateUniqueId('bot'),
        sender: 'bot',
        text: '🔒 계획을 저장하고 고정했습니다! 이제 대화로는 수정할 수 없어요 — 오른쪽 체크리스트를 하나씩 완료해 나가세요. 궁금한 점은 계속 물어보셔도 됩니다.'
      }
    ]);
  };

  // 처음부터 다시 만들기 — 새 계획의 슬롯필링을 시작한다. 현재 계획은 서버 보관함에 그대로
  // 남으므로(활성 포인터만 해제) 언제든 목록에서 다시 불러올 수 있다. 고정된 계획을 바꾸고
  // 싶을 때의 탈출구이기도 하다: 새 계획을 만들고, 필요하면 목록에서 이전 계획을 삭제한다.
  // 요청이 진행 중일 때 리셋하면 나중에 도착하는 응답이 새 상태를 덮어써버릴 수 있어 막는다.
  const handleResetPlan = async () => {
    if (isTyping) return;
    if (draftChecklist && !window.confirm('현재 계획은 보관함에 남고, 새 계획을 처음부터 시작합니다. 계속할까요?')) return;
    // 떠나기 전에 대기 중 변경(완료 토글 등)을 마저 저장한다 — 보관함에 남길 것이므로 유실 금지.
    await syncActivePlan({ recreateIfMissing: false });
    // 떠나는 계획의 스냅샷도 라이브 상태로 최신화(오늘 보기가 낡은 값을 보이지 않게).
    if (activePlanId != null && draftChecklist) {
      const leavingId = activePlanId;
      setSavedPlans((prev) => prev.map((p) => (p.id === leavingId ? { ...p, ...toPlanPayload(draftChecklist) } : p)));
    }
    setActivePlanId(null);
    clearLastViewedPlanId();
    setShowPlanList(false);
    setSlots({ ...INITIAL_SLOTS });
    setDraftChecklist(null);
    setInputValue('');
    const nextSlot = getNextEmptySlot(INITIAL_SLOTS);
    setCurrentSlot(nextSlot);
    setMessages([
      { id: generateUniqueId('bot-reset'), sender: 'bot', text: getNextQuestion(nextSlot) }
    ]);
  };

  // 보관함 목록에서 계획 선택 — 전환 시점의 최신본을 서버에서 다시 읽는다(다른 방문자의
  // 수정·진행이 반영되도록). 현재 계획은 자동 동기화로 이미 서버에 있으니 확인 창은 불필요.
  const handleLoadPlan = async (planId) => {
    if (isTyping) return;
    if (planId === activePlanId) {
      setShowPlanList(false);
      return;
    }
    // 전환 전에 현재 계획의 대기 중 변경을 마저 저장한다(디바운스가 취소돼 유실되지 않게).
    await syncActivePlan({ recreateIfMissing: false });
    // 떠나는 계획의 savedPlans 스냅샷을 라이브 상태로 최신화한다 — 활성일 때는 오늘 보기가
    // draftChecklist를 읽어 가려지지만, 비활성이 되는 순간 낡은 스냅샷이 드러나기 때문.
    if (activePlanId != null && draftChecklist) {
      const leavingId = activePlanId;
      setSavedPlans((prev) => prev.map((p) => (p.id === leavingId ? { ...p, ...toPlanPayload(draftChecklist) } : p)));
    }
    try {
      const plan = await fetchPlan(planId);
      restorePlan(plan, 'switched');
      // 방금 서버에서 읽은 최신본으로 대상 행 스냅샷도 갱신(다른 방문자의 변경 반영).
      setSavedPlans((prev) => prev.map((p) => (p.id === plan.id ? plan : p)));
    } catch (err) {
      if (err.code === 'PLAN_NOT_FOUND') {
        window.alert('이미 삭제된 계획입니다.');
        refreshPlans();
      } else {
        window.alert('계획을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
      }
    }
  };

  // 보관함에서 계획 삭제 — 공유 저장소라 모든 방문자의 목록에서 사라진다.
  // 이미 지워진 경우(404)는 성공으로 취급하고 목록만 갱신한다.
  const handleDeletePlan = async (planId) => {
    const entry = savedPlans.find((p) => p.id === planId);
    const goalName = entry?.goalName || '계획';
    if (!window.confirm(`"${goalName}" 계획을 보관함에서 삭제할까요? 모든 방문자의 목록에서 사라집니다.`)) return;
    if (planId === activePlanId) {
      // 대기 중 자동 동기화를 먼저 취소한다 — 그러지 않으면 뒤늦은 PUT이 404→재생성으로
      // 방금 지운 계획을 새 id로 되살릴 수 있다. 화면 계획은 그대로 두되 미보관 상태로 전환.
      cancelPendingSync();
      setActivePlanId(null);
      clearLastViewedPlanId();
    }
    try {
      await deletePlan(planId);
    } catch (err) {
      if (err.code !== 'PLAN_NOT_FOUND') {
        window.alert('계획을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.');
        return;
      }
    }
    // 삭제된 계획의 이력 패널이 열려 있었다면 함께 닫는다(행이 목록에서 사라지므로).
    setAuditView((prev) => (prev?.planId === planId ? null : prev));
    refreshPlans();
  };

  // 변경 이력 열기/닫기 — 같은 행을 다시 누르면 접는다. 펼칠 때마다 refetch해 다른 세션의
  // 변경이 반영된다(보관함 목록·회고의 "펼칠 때 refetch" 관례). 늦게 도착한 응답은 그 사이
  // 다른 계획으로 전환/닫힘 상태면 무시한다(functional set으로 현재 planId 확인).
  const loadAuditLog = async (planId) => {
    setAuditView({ planId, status: 'loading', events: [] });
    try {
      const events = await fetchAuditEvents(planId);
      setAuditView((prev) => (prev?.planId === planId ? { planId, status: 'ready', events } : prev));
    } catch {
      setAuditView((prev) => (prev?.planId === planId ? { planId, status: 'error', events: [] } : prev));
    }
  };

  const toggleAuditLog = (planId) => {
    if (auditView?.planId === planId) {
      setAuditView(null);
      return;
    }
    loadAuditLog(planId);
  };

  // 전체 기간 늘리기 — 기존 자유 대화 파이프라인(의도 판단/재생성)을 그대로 재사용한다.
  // 계획 수정에 해당하므로 고정된 계획에서는 동작하지 않는다(버튼도 숨김).
  const EXTEND_DAYS = 3;
  const handleExtendDuration = () => {
    if (isTyping || isLocked) return;
    sendMessage(`전체 기간을 ${EXTEND_DAYS}일 더 늘려줘`);
  };

  // 현재 질문의 빠른 답변 선택지 — 하단 구석이 아니라 대화 흐름 안(마지막 말풍선
  // 바로 아래)에 렌더링해, 질문을 읽는 시선 위치에서 바로 선택할 수 있게 한다.
  const slotQuickReplies =
    draftChecklist || isTyping ? [] :
    currentSlot === REQUIRED_SLOTS.DURATION ? DURATION_PRESETS :
    currentSlot === REQUIRED_SLOTS.DAILY_HOURS ? DAILY_HOURS_PRESETS :
    currentSlot === REQUIRED_SLOTS.CURRENT_LEVEL ? LEVEL_PRESETS :
    [];

  // === 왼쪽: 대화 패널 ===
  const chatPanel = (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%' }}>
      <div style={{ padding: '10px 16px', borderBottom: '1px solid var(--border)', fontWeight: 600, fontSize: '14px', flexShrink: 0 }}>
        AI 코치와 대화
      </div>

      {/* 대화 영역 */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '16px', display: 'flex', flexDirection: 'column', gap: '10px', minHeight: 0 }}>
        {messages.map((msg) => (
          <div
            key={msg.id}
            className="animate-fade-in"
            style={{
              display: 'flex',
              justifyContent: msg.sender === 'user' ? 'flex-end' : 'flex-start'
            }}
          >
            <div
              style={{
                maxWidth: '80%',
                padding: '9px 13px',
                borderRadius: '12px',
                lineHeight: '1.5',
                whiteSpace: 'pre-wrap',
                fontSize: '14px',
                background: msg.sender === 'user' ? 'var(--bubble-user)' : 'var(--bubble-bot)',
                color: msg.sender === 'user' ? '#ffffff' : 'var(--text-main)'
              }}
            >
              {msg.text}
            </div>
          </div>
        ))}

        {/* AI 생각 중 표시 */}
        {isThinking && (
          <div className="animate-fade-in" style={{ display: 'flex' }}>
            <div style={{
              padding: '9px 13px',
              borderRadius: '12px',
              background: 'var(--bubble-bot)',
              fontSize: '13px',
              color: 'var(--text-muted)'
            }}>
              분석 중입니다... ({elapsedTime}초) · {thinkingStatus}
            </div>
          </div>
        )}

        {/* 입력 대기 표시 */}
        {isTyping && !isThinking && (
          <div style={{ display: 'flex' }}>
            <div style={{ padding: '9px 13px', borderRadius: '12px', background: 'var(--bubble-bot)', display: 'flex', gap: '4px' }}>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'blink 1.2s infinite' }}></span>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'blink 1.2s infinite', animationDelay: '0.2s' }}></span>
              <span style={{ width: '6px', height: '6px', background: 'var(--text-muted)', borderRadius: '50%', animation: 'blink 1.2s infinite', animationDelay: '0.4s' }}></span>
            </div>
          </div>
        )}

        {/* 현재 질문의 빠른 답변 — 마지막 봇 말풍선 아래(대화 흐름 안)에 표시 */}
        {slotQuickReplies.length > 0 && (
          <div className="animate-fade-in" style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
            {slotQuickReplies.map((label) => (
              <button
                key={label}
                type="button"
                onClick={() => sendQuickReply(label)}
                style={quickReplyButtonStyle}
              >
                {label}
              </button>
            ))}
          </div>
        )}

        <div ref={chatEndRef} />
      </div>

      {/* 계획 생성 후 빠른 동작 — 저장(고정) / 전체 기간 늘리기 / 처음부터 다시 만들기.
          고정 후에는 수정 계열 버튼(저장·기간 늘리기)을 숨기고 고정 표시만 남긴다. */}
      {draftChecklist && (
        <div style={{ padding: '0 16px 10px', display: 'flex', gap: '6px', flexWrap: 'wrap', flexShrink: 0 }}>
          {isLocked ? (
            <span style={{ ...quickReplyButtonStyle, cursor: 'default', color: 'var(--text-muted)' }}>
              <Lock size={12} />
              고정된 계획 — 대화 수정 불가
            </span>
          ) : (
            <>
              <button type="button" onClick={handleSavePlan} disabled={isTyping} style={quickReplyButtonStyle}>
                <Save size={12} />
                계획 저장(고정)
              </button>
              <button type="button" onClick={handleExtendDuration} disabled={isTyping} style={quickReplyButtonStyle}>
                <CalendarPlus size={12} />
                기간 +{EXTEND_DAYS}일
              </button>
            </>
          )}
          <button type="button" onClick={handleResetPlan} disabled={isTyping} style={quickReplyButtonStyle}>
            <RotateCcw size={12} />
            처음부터 다시 만들기
          </button>
        </div>
      )}

      {/* 입력바 */}
      <form
        onSubmit={handleSendMessage}
        style={{
          padding: '12px 16px',
          borderTop: '1px solid var(--border)',
          display: 'flex',
          gap: '8px',
          flexShrink: 0
        }}
      >
        <input
          type="text"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          placeholder={
            isLocked ? "질문을 입력해 주세요... (계획은 고정되어 수정 불가)" :
            draftChecklist ? "수정 요청이나 질문을 입력해 주세요..." :
            "대답을 입력해 주세요..."
          }
          style={{
            flex: 1,
            padding: '10px 12px',
            background: 'var(--bg-card)',
            border: '1px solid var(--border)',
            borderRadius: '8px',
            color: 'var(--text-main)',
            outline: 'none',
            fontSize: '14px'
          }}
        />
        <button
          type="submit"
          style={{
            width: '42px',
            height: '42px',
            border: 'none',
            borderRadius: '8px',
            background: 'var(--primary)',
            color: '#ffffff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            cursor: 'pointer',
            flexShrink: 0
          }}
        >
          <Send size={16} />
        </button>
      </form>
    </div>
  );

  // 전체 진행률(완료/전체 개수) — 헤더 요약과 진행 바에 함께 쓴다.
  const { done: completedCount, total: totalCount } = getPlanProgress(draftChecklist?.tasks);

  // === 오늘 보기 집계 — 모든 "보관된" 계획에서 오늘 날짜의 할 일만 모은다 ===
  // tasks 맵 키가 실제 로컬 날짜(YYYY-MM-DD)라 todayStr()과 문자열 비교로 충분하다.
  // 활성 계획은 라이브 상태(draftChecklist)에서 읽는다 — 서버 스냅샷은 600ms 디바운스
  // 동기화 전이라 낡을 수 있고, 같은 계획이 두 번 보이는 것도 막는다. 미보관 초안
  // (activePlanId가 null)은 스펙대로 제외한다(보관 계획만 대상).
  const today = todayStr();
  const todayGroups = useMemo(() => (
    savedPlans
      .map((plan) => {
        const isCurrent = plan.id === activePlanId && !!draftChecklist;
        const source = isCurrent ? draftChecklist : plan;
        const dayTasks = source.tasks?.[today];
        return {
          planId: plan.id,
          goalName: source.goalName || plan.goalName,
          locked: (source.status || 'DRAFT') === 'CONFIRMED',
          isCurrent,
          tasks: Array.isArray(dayTasks) ? dayTasks : []
        };
      })
      .filter((g) => g.tasks.length > 0)
  ), [savedPlans, activePlanId, draftChecklist, today]);
  const todayDone = todayGroups.reduce((n, g) => n + g.tasks.filter((t) => t.completed).length, 0);
  const todayTotal = todayGroups.reduce((n, g) => n + g.tasks.length, 0);

  // === 오늘 마무리(회고) 핸들러 ===

  // 오늘 회고를 계획별로 불러온다 — 섹션을 펼칠 때마다 호출해 다른 방문자의 회고가 반영된다
  // (보관된 계획 목록의 "펼칠 때 refetch" 패턴과 동일). REFLECTION_NOT_FOUND는 "아직 없음"이
  // 확인된 정상 상태(null 저장)이고, 그 외 오류는 재시도 행을 띄운다.
  const loadReflections = async () => {
    await Promise.all(todayGroups.map(async (group) => {
      try {
        const data = await fetchReflection(group.planId, todayStr());
        setReflections((prev) => ({ ...prev, [group.planId]: data }));
        setReflectionErrors((prev) => ({ ...prev, [group.planId]: false }));
      } catch (err) {
        if (err.code === 'REFLECTION_NOT_FOUND') {
          setReflections((prev) => ({ ...prev, [group.planId]: null }));
          setReflectionErrors((prev) => ({ ...prev, [group.planId]: false }));
        } else {
          setReflectionErrors((prev) => ({ ...prev, [group.planId]: true }));
        }
      }
    }));
  };

  // 작성/수정 중 선택 상태 갱신(난이도·이유 공용).
  const setReflectionDraftField = (planId, field, value) => {
    setReflectionDrafts((prev) => ({ ...prev, [planId]: { ...prev[planId], [field]: value } }));
  };

  // 저장된 회고의 "수정" — 저장값으로 선택을 프리필한 채 폼 뷰로 전환한다.
  const startReflectionEdit = (planId) => {
    const saved = reflections[planId];
    setReflectionDrafts((prev) => ({
      ...prev,
      [planId]: { difficulty: saved?.difficulty || null, reason: saved?.reason || null, editing: true }
    }));
  };

  // 회고 저장(업서트) — 완료 개수는 보내지 않는다(서버가 계획의 오늘 할 일에서 재계산).
  // 성공 응답을 reflections에 반영하면 저장 뷰가 서버 계산 수치로 갱신된다.
  const saveReflection = async (planId) => {
    const draft = reflectionDrafts[planId];
    if (!draft?.difficulty || !draft?.reason || draft.saving) return;
    setReflectionDrafts((prev) => ({ ...prev, [planId]: { ...prev[planId], saving: true } }));
    try {
      const saved = await putReflection(planId, todayStr(), {
        difficulty: draft.difficulty,
        reason: draft.reason
      });
      setReflections((prev) => ({ ...prev, [planId]: saved }));
      setReflectionDrafts((prev) => {
        const next = { ...prev };
        delete next[planId]; // 저장 완료 → 폼 종료(저장 뷰로 전환)
        return next;
      });
    } catch (err) {
      setReflectionDrafts((prev) => ({ ...prev, [planId]: { ...prev[planId], saving: false } }));
      if (err.code === 'PLAN_NOT_FOUND') {
        window.alert('이미 삭제된 계획입니다.');
        refreshPlans();
      } else {
        window.alert(err.message);
      }
    }
  };

  // === 오늘 할 일 패널 (가운데 칸 · 항상 표시) ===
  // 대화/체크리스트와 같은 높이의 세로 칸. 새로고침 버튼으로 보관함을 다시 불러와
  // 다른 기기/방문자의 변경을 반영한다(자동 갱신은 마운트 fetch + 각 조작 경로가 담당).
  const todayPanel = (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%', background: 'var(--bg-card)' }}>
      <div
        style={{
          padding: '10px 16px',
          borderBottom: '1px solid var(--border)',
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          gap: '6px',
          fontSize: '14px',
          fontWeight: 600
        }}
      >
        <Sun size={14} style={{ color: 'var(--primary)' }} />
        오늘 할 일
        {todayTotal > 0 && (
          <span style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: 400 }}>
            오늘 {todayDone}/{todayTotal} 완료
          </span>
        )}
        <button
          type="button"
          onClick={refreshPlans}
          title="오늘 할 일 새로고침"
          style={{ marginLeft: 'auto', display: 'flex', background: 'transparent', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', padding: '4px' }}
        >
          <RefreshCw size={13} />
        </button>
      </div>
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {savedPlans.length === 0 ? (
            <div style={{ fontSize: '13px', color: 'var(--text-muted)', textAlign: 'center', paddingTop: '24px' }}>
              보관된 계획이 아직 없습니다.<br />계획을 만들면 오늘 할 일이 여기에 모여요.
            </div>
          ) : todayGroups.length === 0 ? (
            <div style={{ fontSize: '13px', color: 'var(--text-muted)', textAlign: 'center', paddingTop: '24px' }}>오늘 할 일이 없습니다</div>
          ) : (
            todayGroups.map((group) => (
              <div
                key={group.planId}
                style={{
                  background: 'var(--bg-panel)',
                  borderRadius: '8px',
                  padding: '8px 10px',
                  border: `1px solid ${group.isCurrent ? 'var(--primary)' : 'var(--border)'}`
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '4px', marginBottom: '6px' }}>
                  {group.locked && <Lock size={11} style={{ flexShrink: 0, color: 'var(--primary)' }} />}
                  <span style={{ fontSize: '13px', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {group.goalName}
                  </span>
                  {group.isCurrent && <span style={{ fontSize: '13px', color: 'var(--primary)', flexShrink: 0 }}>· 보는 중</span>}
                  <button
                    type="button"
                    onClick={() => handleLoadPlan(group.planId)}
                    disabled={isTyping}
                    style={{
                      marginLeft: 'auto',
                      flexShrink: 0,
                      display: 'flex',
                      alignItems: 'center',
                      gap: '3px',
                      fontSize: '12px',
                      background: 'transparent',
                      border: 'none',
                      color: 'var(--primary)',
                      cursor: 'pointer',
                      padding: '2px 4px'
                    }}
                  >
                    계획 보기
                    <ArrowRight size={12} />
                  </button>
                </div>
                <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '4px', margin: 0, padding: 0 }}>
                  {group.tasks.map((task) => (
                    // 본문 체크리스트와 동일한 접근성 패턴 — <label>로 감싼 실제 체크박스라
                    // 텍스트 클릭 토글 + Tab 포커스/Space 토글이 네이티브로 동작한다.
                    <li key={task.id} style={{ fontSize: '13px' }}>
                      <label style={{ display: 'flex', gap: '8px', alignItems: 'flex-start', cursor: 'pointer', userSelect: 'none' }}>
                        <input
                          type="checkbox"
                          checked={!!task.completed}
                          onChange={() => toggleTodayTask(group.planId, task.id)}
                          style={{ marginTop: '2px', flexShrink: 0, width: '14px', height: '14px', accentColor: 'var(--primary)', cursor: 'pointer' }}
                        />
                        <span
                          style={{
                            textDecoration: task.completed ? 'line-through' : 'none',
                            color: task.completed ? 'var(--text-muted)' : 'var(--text-main)'
                          }}
                        >
                          {task.content}
                        </span>
                      </label>
                    </li>
                  ))}
                </ul>
                {/* 미완료 이월 — 계획 구조를 바꾸므로 DRAFT 계획에서만, 미완료가 있을 때만 노출.
                    고정(locked) 계획은 숨긴다(잠금 시 수정 버튼을 숨기는 기존 관례). */}
                {!group.locked && group.tasks.some((t) => !t.completed) && (
                  <button
                    type="button"
                    onClick={() => handleCarryOver(group.planId)}
                    disabled={isTyping}
                    style={{ ...quickReplyButtonStyle, marginTop: '8px' }}
                  >
                    <CalendarPlus size={12} />
                    미완료 {group.tasks.filter((t) => !t.completed).length}건 내일로
                  </button>
                )}
              </div>
            ))
          )}
      </div>

      {/* === 오늘 마무리(회고) — 오늘 할 일 패널 하단 접이식 푸터 === */}
      {todayGroups.length > 0 && (
        <div style={{ borderTop: '1px solid var(--border)', flexShrink: 0 }}>
          <button
            type="button"
            onClick={() => {
              const next = !showReflection;
              setShowReflection(next);
              if (next) loadReflections(); // 펼칠 때마다 refetch — 다른 방문자의 회고 반영
            }}
            style={{
              width: '100%',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              padding: '8px 16px',
              fontSize: '13px',
              background: 'transparent',
              border: 'none',
              color: 'var(--text-main)',
              cursor: 'pointer'
            }}
          >
            <CheckCircle2 size={13} style={{ color: 'var(--primary)' }} />
            오늘 마무리
            <span style={{ marginLeft: 'auto', display: 'flex' }}>
              {showReflection ? <ChevronDown size={13} /> : <ChevronUp size={13} />}
            </span>
          </button>
          {showReflection && (
            <div style={{ maxHeight: '280px', overflowY: 'auto', padding: '0 16px 10px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {todayGroups.map((group) => {
                const done = group.tasks.filter((t) => t.completed).length;
                const total = group.tasks.length;
                const rate = total > 0 ? Math.round((done / total) * 100) : 0;
                const loadFailed = reflectionErrors[group.planId] === true;
                const loaded = reflections[group.planId] !== undefined;
                const saved = reflections[group.planId];
                const draft = reflectionDrafts[group.planId] || {};
                const isFormView = loaded && (saved == null || draft.editing === true);
                // 저장 후 완료 체크가 더 바뀌었으면 안내한다(재저장하면 서버가 새 수치로 재계산).
                const countsChanged = saved != null && (saved.completedCount !== done || saved.totalCount !== total);
                return (
                  <div
                    key={group.planId}
                    style={{
                      background: 'var(--bg-panel)',
                      borderRadius: '8px',
                      padding: '8px 10px',
                      border: `1px solid ${group.isCurrent ? 'var(--primary)' : 'var(--border)'}`
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '4px', marginBottom: '4px' }}>
                      {group.locked && <Lock size={11} style={{ flexShrink: 0, color: 'var(--primary)' }} />}
                      <span style={{ fontSize: '13px', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {group.goalName}
                      </span>
                    </div>
                    {/* 자동 계산된 오늘 결과 — todayGroups(라이브 상태)에서 파생되어 완료 체크에 즉시 반영 */}
                    <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                      오늘 {total}개 중 {done}개 완료 · 완료율 {rate}%
                    </div>
                    {loadFailed ? (
                      <button type="button" onClick={loadReflections} style={quickReplyButtonStyle}>
                        <RefreshCw size={12} />
                        회고를 불러오지 못했습니다 · 다시 시도
                      </button>
                    ) : !loaded ? (
                      <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>회고를 불러오는 중...</div>
                    ) : isFormView ? (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                        <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>오늘 하루 어땠나요?</div>
                        <div role="radiogroup" aria-label="체감 난이도" style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                          {DIFFICULTY_OPTIONS.map((opt) => (
                            <button
                              key={opt.code}
                              type="button"
                              role="radio"
                              aria-checked={draft.difficulty === opt.code}
                              onClick={() => setReflectionDraftField(group.planId, 'difficulty', opt.code)}
                              style={{
                                ...quickReplyButtonStyle,
                                ...(draft.difficulty === opt.code
                                  ? { border: '1px solid var(--primary)', color: 'var(--primary)', fontWeight: 600 }
                                  : {})
                              }}
                            >
                              {opt.label}
                            </button>
                          ))}
                        </div>
                        <div role="radiogroup" aria-label="이유" style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                          {REASON_OPTIONS.map((opt) => (
                            <button
                              key={opt.code}
                              type="button"
                              role="radio"
                              aria-checked={draft.reason === opt.code}
                              onClick={() => setReflectionDraftField(group.planId, 'reason', opt.code)}
                              style={{
                                ...quickReplyButtonStyle,
                                ...(draft.reason === opt.code
                                  ? { border: '1px solid var(--primary)', color: 'var(--primary)', fontWeight: 600 }
                                  : {})
                              }}
                            >
                              {opt.label}
                            </button>
                          ))}
                        </div>
                        <button
                          type="button"
                          onClick={() => saveReflection(group.planId)}
                          disabled={!draft.difficulty || !draft.reason || draft.saving}
                          style={{
                            ...quickReplyButtonStyle,
                            alignSelf: 'flex-start',
                            ...(!draft.difficulty || !draft.reason || draft.saving
                              ? { color: 'var(--text-muted)', cursor: 'not-allowed' }
                              : { background: 'var(--primary)', border: '1px solid var(--primary)', color: '#ffffff' })
                          }}
                        >
                          <Save size={12} />
                          {draft.saving ? '저장 중...' : '회고 저장'}
                        </button>
                      </div>
                    ) : (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                        <div style={{ fontSize: '12px', color: 'var(--text-main)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                          <Check size={12} style={{ flexShrink: 0, color: 'var(--primary)' }} />
                          오늘 회고를 저장했습니다.
                        </div>
                        <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                          {saved.completedCount}/{saved.totalCount} 완료
                          {' · '}{reflectionLabel(DIFFICULTY_OPTIONS, saved.difficulty)}
                          {' · '}{reflectionLabel(REASON_OPTIONS, saved.reason)}
                        </div>
                        {countsChanged && (
                          <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                            완료 상태가 바뀌었어요 · 다시 저장하면 {done}/{total}로 갱신됩니다
                          </div>
                        )}
                        <button
                          type="button"
                          onClick={() => startReflectionEdit(group.planId)}
                          style={{ ...quickReplyButtonStyle, alignSelf: 'flex-start' }}
                        >
                          수정
                        </button>
                      </div>
                    )}
                  </div>
                );
              })}
              <div style={{ fontSize: '11px', color: 'var(--text-muted)', textAlign: 'center' }}>
                모든 방문자가 함께 보는 데모 회고입니다 · 다른 방문자가 회고를 수정할 수 있어요 · 서버 재시작 시 초기화됩니다
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );

  // === 보관된 계획 목록 (체크리스트 패널 헤더 아래 접이식 바) ===
  // 서버 공유 보관함이라 폴링 없이 "열 때마다 refetch"로 다른 방문자의 변경을 반영한다.
  const planListBar = (savedPlans.length > 0 || plansStatus === 'error') && (
    <div style={{ borderBottom: '1px solid var(--border)', flexShrink: 0 }}>
      <button
        type="button"
        onClick={() => {
          const next = !showPlanList;
          setShowPlanList(next);
          if (next) refreshPlans();
        }}
        style={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          gap: '6px',
          padding: '8px 16px',
          fontSize: '13px',
          background: 'transparent',
          border: 'none',
          color: 'var(--text-main)',
          cursor: 'pointer'
        }}
      >
        <FolderOpen size={13} />
        보관된 계획{plansStatus === 'error' ? '' : ` ${savedPlans.length}개`}
        <span style={{ marginLeft: 'auto', display: 'flex' }}>
          {showPlanList ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
        </span>
      </button>
      {showPlanList && (
        <div style={{ maxHeight: '240px', overflowY: 'auto', padding: '0 16px 10px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
          {plansStatus === 'error' && (
            <div style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '6px' }}>
              목록을 불러오지 못했습니다.
              <button type="button" onClick={refreshPlans} style={quickReplyButtonStyle}>
                <RefreshCw size={12} />
                다시 시도
              </button>
            </div>
          )}
          {savedPlans.map((plan) => {
            const isCurrent = plan.id === activePlanId;
            // 현재 보고 있는 계획은 라이브 상태(draftChecklist)에서 진행률을 읽는다 — 서버
            // 스냅샷(plan.tasks)은 600ms 디바운스 동기화 전이라, 목록을 펼친 채 체크하면
            // 옛 수치로 남기 때문. 다른(비활성) 계획은 종전대로 서버 스냅샷을 쓴다.
            const { done, total } = getPlanProgress(isCurrent ? draftChecklist?.tasks : plan.tasks);
            const isPlanConfirmed = plan.status === 'CONFIRMED';
            const isAuditOpen = auditView?.planId === plan.id;
            return (
              <React.Fragment key={plan.id}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  background: 'var(--bg-card)',
                  borderRadius: '8px',
                  padding: '8px 10px',
                  border: `1px solid ${isCurrent ? 'var(--primary)' : 'var(--border)'}`
                }}
              >
                <button
                  type="button"
                  onClick={() => handleLoadPlan(plan.id)}
                  disabled={isTyping}
                  style={{
                    flex: 1,
                    minWidth: 0,
                    textAlign: 'left',
                    background: 'transparent',
                    border: 'none',
                    cursor: 'pointer',
                    color: 'var(--text-main)',
                    padding: 0
                  }}
                >
                  <div style={{ fontSize: '13px', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'flex', alignItems: 'center', gap: '4px' }}>
                    {isPlanConfirmed && <Lock size={11} style={{ flexShrink: 0, color: 'var(--primary)' }} />}
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{plan.goalName}</span>
                    {isCurrent && <span style={{ color: 'var(--primary)', fontWeight: 400, flexShrink: 0 }}>· 보는 중</span>}
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                    기간 {plan.duration}일 · 하루 {plan.dailyHours}시간 · {done}/{total} 완료 · {formatSavedAt(plan.savedAt)}
                  </div>
                </button>
                <button
                  type="button"
                  onClick={() => toggleAuditLog(plan.id)}
                  title="변경 이력"
                  style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: isAuditOpen ? 'var(--primary)' : 'var(--text-muted)', padding: '4px', flexShrink: 0, display: 'flex' }}
                >
                  <History size={14} />
                </button>
                <button
                  type="button"
                  onClick={() => handleDeletePlan(plan.id)}
                  title="계획 삭제"
                  style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', padding: '4px', flexShrink: 0, display: 'flex' }}
                >
                  <Trash2 size={14} />
                </button>
              </div>
              {/* 변경 이력 패널 — 행 바로 아래 확장. 펼칠 때마다 refetch(다른 세션 변경 반영). */}
              {isAuditOpen && (
                <div style={{ margin: '0 4px', padding: '6px 10px', borderRadius: '8px', background: 'var(--bg-panel)', border: '1px solid var(--border)' }}>
                  {auditView.status === 'loading' && (
                    <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>이력을 불러오는 중...</div>
                  )}
                  {auditView.status === 'error' && (
                    <div style={{ fontSize: '12px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                      이력을 불러오지 못했습니다.
                      <button type="button" onClick={() => loadAuditLog(plan.id)} style={quickReplyButtonStyle}>
                        <RefreshCw size={12} />
                        다시 시도
                      </button>
                    </div>
                  )}
                  {auditView.status === 'ready' && auditView.events.length === 0 && (
                    <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                      기록이 없습니다 (서버 재시작 시 이력도 초기화됩니다)
                    </div>
                  )}
                  {auditView.status === 'ready' && auditView.events.length > 0 && (
                    <>
                      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: '5px', maxHeight: '160px', overflowY: 'auto' }}>
                        {auditView.events.map((event) => (
                          <li key={event.id} style={{ fontSize: '12px', display: 'flex', alignItems: 'baseline', gap: '6px' }}>
                            <span style={{ fontWeight: 600, flexShrink: 0 }}>
                              {AUDIT_EVENT_LABELS[event.type] || event.type}
                            </span>
                            {event.detail && (
                              <span style={{ color: 'var(--text-muted)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                {event.detail}
                              </span>
                            )}
                            <span
                              style={{
                                marginLeft: 'auto',
                                flexShrink: 0,
                                fontSize: '11px',
                                padding: '1px 6px',
                                borderRadius: '999px',
                                border: `1px solid ${auditSessionBadge(event.sessionId) === '이 브라우저' ? 'var(--primary)' : 'var(--border)'}`,
                                color: auditSessionBadge(event.sessionId) === '이 브라우저' ? 'var(--primary)' : 'var(--text-muted)'
                              }}
                            >
                              {auditSessionBadge(event.sessionId)}
                            </span>
                            <span style={{ flexShrink: 0, fontSize: '11px', color: 'var(--text-muted)' }}>
                              {formatEventTime(event.createdAt)}
                            </span>
                          </li>
                        ))}
                      </ul>
                      <div style={{ fontSize: '11px', color: 'var(--text-muted)', textAlign: 'center', marginTop: '6px' }}>
                        모든 방문자가 함께 보는 데모 이력입니다 · 최근 1000건만 보관됩니다
                      </div>
                    </>
                  )}
                </div>
              )}
              </React.Fragment>
            );
          })}
          <button type="button" onClick={handleResetPlan} disabled={isTyping} style={quickReplyButtonStyle}>
            <Plus size={12} />
            새 계획 만들기
          </button>
          <div style={{ fontSize: '11px', color: 'var(--text-muted)', textAlign: 'center' }}>
            모든 방문자가 함께 보는 데모 보관함입니다 · 서버 재시작 시 초기화됩니다
          </div>
        </div>
      )}
    </div>
  );

  // === 오른쪽: 체크리스트 패널 ===
  const checklistPanel = (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%', background: 'var(--bg-panel)' }}>
      <div style={{
        padding: '10px 16px',
        borderBottom: '1px solid var(--border)',
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between'
      }}>
        <span style={{ fontWeight: 600, fontSize: '14px', display: 'flex', alignItems: 'center', gap: '6px' }}>
          생성된 체크리스트
          {isLocked && (
            <span style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '3px',
              fontSize: '11px',
              fontWeight: 600,
              color: 'var(--primary)',
              border: '1px solid var(--primary)',
              borderRadius: '999px',
              padding: '1px 8px'
            }}>
              <Lock size={10} />
              고정됨
            </span>
          )}
        </span>
        {draftChecklist && (
          <div style={{ display: 'flex', gap: '6px' }}>
            <button
              type="button"
              onClick={handleCopyPlan}
              title="계획을 텍스트로 복사"
              style={{
                ...exportButtonStyle,
                ...(copyFeedback === 'error' ? { color: 'var(--warning)', borderColor: 'var(--warning)' } : {})
              }}
            >
              {copyFeedback === 'ok' && <Check size={13} />}
              {copyFeedback !== 'ok' && <Copy size={13} />}
              {copyFeedback === 'ok' ? '복사됨' : copyFeedback === 'error' ? '복사 실패' : '복사'}
            </button>
            <button
              type="button"
              onClick={handleDownloadPlan}
              title="계획을 .txt 파일로 다운로드"
              style={exportButtonStyle}
            >
              <Download size={13} />
              다운로드
            </button>
          </div>
        )}
      </div>

      {planListBar}

      <div style={{ flex: 1, overflowY: 'auto', padding: '16px', minHeight: 0 }}>
        {!draftChecklist ? (
          <div style={{ color: 'var(--text-muted)', fontSize: '14px', textAlign: 'center', marginTop: '40px', lineHeight: 1.6 }}>
            왼쪽 대화에서 목표 · 기간 · 하루 투자 시간 · 현재 수준을<br />
            입력하면 이곳에 계획표가 생성됩니다.
          </div>
        ) : (
          <div className="animate-fade-in" style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            {/* 요약 헤더 */}
            <div style={{ borderBottom: '1px solid var(--border)', paddingBottom: '12px' }}>
              <h2 style={{ fontSize: '18px', fontWeight: 700, marginBottom: '6px' }}>{draftChecklist.goalName}</h2>
              <div style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '8px' }}>
                기간 {draftChecklist.duration}일 · 하루 {draftChecklist.dailyHours}시간 · {draftChecklist.currentLevel}
              </div>
              {totalCount > 0 && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <div style={{ flex: 1, height: '6px', borderRadius: '3px', background: 'var(--border)', overflow: 'hidden' }}>
                    <div
                      style={{
                        height: '100%',
                        borderRadius: '3px',
                        background: 'var(--primary)',
                        width: `${Math.round((completedCount / totalCount) * 100)}%`,
                        transition: 'width 0.3s ease'
                      }}
                    />
                  </div>
                  <span style={{ fontSize: '12px', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                    {completedCount}/{totalCount} 완료
                  </span>
                </div>
              )}
            </div>

            {/* 일차별 미션 (tasks가 비정상이어도 화면이 죽지 않게 방어) — Day마다 살짝 늦게 나타나 순차 생성 느낌을 준다 */}
            {Object.entries(draftChecklist.tasks || {}).map(([date, taskList], idx) => (
              <div
                key={date}
                className="animate-fade-in"
                style={{
                  background: 'var(--bg-card)',
                  border: '1px solid var(--border)',
                  borderRadius: '8px',
                  padding: '12px 14px',
                  animationDelay: `${Math.min(idx, 8) * 70}ms`,
                  animationFillMode: 'backwards'
                }}
              >
                <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--primary)', marginBottom: '8px' }}>
                  Day {idx + 1} · {date}
                </div>
                <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '6px', margin: 0, padding: 0 }}>
                  {(Array.isArray(taskList) ? taskList : []).map((task) => (
                    // 실제 <input type="checkbox">를 <label>로 감싼다 — 이렇게 하면 텍스트
                    // 클릭도 토글되고, Tab 포커스 + Space 토글이 네이티브로 동작하며(별도
                    // onKeyDown/role 불필요), 스크린리더도 체크박스로 인식한다.
                    <li key={task.id} style={{ fontSize: '14px' }}>
                      <label
                        style={{
                          display: 'flex',
                          gap: '8px',
                          alignItems: 'flex-start',
                          cursor: 'pointer',
                          userSelect: 'none'
                        }}
                      >
                        <input
                          type="checkbox"
                          checked={!!task.completed}
                          onChange={() => toggleTask(date, task.id)}
                          style={{
                            marginTop: '2px',
                            flexShrink: 0,
                            width: '15px',
                            height: '15px',
                            accentColor: 'var(--primary)',
                            cursor: 'pointer'
                          }}
                        />
                        <span
                          style={{
                            textDecoration: task.completed ? 'line-through' : 'none',
                            color: task.completed ? 'var(--text-muted)' : 'var(--text-main)'
                          }}
                        >
                          {task.content}
                        </span>
                      </label>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );

  // 가로 3칸 — 왼쪽=대화, 가운데=오늘 할 일, 오른쪽=체크리스트. 가운데 칸은 목록 성격이라
  // 살짝 좁게 잡는다. 모바일 폭에서는 위아래 스택으로 전환하고, 오늘 칸은 화면을 다
  // 차지하지 않게 높이를 제한한다(내부 스크롤).
  return (
    <div className="split-layout">
      <div className="split-pane split-pane--chat">{chatPanel}</div>
      <div className="split-pane split-pane--today">{todayPanel}</div>
      <div className="split-pane split-pane--checklist">{checklistPanel}</div>

      <style>{`
        .split-layout {
          flex: 1;
          min-height: 0;
          display: grid;
          grid-template-columns: 1.1fr 0.8fr 1.1fr;
        }
        .split-pane {
          min-height: 0;
          overflow: hidden;
        }
        .split-pane--chat,
        .split-pane--today {
          border-right: 1px solid var(--border);
        }
        @media (max-width: 760px) {
          .split-layout {
            grid-template-columns: 1fr;
            grid-template-rows: 1fr auto 1fr;
          }
          .split-pane--chat,
          .split-pane--today {
            border-right: none;
            border-bottom: 1px solid var(--border);
          }
          .split-pane--today {
            max-height: 40vh;
          }
        }
      `}</style>
    </div>
  );
}
