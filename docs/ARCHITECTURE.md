# 구조

## 화면 구성

화면은 **가로 3칸**입니다 — **왼쪽=AI 코치와의 대화**, **가운데="오늘 할 일"**(보관된 계획들의 오늘 항목 모음), **오른쪽=생성된 체크리스트**. 모바일 폭에서는 같은 순서로 위아래 스택됩니다.

## 디렉토리 구조

```
DelayNoMore_Release/
├── Dockerfile  # 단일 배포: 프론트 빌드 → 백엔드 static 포함 → 하나의 jar/컨테이너
├── frontend/   # React 19 + Vite (순수 JS/JSX) — 심플 디자인(시스템 폰트, 무배경)
│   └── src/
│       ├── App.jsx                    # 헤더 + AI 상태 표시 + 코치 화면 마운트
│       ├── ai_engine.js               # 슬롯필링 로직 + 계획 생성 + mock 폴백
│       ├── db_service.js              # 백엔드 호출(단일 REST 클라이언트) — AI 프록시 + 계획 보관함 + 변경 이력
│       ├── session_id.js              # 브라우저 단위 익명 세션 ID(localStorage) — 변경 이력 귀속용 X-Session-Id
│       ├── date_utils.js              # 로컬 기준 'YYYY-MM-DD' 포맷/파싱/오늘 날짜 유틸
│       └── components/chat_coach.jsx  # 가로 3칸: 대화 패널 · 오늘 할 일(+미완료 이월) · 체크리스트/보관함(+변경 이력) 패널
└── backend/    # Spring Boot 4.1 / Java 21 (AI 프록시 + 계획 보관함 + 정적 화면 서빙)
    └── src/main/java/.../
        ├── domain/ai/   # controller·service·client·dto — /api/v1/ai/{health,drafts,chats}(+/stream)
        ├── domain/plan/ # 계획 보관함+일일 회고+변경 이력(인메모리·휘발성) — /api/v1/plans CRUD
        │                #   + /plans/{id}/reflections + /plans/{id}/audit-events, 추후 DB로 교체 예정
        └── global/      # 공통: response(ApiResponse) · error(ErrorCode, GlobalExceptionHandler) · config
```

## API 개요

- **AI 프록시** — `/api/v1/ai/{health, drafts, chats}` (+ `/stream` SSE 변형). OpenRouter 키는 서버에만 보관.
- **계획 보관함** — `/api/v1/plans` CRUD + `POST /api/v1/plans/{id}/carry-over`(미완료 이월 — 오늘(KST) 미완료를 내일로, 고정 계획은 409 `PLAN_LOCKED`) + `/api/v1/plans/{id}/reflections`(일일 회고). 응답(`PlanResponse`)은 완료율을 서버가 계산한 `progress {done, total}` 필드로 내려준다. 현재 인메모리(휘발성), 추후 DB로 교체 예정.
- **변경 이력(Audit)** — `GET /api/v1/plans/{id}/audit-events`(최신순, 읽기 전용 — 이벤트는 서버가 변경 서비스 안에서 직접 발행). 변이 요청(POST/PUT/DELETE)의 선택 헤더 `X-Session-Id`(브라우저 단위 익명 ID)로 "어느 세션의 변경인지"를 기록한다. 계획을 삭제해도 이력은 남는다(전역 1,000건 링버퍼 상한).
- **메타(선택지·라벨)** — `GET /api/v1/meta/{reflection-options, audit-event-types}`(읽기 전용). 회고 선택지·이력 라벨의 소스오브트루스인 서버 enum을 코드+한글 라벨로 내려준다(프론트는 마운트 시 수신, 미가용 시 폴백 사본).
- 응답은 `{ success, data, error }`(ApiResponse)로 래핑되고, 검증 실패는 `error.fieldErrors`, 오류 분기는 `error.code`(ErrorCode)로 판별합니다. Swagger UI: `/swagger-ui.html`.

관련 문서: [기능 상세](FEATURES.md) · [실행·배포](DEPLOY.md)
