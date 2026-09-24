-- 목표 예치 (v0.32.0) — 1인 에스크로. 고정(CONFIRMED)한 계획에 자기 포인트를 걸고, 계획이
-- 종결되는 순간 **달성률만큼 돌려받고 나머지는 소멸**한다. 챌린지(v0.21.0~)가 여럿이 걸고 나누는
-- 판이라면 이쪽은 혼자 자기 자신과 하는 약속이다.
--
-- [계획당 한 건] plan_id가 PK다 — "한 계획에 예치는 최대 하나"의 판정 주체는 애플리케이션
-- 검사가 아니라 이 제약이다(중복 참가를 복합 PK가 막는 challenge_participants와 같은 관례).
--
-- [정산은 최대 한 번] 판정은 조건부 UPDATE 하나다:
--     UPDATE plan_deposits SET settled_at = :now, refunded = :amount
--      WHERE plan_id = :id AND settled_at IS NULL
-- 1행을 받은 호출만 환급·소각을 기표한다. 정원(v0.21.0)·자동 개설(v0.23.0)·챌린지 정산
-- (v0.25.0)·일일 발송(v0.26.0)·원장 기표(v0.31.0)에 이은 여섯 번째 판본이다.
--
-- [계획 삭제] FK는 CASCADE지만 서비스가 **삭제 전에 정산**한다 — 예치 행만 지우면 예치금이
-- escrow:plan:<id> 계정에 영영 갇힌다. CASCADE는 그 뒤를 받는 안전망일 뿐이다.
--
-- 기존 관례 유지: 시각은 ISO 문자열 TEXT, 소유자는 TEXT, RLS 활성화.

CREATE TABLE plan_deposits (
    plan_id    BIGINT  PRIMARY KEY REFERENCES plans (id) ON DELETE CASCADE,
    owner      TEXT    NOT NULL,
    amount     INTEGER NOT NULL CHECK (amount > 0),  -- 건 금액(차감은 이미 끝난 상태로 기록된다)
    created_at TEXT    NOT NULL,
    settled_at TEXT,                                 -- NULL = 미정산
    refunded   INTEGER                               -- 정산 시 환급액(0 = 전액 소멸)
);

-- 내 예치 목록 조회용(소유자 스코프).
CREATE INDEX idx_plan_deposits_owner ON plan_deposits (owner);

ALTER TABLE plan_deposits ENABLE ROW LEVEL SECURITY;
