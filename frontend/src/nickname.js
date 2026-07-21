// 닉네임 — 화면에 표시되는 이름(라벨)일 뿐이다. 데이터 소유·격리의 기준은 guest_id.js이며,
// 닉네임은 서버로 전송되지 않는다. 따라서 다른 브라우저에서 같은 닉네임을 써도 데이터는 공유되지
// 않고(각자 별도의 게스트 보관함), 닉네임을 바꿔도 데이터 스코프는 그대로다.
// localStorage에 보관해 새로고침 후에도 표시 이름을 유지한다.

const NICKNAME_KEY = 'delaynomore:nickname';

// localStorage가 막힌 환경(프라이빗 모드 등)은 모듈 메모리에만 보관 — 탭 수명 동안만 유지된다
// (session_id.js의 try/catch 관례와 동일: 보존이 약해질 뿐 앱이 죽지 않게).
let inMemoryNickname = null;

// 닉네임(표시 이름) 규칙 — 트림 후 한글·영문·숫자 2~20자. 서버로 전송되지 않으므로 순수 UI
// 검증이다(데이터 소유 키인 게스트 ID의 서버 검증은 OwnerGuestId.java와 guest_id.js에 있다).
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
