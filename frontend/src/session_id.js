// 브라우저 세션 식별자 — 변경 이력의 "이 브라우저에서 한 변경인가, 다른 세션인가" 구분용.
// 최초 1회 생성해 localStorage에 보관한다(계정이 아니라 브라우저 단위 표식일 뿐, 인증이 아니다).
// 변이 요청(POST/PUT/DELETE)에 X-Session-Id 헤더로 실려 서버 변경 이력에 기록된다.

const SESSION_ID_KEY = 'delaynomore:sessionId';

// localStorage가 막힌 환경(프라이빗 모드 등)은 모듈 메모리에만 보관 — 탭 수명 동안만 유지된다
// (lastViewedPlanId의 try/catch 관례와 동일: 식별이 약해질 뿐 앱이 죽지 않게).
let inMemoryId = null;

// 평문 HTTP 배포에선 crypto.randomUUID(보안 컨텍스트 전용)가 없을 수 있어 폴백을 둔다
// (클립보드 폴백과 같은 사정). 식별용 난수라 암호학적 강도는 필요 없다.
function generateSessionId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function getSessionId() {
  if (inMemoryId) return inMemoryId;
  try {
    let id = localStorage.getItem(SESSION_ID_KEY);
    if (!id) {
      id = generateSessionId();
      localStorage.setItem(SESSION_ID_KEY, id);
    }
    inMemoryId = id;
  } catch {
    inMemoryId = generateSessionId();
  }
  return inMemoryId;
}
