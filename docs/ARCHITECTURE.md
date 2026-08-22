# 구조

## 화면 구성

화면은 **모바일 우선**입니다 — 하단 고정 탭바로 **대화**(AI 코치와의 대화) · **오늘**("오늘 할 일" — 보관된 계획들의 오늘 항목 모음) · **체크리스트**(생성된 체크리스트) · **챌린지**를 오갑니다. 1023px 이하에서는 한 번에 한 패널이 세로 전체를 쓰고 폰 폭(최대 480px) 컬럼으로 가운데 정렬되며, **1024px 이상에서는 2분할**(왼쪽 대화 고정 + 오른쪽만 탭 전환)로 화면을 꽉 채웁니다 — 패널 집합과 전환 수단은 그대로이고 CSS 미디어쿼리 한 블록으로만 갈립니다.

## 디렉토리 구조

```
DelayNoMore_Release/
├── Dockerfile  # 단일 배포: 프론트 빌드 → 백엔드 static 포함 → 하나의 jar/컨테이너
├── frontend/   # React 19 + Vite (순수 JS/JSX) — 심플 디자인(시스템 폰트, 무배경)
│   └── src/
│       ├── App.jsx                    # 시작 화면(Google 로그인/게스트 시작) + 헤더(닉네임→마이페이지·LED) + 마이페이지(닉네임 변경·로그인/로그아웃) + 저장소 경고 + 코치 화면 마운트
│       ├── ai_engine.js               # 초안 이후 자유 대화·에이전트 대화 + 4단 폴백 (작성 슬롯은 서버 세션으로 이관, v0.19.0)
│       ├── db_service.js              # 백엔드 호출(단일 REST 클라이언트) — 작성 세션 + 작업 명령 + Dashboard + 계획 보관함
│       ├── session_id.js              # 브라우저 단위 익명 세션 ID(localStorage) — 변경 이력 귀속용 X-Session-Id
│       ├── guest_id.js                # 게스트 ID(localStorage) — 데이터 소유 키, 계획 API의 X-Guest-Id
│       ├── nickname.js                # 닉네임(localStorage) — 표시용 라벨 + 게스트 랜덤 생성(가입 시 1회 서버 이관, v0.22.0)
│       ├── auth.js                    # 로그인 상태(localStorage) — 세션 토큰·닉네임·이메일 (Authorization: Bearer, v0.22.0)
│       ├── date_utils.js              # 로컬 기준 'YYYY-MM-DD' 포맷/파싱/오늘 날짜 유틸
│       └── components/
│           ├── chat_coach.jsx         # 하단 탭 4개: 대화 패널(+에이전트 실행 추적) · 오늘 할 일(+미완료 이월) · 체크리스트/보관함(+변경 이력) · 챌린지
│           └── nickname_setup.jsx     # 닉네임(표시 이름) 변경 오버레이(v0.22.0부터 최초 진입 게이트에서는 빠짐)
└── backend/    # Spring Boot 4.1 / Java 21 (AI 프록시 + 계획 보관함 + 정적 화면 서빙)
    └── src/main/java/.../
        ├── domain/ai/   # controller·service·client·dto — /api/v1/ai/{health,drafts,chats}(+/stream) + plan-draft-sessions
        │   └── agent/   # 에이전트 도구 레이어 — AgentTool·AgentToolRegistry(상태별 노출)·tools/
        │                #   + AgentProfile(상태별 페르소나: 코치→전문 에이전트→회고 도우미, v0.17.0)
        │                #   루프는 service/AgentRunner — /api/v1/ai/agent/{tools, chats/stream}
        ├── domain/plan/ # 계획 보관함+일일 회고+변경 이력(InMemory/Jdbc 프로필 분리, 기본은 인메모리) — /api/v1/plans CRUD
        │                #   + 단일 작업 완료 명령 + /dashboard/today 읽기 모델 + reflections/audit-events
        ├── domain/challenge/ # 정원 한정 챌린지(조건부 UPDATE 정원 판정, v0.21.0) — /api/v1/challenges
        │                     # 개설 없음(v0.23.0) — 계획 고정 시 조건별 자동 생성(support/ChallengeCondition)
        ├── domain/auth/ # Google 로그인 + 세션 + 게스트 흡수(re-key) (v0.22.0) — /api/v1/auth/{google,logout,config}
        └── global/      # 공통: response(ApiResponse) · error(ErrorCode, GlobalExceptionHandler) · config
                         #   + auth(@Owner ArgumentResolver — Bearer 세션이면 회원, 없으면 게스트 폴백, v0.22.0)
```

## API 개요

- **AI 프록시** — `/api/v1/ai/{health, drafts, chats}` (+ `/stream` SSE 변형). OpenRouter 키는 서버에만 보관.
- **계획 작성 세션** — `/api/v1/ai/plan-draft-sessions`는 목표·기간·하루 시간·현재 수준의 질문 순서와 입력 검증, AI 초안 생성·최초 보관을 서버가 소유한다. 프론트는 메시지를 보내고 `{reply, slots, nextInput, plan}`을 표시한다(v0.19.0). 세션은 작성 중 임시 상태라 인메모리이며, 저장된 계획은 기존 저장소 계약을 따른다.
- **에이전트(도구 호출)** — `/api/v1/ai/agent/chats/stream`(SSE) + `/api/v1/ai/agent/tools`(도구 카탈로그). 코치가 산문 규약(`===PLAN===`) 대신 **도구를 호출**하고 서버가 실행·검증한다. 도구는 기존 서비스에 위임만 하며(PlanService·ReflectionService·WorkloadRecommendationService·ChatPatchMerger), **어떤 도구를 모델에게 노출할지는 `PlanStatus`의 능력 플래그가 결정한다** — 고정(CONFIRMED) 계획에는 수정 도구(`update_plan_tasks`)가 프롬프트에 실리지 않으므로 모델에게 부탁하는 게 아니라 구조적으로 수정이 불가능하다(이월은 실행 단계 액션이라 남는다). 계획 상태·소유자는 요청 바디가 아니라 서버 저장본과 `X-Guest-Id`에서 읽는다. 루프 상한 4턴, 실패 시 기존 자유 대화 → 비스트리밍 → mock의 4단 폴백. 도구 미지원 모델로 바꾸면 `OPENROUTER_TOOL_CALLING=false`로 코드 배포 없이 예전 경로로 되돌린다. 자세한 내용은 [에이전트 문서](AGENT.md).
- **계획 보관함** — `/api/v1/plans` CRUD + `POST /api/v1/plans/{id}/carry-over`(미완료 이월 — 오늘(KST) 미완료를 내일로, 고정 계획은 409 `PLAN_LOCKED`) + `/api/v1/plans/{id}/reflections`(일일 회고 저장 + `GET`으로 계획별 회고 이력 목록, 최신순). 모든 계획 API는 필수 헤더 `X-Guest-Id`(브라우저별 안정 식별자 — 닉네임은 표시용이라 서버로 오지 않음)로 소유자 격리된다(다른 소유자의 계획은 404). 응답에는 `Cache-Control: no-store`(개인 데이터 캐시 금지, `global/config/WebConfig`)가 붙는다. 저장/수정 시 `startDate`·`duration`은 서버가 tasks 날짜 키에서 산출하고(클라이언트 값 무시) `endDate`는 형식·범위(마지막 할 일 날짜 이상)를 검증한다(`@ValidPlanDates`, 위반은 400 `fieldErrors`). 응답(`PlanResponse`)은 완료율을 서버가 계산한 `progress {done, total}` 필드로 내려준다. 저장소는 리포지토리 인터페이스 + 스프링 프로필로 분리(v0.12.0) — 기본(`!postgres`, 로컬/단위 테스트)은 인메모리(휘발성), `postgres` 프로필(배포)은 PostgreSQL(Supabase 관리형, Flyway 스키마)로 서버 재시작에도 데이터가 복원된다. 데이터 소유 키는 여전히 브라우저 게스트 ID(X-Guest-Id)라, 이 값을 브라우저에서 잃으면 DB에 데이터가 남아 있어도 재연결할 수 없다([백엔드 이관 현황](BACKEND_MIGRATION.md) 참고).
- **실행 명령·오늘 읽기 모델** — 작업 완료/해제는 `PUT /api/v1/plans/{id}/tasks/{taskId}/completion`으로 수행한다. 서버가 저장된 작업의 날짜를 찾아 상태·과거 날짜 잠금·이력·진행률을 함께 처리하므로 전체 계획 PUT이 필요 없다. `GET /api/v1/dashboard/today`는 오늘 작업, 오늘 회고, 합계와 완료 가능 여부를 조합해 오늘 탭의 읽기 모델로 반환한다(v0.19.0).
- **계획 상태 수명주기** — 상태 집합·전이 규칙(`DRAFT → CONFIRMED → COMPLETED`, `DRAFT|CONFIRMED → CANCELLED`)·상태별 허용 동작은 `domain/plan/entity/PlanStatus` enum의 **선언적 전이표**가 단일 소유한다. 전이는 명시적 명령 엔드포인트(`POST /api/v1/plans/{id}/{confirm, complete, cancel}` — 본문 없는 POST, 시각은 서버 발급)로 실행되고, 전이표에 없는 전이는 409 `INVALID_STATUS_TRANSITION`. 레거시 PUT 경로(바디 status로 DRAFT→CONFIRMED 고정)는 하위 호환으로 유지되며 같은 전이표를 참조해 판정한다(위반은 기존 409 `PLAN_LOCKED`). 종결 상태(COMPLETED·CANCELLED)는 전면 잠금(조회·회고·삭제만). DB에는 상태가 String으로 저장되고(행 1:1 관례) `V2` 마이그레이션의 CHECK 제약이 최후 안전망. 상태 코드+라벨은 `GET /api/v1/meta/plan-statuses`로 내려간다.
- **변경 이력(Audit)** — `GET /api/v1/plans/{id}/audit-events`(최신순, 읽기 전용 — 이벤트는 서버가 변경 서비스 안에서 직접 발행). 변이 요청(POST/PUT/DELETE)의 선택 헤더 `X-Session-Id`(브라우저 단위 익명 ID)로 "어느 세션의 변경인지"를 기록한다. 계획을 삭제해도 이력은 남는다(전역 1,000건 링버퍼 상한).
- **메타(선택지·라벨)** — `GET /api/v1/meta/{reflection-options, audit-event-types}`(읽기 전용). 회고 선택지·이력 라벨의 소스오브트루스인 서버 enum을 코드+한글 라벨로 내려준다(프론트는 마운트 시 수신, 미가용 시 폴백 사본).
- 응답은 `{ success, data, error }`(ApiResponse)로 래핑되고, 검증 실패는 `error.fieldErrors`, 오류 분기는 `error.code`(ErrorCode)로 판별합니다. Swagger UI: `/swagger-ui.html`.

관련 문서: [기능 상세](FEATURES.md) · [에이전트](AGENT.md) · [실행·배포](DEPLOY.md)
