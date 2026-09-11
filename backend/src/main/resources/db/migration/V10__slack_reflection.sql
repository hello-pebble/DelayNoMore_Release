-- 슬랙 문답 회고 상태 기계(v0.28.0). 활동 종료 시각에 계획별 세션 행이 만들어지고,
-- 사용자의 답장이 상태를 전이시킨다: PENDING(대기) → AWAITING_DIFFICULTY(난이도 질문 중)
-- → AWAITING_REASON(이유 질문 중) → DONE(저장 완료) | EXPIRED(자정 경과·중단).
-- 한 번에 한 계획만 AWAITING_* 상태가 되도록 애플리케이션이 순차 활성화한다.
-- 회고 저장 자체는 기존 reflections 테이블·ReflectionService가 소유한다 — 이 테이블은
-- "슬랙 대화가 어디까지 왔는지"만 기억하는 진행 상태다.

CREATE TABLE slack_reflection_sessions (
    owner        TEXT   NOT NULL,
    session_date DATE   NOT NULL,               -- KST 날짜(회고는 당일만 저장 가능 — 서버 가드)
    plan_id      BIGINT NOT NULL,
    state        TEXT   NOT NULL,               -- PENDING | AWAITING_DIFFICULTY | AWAITING_REASON | DONE | EXPIRED
    difficulty   TEXT,                          -- 1차 답변 임시 보관(EASY/NORMAL/HARD)
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner, session_date, plan_id)
);

ALTER TABLE slack_reflection_sessions ENABLE ROW LEVEL SECURITY;
