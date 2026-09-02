-- 챌린지 정산 루프(v0.25.0). 상태 enum 없이 두 시각 컬럼의 null 여부로 3상태를 파생한다:
--   모집중 = started_at IS NULL / 진행중 = started_at 있음 · settled_at 없음 / 종료 = settled_at 있음
-- "챌린지는 최대 한 번 정산된다"의 판정 주체는 애플리케이션 검사가 아니라
-- UPDATE ... WHERE settled_at IS NULL 이다(정원 판정·자동 개설과 같은 원칙, docs/CONCURRENCY.md 8절).

ALTER TABLE challenges ADD COLUMN started_at TEXT;  -- 정원이 찬 순간(마지막 참가의 원자 구간에서 기록)
ALTER TABLE challenges ADD COLUMN settled_at TEXT;  -- 정산 완료 순간(NULL = 미정산)

-- 참가자 ↔ 완주 판정 근거 계획의 링크. 계획을 삭제하면 완주 증명이 사라지므로 SET NULL → 정산 시
-- 패배로 수렴한다(삭제 차단·경고 없음 — 참가비는 이미 낸 상태라 삭제할 유인이 없다).
-- V8 이전의 기존 참가 행은 plan_id NULL로 남는다(백필 없음 — 데모 데이터라 패배 수렴을 수용).
ALTER TABLE challenge_participants ADD COLUMN plan_id BIGINT REFERENCES plans (id) ON DELETE SET NULL;
-- 정산 결과: NULL = 미정산, 0 = 미완주(패배), 양수 = 배당 또는 환불액.
ALTER TABLE challenge_participants ADD COLUMN payout INTEGER;

-- 부분 UNIQUE 인덱스 재정의. 기존 WHERE participant_count < capacity만으로는 "모집 미달인 채
-- 환불 마감된" 챌린지가 여전히 미달이라 인덱스에 남아, 같은 조건의 다음 챌린지 개설을 영구히
-- 막는다. settled_at 조건을 더해 마감된 챌린지를 인덱스에서 빼면 다음 챌린지가 다시 열린다.
DROP INDEX uq_challenges_open_condition;
CREATE UNIQUE INDEX uq_challenges_open_condition
    ON challenges (condition_key)
    WHERE participant_count < capacity AND settled_at IS NULL;
