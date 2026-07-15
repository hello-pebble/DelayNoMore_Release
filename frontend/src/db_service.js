// 백엔드 REST 클라이언트 (최소 구성)
// 이 데모는 "대화 → 투두리스트 생성" 흐름만 다루므로 AI 프록시 두 엔드포인트만 노출한다.
// 인증·목표 저장·팀·알림 등은 제거되었다.

const API_BASE = '/api';

// AI 계획 초안 생성 요청 — 백엔드(/api/ai/draft)가 OpenRouter 호출을 대행한다(키는 서버에만 보관).
export const postAiDraft = async (payload) => {
  const response = await fetch(`${API_BASE}/ai/draft`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!response.ok) {
    const message =
      (data && typeof data === 'object' && (data.message || data.error)) ||
      `HTTP ${response.status}`;
    throw new Error(message);
  }

  return data;
};

// 초안 생성 이후의 자유 대화 — 백엔드(/api/ai/chat)가 의도 판단(수정/질문/불명확)까지 LLM에 위임한다.
// 응답: { reply, planUpdated, tasks? }
export const postAiChat = async (payload) => {
  const response = await fetch(`${API_BASE}/ai/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!response.ok) {
    const message =
      (data && typeof data === 'object' && (data.message || data.error)) ||
      `HTTP ${response.status}`;
    throw new Error(message);
  }

  return data;
};

// 자유 대화 스트리밍(SSE) — /api/ai/chat/stream 이 흘려보내는 이벤트를 파싱해 onEvent로 넘긴다.
// EventSource는 POST를 못 하므로 fetch + ReadableStream 리더로 직접 SSE를 파싱한다.
// 이벤트(각각 "data: <JSON>\n\n"): {type:'token',t} / {type:'plan',patch} / {type:'done'} / {type:'error',m}
export const streamAiChat = async (payload, onEvent) => {
  const response = await fetch(`${API_BASE}/ai/chat/stream`, {
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

// AI 연결 상태 LED용 헬스체크 — 실패해도 예외를 던지지 않고 상태 객체를 반환한다.
export const getAiHealth = async () => {
  try {
    const response = await fetch(`${API_BASE}/ai/health`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    });
    if (!response.ok) {
      return { success: false, reason: `인증 오류 (${response.status})` };
    }
    return await response.json();
  } catch {
    return { success: false, reason: '네트워크 연결 오류' };
  }
};
