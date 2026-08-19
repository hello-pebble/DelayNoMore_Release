-- 로그인 세션 — 서버가 발급한 불투명 토큰의 저장소. 토큰 원문은 클라이언트(localStorage)만
-- 갖고, 서버는 SHA-256 해시만 저장한다(DB가 유출돼도 세션을 탈취할 수 없다 — 비용은
-- MessageDigest 한 줄). 만료 행 청소는 로그인 시 DELETE 한 문장으로 한다(별도 스케줄러 없음).
CREATE TABLE auth_sessions (
    token_hash TEXT        PRIMARY KEY,          -- SHA-256(token) hex 64자
    user_id    UUID        NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL              -- 발급 시 now() + 30일. sliding 갱신 없음(만료 시 재로그인)
);

-- 로그아웃·청소가 사용자 단위로 지울 때를 위한 보조 인덱스.
CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);

-- 기존 관례 유지: 정책 없는 RLS = PostgREST 익명 노출 전면 차단 (V1 주석 참조)
ALTER TABLE auth_sessions ENABLE ROW LEVEL SECURITY;
