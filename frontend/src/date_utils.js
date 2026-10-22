// 로컬 시간대 기준 날짜 유틸(단일 구현). 여러 컴포넌트/모듈에 흩어져 있던
// 'YYYY-MM-DD' 포맷·파싱 로직을 여기로 모은다.
//
// 주의: new Date('YYYY-MM-DD')는 문자열을 UTC 자정으로 해석하므로, 음수 UTC 지역에서
// getMonth()/getDate()가 하루 밀린다. 계획 날짜는 로컬 기준이어야 하므로 parseLocalDate로 파싱한다.

// Date 객체를 로컬 기준 'YYYY-MM-DD' 문자열로 포맷한다.
export function formatLocalDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

// 오늘(+offsetDays)을 서비스 기준 시간대(Asia/Seoul)의 'YYYY-MM-DD'로 반환한다. 서버의 "오늘"
// (KstDates.today — 지난 날짜 잠금·이월·오늘 대시보드)과 같은 기준이어야 브라우저 시간대가
// 달라도 화면의 오늘과 서버의 오늘이 어긋나지 않는다. en-CA 로캘이 YYYY-MM-DD로 포맷한다.
export function todayStr(offsetDays = 0) {
  const kst = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date());
  if (!offsetDays) return kst;
  const d = parseLocalDate(kst);
  d.setDate(d.getDate() + offsetDays);
  return formatLocalDate(d);
}

// 'YYYY-MM-DD'를 로컬 자정 Date로 파싱한다(UTC 밀림 방지). 형식이 아니면 null.
export function parseLocalDate(str) {
  if (typeof str !== 'string') return null;
  const [year, month, day] = str.split('-').map(Number);
  if (!year || !month || !day) return null;
  return new Date(year, month - 1, day);
}
