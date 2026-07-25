-- 계획 상태 수명주기 확장 (DRAFT → CONFIRMED → COMPLETED, DRAFT|CONFIRMED → CANCELLED).
-- 규칙 소유권은 PlanStatus enum(전이표) — 이 CHECK 제약은 애플리케이션 버그로 알 수 없는
-- 상태 문자열이 저장되는 것을 막는 최후 안전망일 뿐이다. 기존 행은 DRAFT|CONFIRMED뿐이라
-- 제약 추가는 안전하다.
ALTER TABLE plans ADD COLUMN completed_at TEXT; -- 완료 시각(ISO 문자열) — confirmed_at과 동형, 미완료면 NULL
ALTER TABLE plans ADD CONSTRAINT chk_plans_status
    CHECK (status IN ('DRAFT', 'CONFIRMED', 'COMPLETED', 'CANCELLED'));
