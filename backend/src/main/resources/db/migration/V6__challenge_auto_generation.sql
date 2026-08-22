-- 챌린지 자동 생성 — 비슷한 조건(기간 버킷 + 목적 카테고리)의 계획이 모이면 서버가 챌린지를 연다
-- (v0.23.0). 사용자 개설은 사라졌으므로 challenges.owner에는 자동 생성분에 한해 'system'이 들어간다
-- (기존 사용자 개설 행은 그대로 남는다 — condition_key가 NULL인 행이 그것이다).

ALTER TABLE challenges ADD COLUMN condition_key TEXT;

-- [같은 조건의 모집 중 챌린지는 하나뿐이다 — 판정은 애플리케이션이 아니라 이 인덱스가 한다]
-- "이미 있나?"를 자바에서 확인한 뒤 INSERT하면 확인과 삽입 사이가 열려, 동시에 고정한 두 계획이
-- 같은 조건의 챌린지를 둘 만든다(정원 판정에서 피한 것과 똑같은 TOCTOU, docs/CONCURRENCY.md).
-- WHERE participant_count < capacity가 붙은 부분 인덱스인 이유: 정원이 찬 챌린지는 인덱스에서
-- 빠지므로, 모집이 끝난 조건에 대해서는 다음 챌린지가 다시 열릴 수 있다.
CREATE UNIQUE INDEX uq_challenges_open_condition
    ON challenges (condition_key) WHERE participant_count < capacity;

-- 조건별 씨앗 — 그 조건의 계획을 고정한 소유자 집합이다. 카운트의 단위가 계획이 아니라 소유자인
-- 이유는 한 사람이 비슷한 계획을 세 개 고정했다고 해서 함께 달릴 사람이 셋이 되지는 않기 때문이다.
-- 복합 PK가 그 중복 제거를 DB에서 단독으로 한다(애플리케이션의 사전 조회 없음).
CREATE TABLE challenge_seeds (
    condition_key TEXT NOT NULL,
    owner         TEXT NOT NULL,
    seeded_at     TEXT NOT NULL,
    PRIMARY KEY (condition_key, owner)
);

ALTER TABLE challenge_seeds ENABLE ROW LEVEL SECURITY;
