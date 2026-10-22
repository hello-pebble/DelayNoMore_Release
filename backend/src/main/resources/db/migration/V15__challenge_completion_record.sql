-- 챌린지 "기간 내 완주" 기록 (v0.34.0). 지금까지 완주 판정은 정산 순간의 완료율이었다 — 마감 후 몇
-- 분 사이의 체크(또는 서버 중단·재기동 지연)가 완주로 인정될 수 있었다. 이제 마감 전까지의 상태를
-- 서버가 참가 레코드에 기록하고, 정산은 그 기록만 본다.
--
-- [completed_at] "마감 전까지 이 시각부터 100% 완료 상태". NULL = 미완주. 기록 규칙(저장소가 소유):
-- 미정산이고 (미시작이거나 마감 전)인 참가만 갱신하므로 마감·정산 이후의 변경은 무시된다.
-- 완주 = 할 일 1개 이상 + 전부 완료(Plan.allTasksDone) — 아래 백필의 집계도 같은 기준이다.
--
-- [settlement_hold] 이미 마감이 지났는데 정산되지 않은 챌린지는 "마감 시점의 상태"를 현재 데이터만으로
-- 증명할 수 없다(지금 100%여도 마감 후에 채웠을 수 있다). 현재 완료율로 자동 백필하면 기간 내 완주 규칙을
-- 보장할 수 없으므로 백필하지 않고, 자동 정산(스케줄러·lazy)을 막은 채 운영자가 결과를 정한다.
-- 해제 절차는 docs/OPERATIONS.md — completed_at 없이 hold만 풀면 다음 정산이 풀 전액을 소멸시킨다.

ALTER TABLE challenge_participants ADD COLUMN completed_at TEXT;
ALTER TABLE challenges ADD COLUMN settlement_hold BOOLEAN NOT NULL DEFAULT FALSE;

-- 1) 마감이 지난 미정산 챌린지는 보류한다. 종료 시각 = started_at + duration_days × 24시간
--    (Challenge.endsAt()의 Duration.ofDays와 같은 계산 — 세션 시간대의 DST와 무관하게).
UPDATE challenges
   SET settlement_hold = TRUE
 WHERE settled_at IS NULL
   AND started_at IS NOT NULL
   AND (started_at::timestamptz + duration_days * interval '24 hours') <= now();

-- 2) 보류되지 않은 미정산 챌린지(모집 중·마감 전)의 참가자는 현재 100% 상태를 기준으로 초기화한다.
--    tasks = {날짜: [{id, content, completed}]} — Plan.countAllTasks와 같은 방어: 값이 배열이 아닌
--    날짜는 0개, 원소는 객체이면서 completed가 JSON true인 것만 완료로 센다.
UPDATE challenge_participants p
   SET completed_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
  FROM challenges c, plans pl
 WHERE p.challenge_id = c.id
   AND pl.id = p.plan_id
   AND c.settled_at IS NULL
   AND c.settlement_hold = FALSE
   AND (SELECT COALESCE(SUM(jsonb_array_length(d.value)), 0)
          FROM jsonb_each(CASE WHEN jsonb_typeof(pl.tasks) = 'object' THEN pl.tasks ELSE '{}'::jsonb END) d
         WHERE jsonb_typeof(d.value) = 'array') > 0
   AND (SELECT COALESCE(SUM(jsonb_array_length(d.value)), 0)
          FROM jsonb_each(CASE WHEN jsonb_typeof(pl.tasks) = 'object' THEN pl.tasks ELSE '{}'::jsonb END) d
         WHERE jsonb_typeof(d.value) = 'array')
       = (SELECT count(*)
            FROM jsonb_each(CASE WHEN jsonb_typeof(pl.tasks) = 'object' THEN pl.tasks ELSE '{}'::jsonb END) d,
                 jsonb_array_elements(CASE WHEN jsonb_typeof(d.value) = 'array' THEN d.value ELSE '[]'::jsonb END) e
           WHERE jsonb_typeof(e) = 'object' AND e -> 'completed' = 'true'::jsonb);
