-- 소셜 로그인 계정 구분용 users (스키마 선행 준비 — OAuth 플로우는 다음 마일스톤).
-- PK가 UUID인 이유: 로그인 도입 시 기존 owner TEXT 컬럼들(plans.owner 등)을
-- guestId → id::text 로 re-key 할 계획(docs/BACKEND_MIGRATION.md #2)이라,
-- 게스트 UUID 문자열과 같은 모양으로 한 컬럼에 공존해야 한다. owner 컬럼들은
-- 전환기 동안 두 종류 id가 섞이므로 FK를 걸지 않는다(걸 수도 없다).
CREATE TABLE users (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    provider         TEXT        NOT NULL,  -- 'google' (1계정 1프로바이더)
    provider_subject TEXT        NOT NULL,  -- 제공자가 발급한 sub 클레임
    email            TEXT,                  -- 제공자가 안 줄 수 있어 nullable
    nickname         TEXT,                  -- 가입 시 localStorage 닉네임 이관 자리
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject)     -- 로그인 조회 키
);

-- 기존 관례 유지: 정책 없는 RLS = PostgREST 익명 노출 전면 차단 (V1 주석 참조)
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
