-- 슬랙 연동(v0.26.0) — 연결·연결 코드·발송 멱등 클레임·이벤트 중복 제거.
-- owner는 항상 users.id다: 연결이 로그인 필수라 게스트 UUID가 이 테이블에 올 수 없고,
-- 따라서 absorbGuest(re-key) 대상이 아니다. FK 없는 TEXT는 plans.owner와 같은 관례(V4 주석).

CREATE TABLE slack_links (
    owner            TEXT PRIMARY KEY,
    slack_team_id    TEXT NOT NULL,
    slack_user_id    TEXT NOT NULL,
    slack_channel_id TEXT,                        -- 봇과의 DM 채널(연결 시 코드가 온 채널을 캐시)
    -- 활동시간(KST 분 단위). v0.26.0은 start(체크리스트 전송 시각)만 쓰고, end는 회고 유도
    -- 릴리스(v0.28.0 예정)를 위해 스키마만 먼저 둔다. start < end는 애플리케이션이 검증한다.
    active_start_min INT  NOT NULL DEFAULT 540,   -- 09:00
    active_end_min   INT  NOT NULL DEFAULT 1260,  -- 21:00
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 슬랙 계정 하나는 서비스 계정 하나에만 연결된다(재연결은 기존 행 교체).
    CONSTRAINT uq_slack_links_slack_user UNIQUE (slack_team_id, slack_user_id)
);

-- 웹에서 발급해 슬랙 DM으로 입력하는 연결 코드. 만료 판정·청소는 DB 시계 기준이고
-- 별도 스케줄러 없이 발급 시 lazy DELETE 한 문장으로 치운다(V5 auth_sessions 관례).
CREATE TABLE slack_link_codes (
    code       TEXT PRIMARY KEY,                  -- SecureRandom 8자(대문자+숫자)
    owner      TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL               -- 발급 +10분
);

-- 일일 발송 멱등 클레임. "오늘 이 owner에게 이미 보냈는가"의 판정 주체는 애플리케이션 검사가
-- 아니라 이 PK다 — INSERT ON CONFLICT DO NOTHING이 0행이면 이미 다른 실행이 클레임한 것
-- (정산 claimSettlement과 같은 원칙, docs/CONCURRENCY.md 8절). 전송 실패 재시도권도
-- UPDATE ... WHERE sent_at IS NULL AND claimed_at < now() - interval AND attempts < 상한
-- 조건부 UPDATE 하나가 정확히 한 실행에게만 준다.
CREATE TABLE slack_daily_sends (
    owner      TEXT NOT NULL,
    send_date  DATE NOT NULL,                     -- KST 날짜
    kind       TEXT NOT NULL,                     -- 'CHECKLIST' (v0.28에서 'REFLECTION_PROMPT' 추가 예정)
    attempts   INT  NOT NULL DEFAULT 1,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ,                       -- NULL = 전송 미확인(재시도 대상)
    PRIMARY KEY (owner, send_date, kind)
);

-- Slack Events 재전송(X-Slack-Retry) 중복 제거. event_id INSERT가 0행이면 이미 처리한 이벤트.
-- 하루 지난 행은 디스패치 루프에서 lazy DELETE.
CREATE TABLE slack_event_dedup (
    event_id    TEXT PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE slack_links       ENABLE ROW LEVEL SECURITY;
ALTER TABLE slack_link_codes  ENABLE ROW LEVEL SECURITY;
ALTER TABLE slack_daily_sends ENABLE ROW LEVEL SECURITY;
ALTER TABLE slack_event_dedup ENABLE ROW LEVEL SECURITY;
