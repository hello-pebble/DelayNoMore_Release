// 게스트 ID — 이 브라우저의 데이터 소유자 키. 최초 1회 생성해 localStorage에 보관하고,
// 모든 계획 API 요청에 X-Guest-Id 헤더로 실린다(db_service.js). 닉네임과 달리 서버 격리의
// 유일한 기준이며(닉네임은 화면 표시용 라벨), 로그인 도입 시 이 키의 데이터가 memberId로 이전된다.
//
// 보안 성격: guestId는 인증 수단이 아니지만 현재 구조에서는 데이터를 여는 bearer 토큰과 같다.
// "로그인 전 브라우저 단위 임시 개인 보관함"이다. v0.12.0부터 서버 데이터는 PostgreSQL에 영속되어
// 서버 재시작으로는 사라지지 않지만, 이 값 자체(브라우저 localStorage)를 잃으면 — 데이터 삭제,
// 다른 브라우저/기기, 프라이빗 모드 종료 — 닉네임을 기억해도 자동으로 재연결할 방법이 없다(닉네임은
// 서버로 전송되지 않는 표시용 라벨이라 소유자 조회 키가 될 수 없음). HTTP 배포에서는 네트워크상
// guestId 보호도 보장되지 않으므로 민감한 정보를 담으면 안 된다.

const GUEST_ID_KEY = 'delaynomore:guestId';

let inMemoryId = null;
// localStorage에 실제로 저장됐는지 — false면 이 탭을 벗어나거나 새로고침 시 보관함을 잃는다.
// UI가 이 값을 읽어 경고를 띄운다(isGuestIdPersisted).
let persisted = true;

// 128비트 난수 UUID를 만든다. crypto.randomUUID(보안 컨텍스트 전용)가 없더라도
// crypto.getRandomValues로 충분한 엔트로피를 확보하고, 그것마저 없을 때만 최후의 폴백을 쓴다
// (평문 HTTP·구형 환경 대비). guestId는 데이터를 여는 키라 폴백도 추측 어려운 난수여야 한다.
function generateGuestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    // RFC 4122 version/variant 비트 세팅 후 8-4-4-4-12 하이픈 포맷(서버 패턴 [A-Za-z0-9-]{8,64} 충족)
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  // 최후의 폴백 — crypto가 아예 없는 환경. 두 개의 난수 조각을 이어 붙여 길이를 확보한다.
  const rand = () => Math.random().toString(36).slice(2, 12);
  return `g-${Date.now().toString(36)}-${rand()}-${rand()}`;
}

export function getGuestId() {
  if (inMemoryId) return inMemoryId;
  try {
    let id = localStorage.getItem(GUEST_ID_KEY);
    if (!id) {
      id = generateGuestId();
      localStorage.setItem(GUEST_ID_KEY, id);
    }
    inMemoryId = id;
    persisted = true;
  } catch {
    // 프라이빗 모드 등 localStorage 차단 — 탭 수명 동안만 유지되는 메모리 사본으로 계속 동작한다.
    inMemoryId = generateGuestId();
    persisted = false;
  }
  return inMemoryId;
}

// localStorage에 guestId가 영속됐는지. false면 새로고침·탭 종료 시 보관함 접근을 잃으므로
// UI가 사용자에게 안내한다. getGuestId를 최소 1회 부른 뒤 유효한 값이다.
export function isGuestIdPersisted() {
  return persisted;
}
