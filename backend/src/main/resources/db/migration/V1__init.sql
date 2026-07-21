-- v0.12.0 초기 스키마 — 인메모리 레코드(Plan·Reflection·AuditEvent)와 1:1 대응.
-- 날짜·시각 필드는 프론트가 만든 ISO 문자열을 그대로 왕복시키기 위해 TEXT로 보관한다
-- (해석·검증 소유권은 서버 코드에 있고, DB는 저장만 담당 — 기존 레코드 관례 유지).
-- saved_at만 epoch ms BIGINT — 목록 정렬의 단일 기준.

CREATE TABLE plans (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner         TEXT    NOT NULL,  -- 게스트 ID(X-Guest-Id). 로그인 도입 시 memberId로 re-key 예정
    goal_name     TEXT    NOT NULL,
    duration      INTEGER,
    daily_hours   INTEGER,
    current_level TEXT,
    tasks         JSONB,             -- {날짜: [{id, content, completed}]} 프론트 스키마 원본 그대로
    status        TEXT    NOT NULL,  -- DRAFT | CONFIRMED
    confirmed_at  TEXT,
    start_date    TEXT,
    end_date      TEXT,
    created_at    TEXT,
    saved_at      BIGINT  NOT NULL
);

-- findAllByOwner: WHERE owner = ? ORDER BY saved_at DESC, id DESC (countByOwner도 owner 프리픽스 사용)
CREATE INDEX idx_plans_owner_saved ON plans (owner, saved_at DESC, id DESC);

-- (plan_id, date)당 1건 업서트 — 복합 PK가 인메모리의 "planId:date" 키와 대응.
-- FK ON DELETE CASCADE는 서비스의 deleteAllByPlanId 캐스케이드와 겹치는 이중 안전망(고아 방지).
CREATE TABLE reflections (
    plan_id         BIGINT  NOT NULL REFERENCES plans (id) ON DELETE CASCADE,
    date            TEXT    NOT NULL,  -- YYYY-MM-DD (서버가 정규화해 저장)
    completed_count INTEGER NOT NULL,
    total_count     INTEGER NOT NULL,
    difficulty      TEXT    NOT NULL,  -- EASY | NORMAL | HARD
    reason          TEXT    NOT NULL,  -- AS_PLANNED | NOT_ENOUGH_TIME | ...
    created_at      TEXT    NOT NULL,  -- 최초 저장 시각 — 재저장(업서트)해도 보존
    updated_at      TEXT    NOT NULL,
    PRIMARY KEY (plan_id, date)
);

-- 변경 이력 — 의도적으로 plans FK 없음: "계획이 언제 삭제됐는가"에 답해야 하므로
-- 감사 이력은 계획 삭제를 살아남는다. 인메모리의 MAX_EVENTS 링버퍼 상한도 두지 않는다
-- (누적이 목적, 보존 정리는 추후 배치 도입 시 — v0.12.0 범위 제외).
CREATE TABLE audit_events (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id    BIGINT NOT NULL,
    owner_id   TEXT   NOT NULL,
    type       TEXT   NOT NULL,  -- AuditEventType.name()
    detail     TEXT,
    session_id TEXT,
    created_at TEXT   NOT NULL
);

-- findAllByPlanIdAndOwner: WHERE plan_id = ? AND owner_id = ? ORDER BY id DESC
CREATE INDEX idx_audit_plan_owner ON audit_events (plan_id, owner_id, id DESC);

-- Supabase 노출 차단 — public 스키마 테이블은 PostgREST 자동 API(anon 키)로 노출될 수 있다.
-- 정책 없이 RLS만 켜면 익명 API 접근은 전면 차단되고, 백엔드는 특권 역할(postgres)로 직접
-- 접속해 RLS 영향을 받지 않는다. 실제 행 단위 정책은 로그인 마일스톤에서 추가할 자리.
ALTER TABLE plans        ENABLE ROW LEVEL SECURITY;
ALTER TABLE reflections  ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
