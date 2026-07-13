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
