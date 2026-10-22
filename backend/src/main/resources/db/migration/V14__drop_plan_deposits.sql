-- 개인 목표 예치 제거 (v0.33.0). 일반 목표는 할 일·완료율만 다루고, 포인트는 챌린지 참가비
-- 예치·정산에서만 움직인다. V13의 plan_deposits를 없애되, **아직 정산되지 않은 예치는 먼저
-- 전액 환급**한다 — 테이블만 지우면 그 포인트가 escrow:plan:<id> 계정에 영영 갇힌다.
--
-- [환급은 원장에 남긴다] 복식 두 줄: 예치 계정(-amount) / 소유자(+amount). 예치 때의 기표
-- (예치 계정 +amount / 소유자 -amount)와 정확히 반대이므로 환급 뒤 모든 escrow:plan:* 잔액은 0,
-- 전체 합계는 0, 소유자 원장 합계 = 잔액이 그대로 성립한다. 과거 원장 행(예치·환급·소각)은
-- 이력이라 지우지 않는다. 한 사용자의 예치가 여러 건이어도 잔액은 합계로 한 번에 올린다
-- (UPDATE ... FROM은 같은 행에 여러 건이 걸려도 한 번만 적용하므로 집계 서브쿼리를 쓴다).

UPDATE point_wallets w
   SET balance = w.balance + s.total
  FROM (SELECT owner, SUM(amount) AS total
          FROM plan_deposits WHERE settled_at IS NULL GROUP BY owner) s
 WHERE w.owner = s.owner;

INSERT INTO point_ledger (tx_key, account, amount, kind, ref_type, ref_id, created_at)
SELECT 'deposit-refund:' || plan_id, 'escrow:plan:' || plan_id, -amount, 'PLAN_DEPOSIT_REFUND',
       'plan', plan_id::text,
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
  FROM plan_deposits WHERE settled_at IS NULL;

INSERT INTO point_ledger (tx_key, account, amount, kind, ref_type, ref_id, created_at)
SELECT 'deposit-refund:' || plan_id, owner, amount, 'PLAN_DEPOSIT_REFUND',
       'plan', plan_id::text,
       to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
  FROM plan_deposits WHERE settled_at IS NULL;

DROP TABLE plan_deposits;
