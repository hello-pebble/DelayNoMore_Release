// 백엔드 REST 클라이언트 (최소 구성)
// 이 데모는 "대화 → 투두리스트 생성" 흐름만 다루므로 AI 프록시 엔드포인트만 노출한다.
// 인증·목표 저장·팀·알림 등은 제거되었다.

const API_BASE = '/api/v1';

// 공통 JSON 요청 — 백엔드의 ApiResponse({ success, data, error })를 언래핑해 data만 돌려준다.
// 실패 시(HTTP 오류 또는 success=false) error.message로 예외를 던진다(필드 오류가 있으면 첫 사유 우선).
const requestJson = async (path, payload) => {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
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
    throw new Error(message);
  }

  return body.data;
};

// AI 계획 초안 생성 요청 — 백엔드(/api/v1/ai/drafts)가 OpenRouter 호출을 대행한다(키는 서버에만 보관).
// 응답 data: { 날짜: [할 일 문자열] }
export const postAiDraft = (payload) => requestJson('/ai/drafts', payload);

// 초안 생성 이후의 자유 대화 — 백엔드(/api/v1/ai/chats)가 의도 판단(수정/질문/불명확)까지 LLM에 위임한다.
// 응답 data: { reply, planUpdated, patch? }
export const postAiChat = (payload) => requestJson('/ai/chats', payload);

// SSE(fetch + ReadableStream) 공통 파서 — EventSource는 POST를 못 하므로 직접 파싱한다.
// 각 이벤트는 "data: <JSON>\n\n" 형태. path의 엔드포인트가 흘려보내는 JSON을 onEvent로 넘긴다.
const consumeSse = async (path, payload, onEvent) => {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
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
// 이벤트: {type:'token',t} / {type:'plan',patch} / {type:'done'} / {type:'error',m}
export const streamAiChat = (payload, onEvent) => consumeSse('/ai/chats/stream', payload, onEvent);

// 초안 생성 스트리밍(SSE) — /api/v1/ai/drafts/stream. 하루(=한 줄)가 완성될 때마다 day 이벤트가 온다.
// 이벤트: {type:'day',date,tasks:[...]} / {type:'done'} / {type:'error',m}
export const streamAiDraft = (payload, onEvent) => consumeSse('/ai/drafts/stream', payload, onEvent);

// AI 연결 상태 LED용 헬스체크 — 실패해도 예외를 던지지 않고 상태 객체를 반환한다.
// 백엔드 data({connected, reason})를 기존 화면 계약({success, reason})으로 되돌려 준다.
export const getAiHealth = async () => {
  try {
    const response = await fetch(`${API_BASE}/ai/health`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    });
    if (!response.ok) {
      return { success: false, reason: `인증 오류 (${response.status})` };
    }
    const body = await response.json();
    const data = body?.data;
    return { success: data?.connected === true, reason: data?.reason };
  } catch {
    return { success: false, reason: '네트워크 연결 오류' };
  }
};
