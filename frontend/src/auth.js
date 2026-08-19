// 로그인 상태 — 서버가 발급한 세션 토큰과 표시용 프로필을 localStorage에 보관한다.
// 토큰은 모든 API 요청에 Authorization: Bearer로 실린다(db_service.js). 서버는 401로만
// 만료를 알리고, 그때 clearAuth로 지우면 다음 요청부터 기존 게스트(X-Guest-Id) 흐름으로
// 자연 복귀한다. guestId는 로그인 후에도 계속 전송된다 — 로그인 시 게스트 보관함을 계정으로
// 흡수하는 재료이기 때문(서버 AuthService.absorbGuest).

const AUTH_KEY = 'delaynomore:auth';

// { token, nickname, email } 또는 null. 파싱 실패·차단 환경은 비로그인으로 취급한다.
export function getAuth() {
  try {
    const raw = localStorage.getItem(AUTH_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function setAuth(auth) {
  try {
    localStorage.setItem(AUTH_KEY, JSON.stringify(auth));
  } catch {
    // localStorage 차단(프라이빗 모드 등) — 로그인이 이 탭에서만 유지되는 것을 감수한다.
  }
}

export function clearAuth() {
  try {
    localStorage.removeItem(AUTH_KEY);
  } catch {
    // 차단 환경이면 지울 것도 없다.
  }
}
