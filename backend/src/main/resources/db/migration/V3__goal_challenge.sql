-- Goal Challenge — 정원이 한정된 목표 챌린지와 포인트 지갑 (v0.21.0).
-- 이 기능의 존재 이유는 "동시 요청에서 정원을 어떻게 지키는가"이므로, 스키마도 그 결정에 맞춰 읽어야
-- 한다. 자세한 근거는 docs/CONCURRENCY.md.
-- 기존 관례 유지: 시각은 프론트/서버가 만든 ISO 문자열을 그대로 왕복시키는 TEXT, 소유자는
-- 게스트 ID(X-Guest-Id) TEXT, 모든 테이블 RLS 활성화(Supabase PostgREST 노출 차단).

CREATE TABLE challenges (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner             TEXT    NOT NULL,  -- 개설자 게스트 ID
    title             TEXT    NOT NULL,
    duration_days     INTEGER NOT NULL,
    capacity          INTEGER NOT NULL,  -- 정원 — 이 값이 경쟁의 대상이다
    entry_fee         INTEGER NOT NULL,  -- 참가비(포인트)
    participant_count INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT    NOT NULL
);

-- 목록: ORDER BY created_at DESC, id DESC (최근 개설이 앞)
CREATE INDEX idx_challenges_created ON challenges (created_at DESC, id DESC);

-- [의도적으로 CHECK (participant_count <= capacity)를 걸지 않았다]
-- 제약을 걸면 "검사-후-쓰기(naive)" 구현이 정원을 넘길 때 오버부킹 대신 제약 위반 예외로 끝나,
-- ChallengeJoinConcurrencyIT의 naive 테스트가 증명하려는 것(틀린 구현은 실제로 정원을 넘는다)이
-- 사라진다. 이 저장소에서 정원 불변식을 지키는 것은 JdbcChallengeRepository의 조건부 UPDATE
-- (WHERE participant_count < capacity) 하나다. 운영에서 방어를 한 겹 더 원하면 그때 추가한다.

-- 중복 참가 방지는 애플리케이션 코드가 아니라 복합 PK가 한다 — 동시 요청에서도 DB가 단독 판정한다.
CREATE TABLE challenge_participants (
    challenge_id BIGINT NOT NULL REFERENCES challenges (id) ON DELETE CASCADE,
    owner        TEXT   NOT NULL,
    joined_at    TEXT   NOT NULL,
    PRIMARY KEY (challenge_id, owner)
);

-- 포인트 지갑 — 게스트당 1행, 최초 참가 시 INITIAL_BALANCE로 지연 생성된다.
-- CHECK (balance >= 0)은 최후 안전망일 뿐, 실제 방어는 차감 UPDATE의 WHERE balance >= :fee다.
CREATE TABLE point_wallets (
    owner   TEXT    PRIMARY KEY,
    balance INTEGER NOT NULL CHECK (balance >= 0)
);

ALTER TABLE challenges             ENABLE ROW LEVEL SECURITY;
ALTER TABLE challenge_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE point_wallets          ENABLE ROW LEVEL SECURITY;
