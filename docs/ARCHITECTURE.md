# 구조

## 화면 구성

화면은 **가로 3칸**입니다 — **왼쪽=AI 코치와의 대화**, **가운데="오늘 할 일"**(보관된 계획들의 오늘 항목 모음), **오른쪽=생성된 체크리스트**. 모바일 폭에서는 같은 순서로 위아래 스택됩니다.

## 디렉토리 구조

```
DelayNoMore_Release/
├── Dockerfile  # 단일 배포: 프론트 빌드 → 백엔드 static 포함 → 하나의 jar/컨테이너
├── frontend/   # React 19 + Vite (순수 JS/JSX) — 심플 디자인(시스템 폰트, 무배경)
│   └── src/
│       ├── App.jsx                    # 닉네임 게이트 + 헤더(표시 이름·변경) + 저장소 경고 + 코치 화면 마운트
│       ├── ai_engine.js               # 슬롯필링 로직 + 계획 생성 + 에이전트 대화 + 4단 폴백
│       ├── db_service.js              # 백엔드 호출(단일 REST 클라이언트) — AI 프록시 + 계획 보관함 + 변경 이력
│       ├── session_id.js              # 브라우저 단위 익명 세션 ID(localStorage) — 변경 이력 귀속용 X-Session-Id
│       ├── guest_id.js                # 게스트 ID(localStorage) — 데이터 소유 키, 계획 API의 X-Guest-Id
│       ├── nickname.js                # 닉네임(localStorage) — 화면 표시용 라벨(서버로 전송 안 함)
│       ├── date_utils.js              # 로컬 기준 'YYYY-MM-DD' 포맷/파싱/오늘 날짜 유틸
│       └── components/
│           ├── chat_coach.jsx         # 가로 3칸: 대화 패널(+에이전트 실행 추적) · 오늘 할 일(+미완료 이월) · 체크리스트/보관함(+변경 이력) 패널
│           └── nickname_setup.jsx     # 닉네임(표시 이름) 설정 화면(최초 진입 게이트·변경 오버레이)
└── backend/    # Spring Boot 4.1 / Java 21 (AI 프록시 + 계획 보관함 + 정적 화면 서빙)
    └── src/main/java/.../
        ├── domain/ai/   # controller·service·client·dto — /api/v1/ai/{health,drafts,chats}(+/stream)
        │   └── agent/   # 에이전트 도구 레이어 — AgentTool·AgentToolRegistry(상태별 노출)·tools/
        │                #   루프는 service/AgentRunner — /api/v1/ai/agent/{tools, chats/stream}
        ├── domain/plan/ # 계획 보관함+일일 회고+변경 이력(InMemory/Jdbc 프로필 분리, 기본은 인메모리) — /api/v1/plans CRUD
        │                #   + /plans/{id}/reflections + /plans/{id}/audit-events, 추후 DB로 교체 예정
        └── global/      # 공통: response(ApiResponse) · error(ErrorCode, GlobalExceptionHandler) · config
```

## API 개요

- **AI 프록시** — `/api/v1/ai/{health, drafts, chats}` (+ `/stream` SSE 변형). OpenRouter 키는 서버에만 보관.
- **에이전트(도구 호출)** — `/api/v1/ai/agent/chats/stream`(SSE) + `/api/v1/ai/agent/tools`(도구 카탈로그). 코치가 산문 규약(`===PLAN===`) 대신 **도구를 호출**하고 서버가 실행·검증한다. 도구는 기존 서비스에 위임만 하며(PlanService·ReflectionService·WorkloadRecommendationService·ChatPatchMerger), **어떤 도구를 모델에게 노출할지는 `PlanStatus`의 능력 플래그가 결정한다** — 고정(CONFIRMED) 계획에는 수정 도구(`update_plan_tasks`)가 프롬프트에 실리지 않으므로 모델에게 부탁하는 게 아니라 구조적으로 수정이 불가능하다(이월은 실행 단계 액션이라 남는다). 계획 상태·소유자는 요청 바디가 아니라 서버 저장본과 `X-Guest-Id`에서 읽는다. 루프 상한 4턴, 실패 시 기존 자유 대화 → 비스트리밍 → mock의 4단 폴백. 도구 미지원 모델로 바꾸면 `OPENROUTER_TOOL_CALLING=false`로 코드 배포 없이 예전 경로로 되돌린다. 자세한 내용은 [에이전트 문서](AGENT.md).
- **계획 보관함** — `/api/v1/plans` CRUD + `POST /api/v1/plans/{id}/carry-over`(미완료 이월 — 오늘(KST) 미완료를 내일로, 고정 계획은 409 `PLAN_LOCKED`) + `/api/v1/plans/{id}/reflections`(일일 회고 저장 + `GET`으로 계획별 회고 이력 목록, 최신순). 모든 계획 API는 필수 헤더 `X-Guest-Id`(브라우저별 안정 식별자 — 닉네임은 표시용이라 서버로 오지 않음)로 소유자 격리된다(다른 소유자의 계획은 404). 응답에는 `Cache-Control: no-store`(개인 데이터 캐시 금지, `global/config/WebConfig`)가 붙는다. 저장/수정 시 `startDate`·`duration`은 서버가 tasks 날짜 키에서 산출하고(클라이언트 값 무시) `endDate`는 형식·범위(마지막 할 일 날짜 이상)를 검증한다(`@ValidPlanDates`, 위반은 400 `fieldErrors`). 응답(`PlanResponse`)은 완료율을 서버가 계산한 `progress {done, total}` 필드로 내려준다. 저장소는 리포지토리 인터페이스 + 스프링 프로필로 분리(v0.12.0) — 기본(`!postgres`, 로컬/단위 테스트)은 인메모리(휘발성), `postgres` 프로필(배포)은 PostgreSQL(Supabase 관리형, Flyway 스키마)로 서버 재시작에도 데이터가 복원된다. 데이터 소유 키는 여전히 브라우저 게스트 ID(X-Guest-Id)라, 이 값을 브라우저에서 잃으면 DB에 데이터가 남아 있어도 재연결할 수 없다([백엔드 이관 현황](BACKEND_MIGRATION.md) 참고).
- **계획 상태 수명주기** — 상태 집합·전이 규칙(`DRAFT → CONFIRMED → COMPLETED`, `DRAFT|CONFIRMED → CANCELLED`)·상태별 허용 동작은 `domain/plan/entity/PlanStatus` enum의 **선언적 전이표**가 단일 소유한다. 전이는 명시적 명령 엔드포인트(`POST /api/v1/plans/{id}/{confirm, complete, cancel}` — 본문 없는 POST, 시각은 서버 발급)로 실행되고, 전이표에 없는 전이는 409 `INVALID_STATUS_TRANSITION`. 레거시 PUT 경로(바디 status로 DRAFT→CONFIRMED 고정)는 하위 호환으로 유지되며 같은 전이표를 참조해 판정한다(위반은 기존 409 `PLAN_LOCKED`). 종결 상태(COMPLETED·CANCELLED)는 전면 잠금(조회·회고·삭제만). DB에는 상태가 String으로 저장되고(행 1:1 관례) `V2` 마이그레이션의 CHECK 제약이 최후 안전망. 상태 코드+라벨은 `GET /api/v1/meta/plan-statuses`로 내려간다.
- **변경 이력(Audit)** — `GET /api/v1/plans/{id}/audit-events`(최신순, 읽기 전용 — 이벤트는 서버가 변경 서비스 안에서 직접 발행). 변이 요청(POST/PUT/DELETE)의 선택 헤더 `X-Session-Id`(브라우저 단위 익명 ID)로 "어느 세션의 변경인지"를 기록한다. 계획을 삭제해도 이력은 남는다(전역 1,000건 링버퍼 상한).
- **메타(선택지·라벨)** — `GET /api/v1/meta/{reflection-options, audit-event-types}`(읽기 전용). 회고 선택지·이력 라벨의 소스오브트루스인 서버 enum을 코드+한글 라벨로 내려준다(프론트는 마운트 시 수신, 미가용 시 폴백 사본).
- 응답은 `{ success, data, error }`(ApiResponse)로 래핑되고, 검증 실패는 `error.fieldErrors`, 오류 분기는 `error.code`(ErrorCode)로 판별합니다. Swagger UI: `/swagger-ui.html`.

관련 문서: [기능 상세](FEATURES.md) · [에이전트](AGENT.md) · [실행·배포](DEPLOY.md)
