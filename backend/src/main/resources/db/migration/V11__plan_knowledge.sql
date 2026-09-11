-- 도메인 지식 자료(v0.29.0) — 계획별 참고 자료(사용자 붙여넣기 텍스트)와 검색용 청크.
-- 소유는 plan 경유(reflections 선례) — owner 컬럼 없음: 서비스가 planService.getPlan(planId, owner)
-- 로 판정하고 불일치는 404. 청크는 업로드 시점에 서버가 분할해 저장한다(검색 경로는 읽기만).
-- 개수·길이 상한은 스키마 CHECK가 아니라 서버 코드가 소유한다(규칙 소유권 관례).

CREATE TABLE plan_knowledge_docs (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id    BIGINT NOT NULL REFERENCES plans (id) ON DELETE CASCADE,
    title      TEXT   NOT NULL,
    content    TEXT   NOT NULL,   -- 원문 보존(재청크·내보내기 대비)
    created_at TEXT   NOT NULL    -- ISO 문자열 TEXT — V1부터의 시각 필드 관례
);
CREATE INDEX idx_knowledge_docs_plan ON plan_knowledge_docs (plan_id, id DESC);

CREATE TABLE plan_knowledge_chunks (
    doc_id  BIGINT  NOT NULL REFERENCES plan_knowledge_docs (id) ON DELETE CASCADE,
    seq     INTEGER NOT NULL,
    content TEXT    NOT NULL,
    PRIMARY KEY (doc_id, seq)
);

ALTER TABLE plan_knowledge_docs   ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_knowledge_chunks ENABLE ROW LEVEL SECURITY;
