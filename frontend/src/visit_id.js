// 방문 식별자 — 페이지 로드마다 새로 만들어지는 ID(v0.37.0). 지표 A("앱 오픈 → 그 방문에서 첫 완료
// 체크 도달률")의 "그 방문" 경계다. session_id.js의 X-Session-Id는 localStorage에 영구 보관되는
// 브라우저 표식이라 방문을 구분하지 못하므로 별도로 둔다. 모든 요청에 X-Visit-Id로 실리고,
// 서버는 앱 오픈 기록(app_opens)과 변경 이력(audit_events.visit_id)에만 저장한다. 인증이 아니다.

function generateVisitId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `v-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

const VISIT_ID = generateVisitId();

export function getVisitId() {
  return VISIT_ID;
}
