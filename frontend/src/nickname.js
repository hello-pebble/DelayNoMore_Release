// 닉네임 — 로그인 도입 전의 간이 계정 키. 계획·회고·이력이 닉네임별로 격리되며,
// 같은 닉네임을 입력하면 어느 브라우저에서든 같은 보관함을 본다(비밀번호 없음 — 인증이 아니다).
// X-Session-Id(브라우저 표식)와 별개: 세션은 이력의 "이 브라우저/다른 세션" 구분용으로 남는다.
// 모든 계획 API 요청에 X-Nickname 헤더로 실린다(db_service.js).

const NICKNAME_KEY = 'delaynomore:nickname';

// localStorage가 막힌 환경(프라이빗 모드 등)은 모듈 메모리에만 보관 — 탭 수명 동안만 유지된다
// (session_id.js의 try/catch 관례와 동일: 보존이 약해질 뿐 앱이 죽지 않게).
let inMemoryNickname = null;

// 닉네임 규칙 — 백엔드 OwnerNickname.java와 거울처럼 유지(트림 후 한글·영문·숫자 2~20자).
// 공백·기호 불허: HTTP 헤더 인코딩 경계 사례(+, %, 공백)를 규칙 차원에서 차단한다.
const NICKNAME_PATTERN = /^[0-9A-Za-z가-힣]{2,20}$/;

// ai_engine.js parseUserMessage와 같은 { isValid, value, error } 계약.
export function validateNickname(raw) {
  const trimmed = (raw || '').trim();
  if (!NICKNAME_PATTERN.test(trimmed)) {
    return { isValid: false, error: '닉네임은 한글·영문·숫자 2~20자로 입력해 주세요.' };
  }
  return { isValid: true, value: trimmed };
}

export function getNickname() {
  if (inMemoryNickname) return inMemoryNickname;
  try {
    inMemoryNickname = localStorage.getItem(NICKNAME_KEY);
  } catch {
    // 읽기 불가 — 미설정으로 취급(설정 게이트가 다시 뜬다)
  }
  return inMemoryNickname;
}

// 검증된 값만 저장한다는 전제(호출부가 validateNickname을 먼저 통과시킨다).
export function setNickname(nickname) {
  inMemoryNickname = nickname;
  try {
    localStorage.setItem(NICKNAME_KEY, nickname);
  } catch {
    // 저장 불가 — 메모리 사본으로 이번 탭에서는 계속 동작한다
  }
}
