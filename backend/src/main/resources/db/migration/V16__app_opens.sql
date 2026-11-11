-- 지표 계측 기반 (v0.37.0) — 캐릭터 독 성공 판정(docs/MEETING_character_dock.md §6)의 기준선을
-- 서버 데이터로 뽑기 위한 최소 저장소. 분석 SDK·접근 로그 없이 두 가지만 기록한다:
--   (1) 앱 오픈: ChatCoach 마운트마다 1회 POST /app-opens — visit_id(페이지 로드마다 새 UUID)가 PK라
--       StrictMode 이중 마운트·재시도는 ON CONFLICT DO NOTHING으로 멱등.
--   (2) 변경 이력(audit_events)에 같은 visit_id를 함께 남겨 "그 방문에서 첫 완료 체크에 도달했는가"
--       (지표 A)를 조인으로 계산한다. 기존 행은 NULL — 기준선은 배포 이후부터 쌓인다.
-- 읽기 행동 전반을 기록하는 것이 아니다(AuditEvent의 "조회는 기록하지 않는다" 원칙 유지) —
-- 오픈 1건만 별도 테이블에 둔다. 집계 SQL은 docs/OPERATIONS.md.

CREATE TABLE app_opens (
    visit_id   TEXT PRIMARY KEY,
    owner_id   TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_app_opens_owner_created ON app_opens (owner_id, created_at);
ALTER TABLE app_opens ENABLE ROW LEVEL SECURITY;

ALTER TABLE audit_events ADD COLUMN visit_id TEXT;
