-- 포인트 원장 (v0.31.0). 지금까지 포인트는 point_wallets.balance 한 칸으로만 움직였다 —
-- 잔액이 틀어져도 어디서 틀어졌는지 알 수 없고, 사용자는 "왜 줄었는지"를 볼 수 없었다.
-- 이 테이블이 그 공백을 메운다: **원장이 진실이고 잔액은 파생값(캐시)**이다.
--
-- [복식부기] 한 번의 이동은 항상 두 줄로 기록된다 — 나가는 계정(음수)과 들어오는 계정(양수).
-- 두 줄은 같은 tx_key를 공유하고 합이 0이다. 따라서 전체 SUM(amount) = 0이 언제나 참이고,
-- 이 한 줄짜리 불변식이 "포인트가 어디선가 생기거나 사라지지 않았다"를 증명한다.
--
-- [계정 체계] 소유자 계정(게스트 ID·사용자 UUID)과 시스템 계정이 같은 컬럼에 산다:
--   system:issuance        발행 계정 — 신규 지급의 출처. 잔액이 음수로 커지고, 그 절댓값이 총 발행량이다.
--   system:opening         이 마이그레이션의 기초잔액 상대 계정(아래 백필).
--   escrow:challenge:<id>  챌린지별 예치 계정 — 참가비가 여기 잠기고 정산이 여기서 빠져나간다.
--     챌린지마다 계정을 나눈 이유: 정산이 끝난 뒤 이 계정에 남는 잔액이 곧 "정수 나눗셈으로
--     소멸한 나머지"라, 미정산 예치금과 소멸분이 한 계정에 섞이지 않는다(조인 없이 설명된다).
--
-- [멱등] UNIQUE (tx_key, account)가 같은 논리 거래의 이중 기표를 DB에서 막는다. tx_key는
-- 도메인에서 파생한다(signup:<owner> · join:<cid>:<owner> · payout:<cid>:<owner>) — 재시도가
-- 같은 키를 만들어내므로 애플리케이션이 "이미 기록했나"를 묻지 않아도 된다.
-- 정원·정산·자동 개설에 이은 "판정을 DB에" 관례의 다섯 번째 판본이다(docs/CONCURRENCY.md 10절).
--
-- 기존 관례 유지: 시각은 ISO 문자열 TEXT, 계정은 소유자 키와 같은 TEXT, RLS 활성화.

CREATE TABLE point_ledger (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tx_key     TEXT    NOT NULL,  -- 한 거래의 두 줄이 공유하는 키(= 멱등 키)
    account    TEXT    NOT NULL,  -- 소유자 키 또는 시스템/에스크로 계정
    amount     INTEGER NOT NULL,  -- 부호 있는 금액. 한 거래 두 줄의 합은 0
    kind       TEXT    NOT NULL,  -- PointTxKind.name()
    ref_type   TEXT,              -- 참조 대상 종류(예: challenge)
    ref_id     TEXT,              -- 참조 대상 id
    created_at TEXT    NOT NULL
);

-- 이중 기표 차단 — 판정 주체는 애플리케이션 검사가 아니라 이 인덱스다.
CREATE UNIQUE INDEX uq_point_ledger_tx_account ON point_ledger (tx_key, account);
-- 거래 내역 조회: 계정별 최신순.
CREATE INDEX idx_point_ledger_account ON point_ledger (account, id DESC);

ALTER TABLE point_ledger ENABLE ROW LEVEL SECURITY;

-- [기초잔액 백필] V12 이전의 이동은 기록이 남아 있지 않아 소급 복원할 수 없다. 현재 잔액을
-- 기초잔액 한 줄로 기표해 "원장 합계 = 잔액" 불변식을 이 시점부터 성립시킨다 — 없는 과거를
-- 지어내지 않고, 원장이 언제부터 진실인지를 데이터로 남기는 쪽을 택했다.
INSERT INTO point_ledger (tx_key, account, amount, kind, created_at)
SELECT 'opening:' || owner, owner, balance, 'OPENING_BALANCE',
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
  FROM point_wallets WHERE balance <> 0;

INSERT INTO point_ledger (tx_key, account, amount, kind, created_at)
SELECT 'opening:' || owner, 'system:opening', -balance, 'OPENING_BALANCE',
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
  FROM point_wallets WHERE balance <> 0;
