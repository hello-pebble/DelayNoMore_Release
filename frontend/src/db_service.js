// 백엔드 REST 클라이언트 (최소 구성)
// 이 데모는 "대화 → 투두리스트 생성" 흐름만 다루므로 AI 프록시 엔드포인트만 노출한다.
// 인증·목표 저장·팀·알림 등은 제거되었다.

import { getSessionId } from './session_id';
import { getGuestId } from './guest_id';

const API_BASE = '/api/v1';

// 공통 JSON 요청 — 백엔드의 ApiResponse({ success, data, error })를 언래핑해 data만 돌려준다.
// 실패 시(HTTP 오류 또는 success=false) error.message로 예외를 던진다(필드 오류가 있으면 첫 사유 우선).
// 던지는 Error에 error.code(예: PLAN_NOT_FOUND)를 붙여 호출부가 오류 종류를 분기할 수 있게 한다.
const requestJson = async (path, payload, method = 'POST') => {
  const headers = { 'Content-Type': 'application/json' };
  if (method !== 'GET') {
    // 변경 이력의 세션 귀속용 — 서버는 변이 요청에서만 이 헤더를 읽고, 읽기는 기록하지 않는다.
    headers['X-Session-Id'] = getSessionId();
  }
  // 소유자 스코프 — 계획·회고·이력 API는 브라우저 게스트 ID로 데이터가 격리되므로 읽기에도
  // 필요하다. 요청 진입 시점에 한 번 고정해 붙인다(호출 도중 소유자 전환이 생겨도 이 요청은 시작
  // 시점의 소유자로만 나간다 — 로그인 도입 후 guestId→memberId 전환 대비). guestId는 ASCII라
  // 인코딩이 필요 없다. AI·메타 엔드포인트는 이 헤더를 무시한다(경로 분기 불필요).
  headers['X-Guest-Id'] = getGuestId();
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: payload == null ? undefined : JSON.stringify(payload),
  });

  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }

  if (!response.ok || !body || body.success !== true) {
    const fieldErrors = body?.error?.fieldErrors;
    const firstFieldError = fieldErrors && Object.values(fieldErrors)[0];
    const message = firstFieldError || body?.error?.message || `HTTP ${response.status}`;
    const err = new Error(message);
    err.code = body?.error?.code;
    err.status = response.status;
    throw err;
  }

  return body.data;
};

// AI 계획 초안 생성 요청 — 백엔드(/api/v1/ai/drafts)가 OpenRouter 호출을 대행한다(키는 서버에만 보관).
// 응답 data: { 날짜: [할 일 문자열] }
export const postAiDraft = (payload) => requestJson('/ai/drafts', payload);

// 계획 작성의 질문 순서와 입력 해석은 서버 세션이 소유한다.
export const createPlanDraftSession = () => requestJson('/ai/plan-draft-sessions', null);
export const postPlanDraftSessionMessage = (sessionId, message) =>
  requestJson(`/ai/plan-draft-sessions/${sessionId}/messages`, { message });

// 초안 생성 이후의 자유 대화 — 백엔드(/api/v1/ai/chats)가 의도 판단(수정/질문/불명확)까지 LLM에 위임한다.
// tasks는 서버가 LLM patch를 현재 계획에 병합한 정규화된 전체 계획({id,content,completed} 객체).
// 응답 data: { reply, planUpdated, tasks? }
export const postAiChat = (payload) => requestJson('/ai/chats', payload);

// SSE(fetch + ReadableStream) 공통 파서 — EventSource는 POST를 못 하므로 직접 파싱한다.
// 각 이벤트는 "data: <JSON>\n\n" 형태. path의 엔드포인트가 흘려보내는 JSON을 onEvent로 넘긴다.
const consumeSse = async (path, payload, onEvent) => {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    // 에이전트 경로는 도구로 소유 데이터(계획·회고·이월)를 다루므로 소유자 스코프가 필수다.
    // 기존 AI 엔드포인트는 이 헤더를 무시하므로 경로 분기 없이 항상 붙인다(requestJson과 같은 관례).
    headers: {
      'Content-Type': 'application/json',
      'X-Guest-Id': getGuestId(),
      'X-Session-Id': getSessionId(),
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok || !response.body) {
    throw new Error(`HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // SSE 이벤트는 빈 줄(\n\n)로 구분된다. 완성된 이벤트만 떼어 처리하고 꼬리는 버퍼에 남긴다.
    let sep;
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const rawEvent = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      const dataLine = rawEvent.split('\n').find((l) => l.startsWith('data:'));
      if (!dataLine) continue;
      const jsonStr = dataLine.slice(5).trim();
      if (!jsonStr) continue;
      let evt;
      try {
        evt = JSON.parse(jsonStr);
      } catch {
        continue;
      }
      onEvent(evt);
    }
  }
};

// 자유 대화 스트리밍(SSE) — /api/v1/ai/chats/stream.
// 이벤트: {type:'token',t} / {type:'plan',tasks} / {type:'done'} / {type:'error',m}
export const streamAiChat = (payload, onEvent) => consumeSse('/ai/chats/stream', payload, onEvent);

// 초안 생성 스트리밍(SSE) — /api/v1/ai/drafts/stream. 하루(=한 줄)가 완성될 때마다 day 이벤트가 온다.
// 이벤트: {type:'day',date,tasks:[...]} / {type:'done'} / {type:'error',m}
export const streamAiDraft = (payload, onEvent) => consumeSse('/ai/drafts/stream', payload, onEvent);

// 에이전트 대화 스트리밍(SSE) — /api/v1/ai/agent/chats/stream. 위 자유 대화와 달리 계획 변경이
// 산문 속 ===PLAN=== 구분자가 아니라 서버가 실행하는 도구 호출로 일어나고, 그 과정이 이벤트로 흐른다.
// 이벤트: {type:'profile',name,label}(v0.17.0, 최초 1회 — 이번 실행의 페르소나) / {type:'step',n}
//        / {type:'tool_call',id,name,args} / {type:'tool_result',id,ok,summary}
//        / {type:'token',t} / {type:'plan',tasks} / {type:'plan_refresh',planId} / {type:'done'} / {type:'error',m}
// plan(미저장 변경 → 초안으로 채택)과 plan_refresh(서버가 이미 저장 → 재조회)의 구분에 주의.
export const streamAiAgentChat = (payload, onEvent) => consumeSse('/ai/agent/chats/stream', payload, onEvent);

// 에이전트 카탈로그 — 해당 계획 상태의 프로필과, 모델에게 실제로 노출되는 도구만 내려온다.
// planId를 생략하면 보관 전 초안(DRAFT) 기준.
// 응답 data: {profile:{name,label}, tools:[{name, description, mutating, parameters}]} (v0.17.0에서 래핑)
export const fetchAgentTools = (planId) =>
  requestJson(`/ai/agent/tools${planId == null ? '' : `?planId=${planId}`}`, null, 'GET');

// 계획 보관함 CRUD — 서버 인메모리 저장소(휘발성: 재시작 시 초기화, 게스트 ID별 격리).
// 로그인/DB 도입 전의 원격 데모용이며, 응답/요청 형태는 PlanController(/api/v1/plans) 계약을 따른다.
export const createPlan = (payload) => requestJson('/plans', payload);
export const updatePlan = (id, payload) => requestJson(`/plans/${id}`, payload, 'PUT');
export const updateTaskCompletion = (planId, taskId, completed) =>
  requestJson(`/plans/${planId}/tasks/${encodeURIComponent(taskId)}/completion`, { completed }, 'PUT');
// 미완료 이월 도메인 액션 — 본문 없는 POST. 이월 규칙(오늘(KST) 미완료 → 내일, 필요 시 기간
// 하루 연장)은 서버 소유라 클라이언트는 날짜를 지정하지 않는다.
// 응답: { movedCount, targetDate, plan } — movedCount 0은 "옮길 게 없음"의 정상 no-op.
export const carryOverPlan = (id) => requestJson(`/plans/${id}/carry-over`, null);
// 상태 전이 도메인 액션 — 본문 없는 POST. 전이 규칙(전이표)·시각 발급(confirmedAt/completedAt)·
// 이력 발행(PLAN_CONFIRMED 등)은 전부 서버 소유이고, 응답은 전이 후의 PlanResponse다.
// 전이표에 없는 전이는 409 INVALID_STATUS_TRANSITION(err.code로 분기).
export const confirmPlan = (id) => requestJson(`/plans/${id}/confirm`, null);
export const completePlan = (id) => requestJson(`/plans/${id}/complete`, null);
export const cancelPlan = (id) => requestJson(`/plans/${id}/cancel`, null);
export const fetchPlans = () => requestJson('/plans', null, 'GET');
export const fetchPlan = (id) => requestJson(`/plans/${id}`, null, 'GET');
export const deletePlan = (id) => requestJson(`/plans/${id}`, null, 'DELETE');
export const fetchTodayDashboard = () => requestJson('/dashboard/today', null, 'GET');

// 주간 완료율 요약 — 계획을 startDate 기준 7일 버킷("N주차")으로 묶은 주별 완료율. 완료 개수 계산은
// 서버 소유(plan.tasks 기준)라 프론트는 표시만 한다. 응답 data: { planId, startDate, endDate,
// totalDone, totalTotal, weeks: [{ index, startDate, endDate, done, total, rate }] }
export const fetchWeeklySummary = (planId) => requestJson(`/plans/${planId}/summary/weekly`, null, 'GET');

// 다음 계획 분량 추천(로드맵 4·5번) — 서버가 수행 기록을 계산하고 규칙으로 하루 분량을 정한다.
// 분량 숫자는 서버 소유이고 AI는 이유 설명·내용 생성만 한다. 세 단계 모두 서버가 오케스트레이션한다.
//  1) recommend: 수행 기록 + 규칙 분량 + 이유. 응답 data: { sourcePlanId, currentTasksPerDay,
//     recommendedTasksPerDay, observedDays, completedCount, totalCount, completionRate, hardCount,
//     topReason:{code,label}|null, insufficientHistory, reason, aiReasonUsed }
//  2) draft: 선택 분량으로 초안 생성(미저장). 응답 data: { ...원본 메타, tasks, tasksPerDay, aiUsed }
//  3) confirm: 승인된 초안을 새 계획으로 저장. 응답 data: PlanResponse
export const postRecommendation = (planId) => requestJson(`/plans/${planId}/recommendation`, null, 'POST');
export const postRecommendationDraft = (planId, selectedTasksPerDay) =>
  requestJson(`/plans/${planId}/recommendation/draft`, { selectedTasksPerDay }, 'POST');
export const confirmRecommendation = (planId, payload) =>
  requestJson(`/plans/${planId}/recommendation/confirm`, payload, 'POST');

// 하루 마무리 회고 — 계획별·날짜별 1건(PUT=업서트). 완료 개수는 보내지 않는다(서버가
// plan.tasks에서 재계산). 저장은 서버 기준 오늘(Asia/Seoul) 날짜만 허용된다.
// 호출부는 err.code(REFLECTION_NOT_FOUND / PLAN_NOT_FOUND 등)로 분기한다.
export const putReflection = (planId, date, payload) => requestJson(`/plans/${planId}/reflections/${date}`, payload, 'PUT');

// 메타(선택지·라벨) — 회고 선택지와 이력 이벤트 라벨의 소스오브트루스는 서버 enum이다.
// 프론트는 마운트 시 한 번 받아 쓰고, 실패하면 하드코딩 폴백(DEFAULT_*)으로 화면을 지킨다.
export const fetchReflectionOptions = () => requestJson('/meta/reflection-options', null, 'GET');
export const fetchAuditEventTypes = () => requestJson('/meta/audit-event-types', null, 'GET');
export const fetchPlanStatuses = () => requestJson('/meta/plan-statuses', null, 'GET');

// 계획 변경 이력(최신순) — 이벤트는 서버가 변경 서비스 안에서 직접 발행하므로 읽기만 있다.
// 삭제된 계획 id도 404가 아니라 과거 이력(또는 빈 목록)으로 응답한다.
export const fetchAuditEvents = (planId) => requestJson(`/plans/${planId}/audit-events`, null, 'GET');
export const fetchReflection = (planId, date) => requestJson(`/plans/${planId}/reflections/${date}`, null, 'GET');
export const fetchReflections = (planId) => requestJson(`/plans/${planId}/reflections`, null, 'GET');

// AI 연결 상태 LED용 헬스체크 — 실패해도 예외를 던지지 않고 상태 객체를 반환한다.
// 백엔드 data({connected, reason, toolCalling})를 기존 화면 계약({success, reason})에 맞춰 돌려주고,
// toolCalling(에이전트 경로 가용 여부)을 additive로 덧붙인다. 서버가 이 필드를 안 내려주는
// 구버전이면 false로 떨어져 기존 자유 대화 경로만 쓴다.
export const getAiHealth = async () => {
  try {
    const response = await fetch(`${API_BASE}/ai/health`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    });
    if (!response.ok) {
      return { success: false, reason: `인증 오류 (${response.status})`, toolCalling: false };
    }
    const body = await response.json();
    const data = body?.data;
    return {
      success: data?.connected === true,
      reason: data?.reason,
      toolCalling: data?.toolCalling === true,
    };
  } catch {
    return { success: false, reason: '네트워크 연결 오류', toolCalling: false };
  }
};
