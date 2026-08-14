# API 데이터 레퍼런스

모든 동작(엔드포인트)의 요청·응답 데이터를 JSON 예시로 정리한 문서입니다. **v0.19.0 기준**이며, 소스(컨트롤러·DTO)와 1:1로 대조해 작성했습니다. v0.19.0은 프론트의 작성 흐름·작업 토글·오늘 화면 조합을 서버 명령과 읽기 모델로 이관했습니다. QA 시 curl 호출·네트워크 탭 확인의 기준 자료로 사용합니다.

- 베이스 경로: `/api/v1`
- 스펙 자동 문서: 서버 실행 후 Swagger UI(`/swagger-ui/index.html`)에서도 확인 가능
- 관련 문서: [QA_CHECKLIST.md](QA_CHECKLIST.md) · [BACKEND_MIGRATION.md](BACKEND_MIGRATION.md)

## 요청·응답 헤더 (v0.11.0)

| 헤더 | 방향 | 대상 | 필수 | 설명 |
|---|---|---|---|---|
| `X-Guest-Id` | 요청 | `/api/v1/plans*` 전부(GET 포함) | **필수** | 브라우저가 생성한 소유자 키(안정 식별자). 값 규칙 `^[A-Za-z0-9-]{8,64}$`(예: `550e8400-e29b-41d4-a716-446655440000`). 누락 → 400 `GUEST_ID_REQUIRED`, 형식 위반 → 400 `GUEST_ID_INVALID`. **닉네임은 이 헤더에 담기지 않으며 서버로 전송되지 않는다**(화면 표시용 라벨). |
| `X-Guest-Id` | 요청 | `/api/v1/ai/plan-draft-sessions*`, `/api/v1/dashboard/today` | **필수** | 작성 세션의 소유자와 오늘 읽기 모델의 범위를 정한다(v0.19.0). |
| `X-Session-Id` | 요청 | `/plans` POST·PUT·DELETE, 회고 PUT | 선택 | 변경 이력의 "이 브라우저/다른 세션" 귀속용. 없으면 이력에 `sessionId: null`. 읽기(GET)에는 받지 않는다. |
| `Cache-Control: no-store` | 응답 | `/api/v1/plans*`, `/dashboard/today`, `/ai/plan-draft-sessions*` | — | 소유자별 개인 데이터·작성 중 입력이라 프록시·브라우저 캐시 금지(`global/config/WebConfig`, v0.19.0 범위 확장). |
| `Access-Control-Allow-Headers: X-Guest-Id` | 응답(프리플라이트) | OPTIONS | — | CORS 프리플라이트에서 `X-Guest-Id` 요청 헤더 허용(`@CrossOrigin` 기본 allowedHeaders). |

> **소유자(owner) 필드는 서버 내부 전용**입니다 — `X-Guest-Id`로 받아 `Plan.owner`·`AuditEvent.ownerId`로 저장하지만, **어떤 응답 바디(`PlanResponse`·`AuditEventResponse` 등)에도 노출되지 않습니다**(응답 스키마는 v0.10.0과 동일). 격리는 서버가 필터링으로 수행하고, 다른 소유자의 리소스는 존재 자체를 숨겨 404 또는 빈 목록으로 응답합니다.
>
> **저장 한도**: 소유자당 최대 10개(초과 시 400 `PLAN_LIMIT_EXCEEDED`) + 전역 최대 200개(초과 시 503 `PLAN_STORE_FULL`). 둘 다 `POST /plans`에서만 검사합니다.

## 공통 응답 래퍼

SSE를 제외한 모든 REST 응답은 아래 형태로 감쌉니다.

```json
// 성공
{ "success": true, "data": { }, "error": null }

// 실패 — 프론트 분기는 error.code로만 한다. fieldErrors는 검증 실패(400)일 때만 존재
{ "success": false, "data": null,
  "error": { "code": "INVALID_INPUT", "message": "입력값을 다시 확인해주세요.",
             "fieldErrors": { "duration": "기간은 1~14일 사이의 정수여야 합니다." } } }
```

### 오류 코드 전체

| code | HTTP | 발생 지점 |
|---|---|---|
| `INVALID_INPUT` | 400 | 모든 `@Valid` 검증 실패 (fieldErrors 동반) |
| `GUEST_ID_REQUIRED` | 400 | `X-Guest-Id` 헤더 누락/공백 |
| `GUEST_ID_INVALID` | 400 | `X-Guest-Id` 형식 위반(영문·숫자·하이픈 8~64자 아님) |
| `PLAN_LIMIT_EXCEEDED` | 400 | 소유자당 계획 개수 초과(최대 10개) |
| `REFLECTION_DATE_INVALID` | 400 | 회고 날짜가 YYYY-MM-DD가 아님 |
| `REFLECTION_DATE_NOT_TODAY` | 400 | 회고 날짜가 KST 오늘이 아님 |
| `PLAN_NOT_FOUND` | 404 | 계획 단건 조회·수정·삭제·회고 저장 시 없는 id 또는 다른 소유자 |
| `REFLECTION_NOT_FOUND` | 404 | 해당 날짜 회고 없음 |
| `PLAN_LOCKED` | 409 | CONFIRMED·종결(COMPLETED/CANCELLED) 계획에 허용 외 변경 (v0.8.0) |
| `INVALID_STATUS_TRANSITION` | 409 | 상태 전이 엔드포인트(confirm·complete·cancel)에서 전이표에 없는 전이 |
| `PAST_TASK_LOCKED` | 409 | CONFIRMED 계획의 **지난 날짜(KST)** 완료 체크/해제 PUT (v0.14.2) |
| `PLAN_STORE_FULL` | 503 | 전역 저장소 상한 초과(서버 메모리 보호, 최대 200개) |
| `AI_UPSTREAM_ERROR` | 502 | OpenRouter 호출 실패 |
| `AI_RESPONSE_INVALID` | 502 | AI 응답 해석·정규화 불가 |
| `INTERNAL_ERROR` | 500 | 그 외 서버 오류 |

---

## AI (`/api/v1/ai`)

### 1. GET /ai/health — AI 연결 상태 점검

```json
// 응답 data (정상 / 실패)
// toolCalling(v0.15.0, additive): 에이전트(도구 호출) 경로를 쓸 수 있는지. 프론트는 이 값으로
//   에이전트 엔드포인트와 기존 자유 대화 경로 중 하나를 고른다. 미연결이면 항상 false.
//   서버 스위치는 OPENROUTER_TOOL_CALLING(기본 true) — 도구 미지원 모델로 바꿔도 코드 배포 불필요.
{ "connected": true, "toolCalling": true }
{ "connected": false, "reason": "API Key 미설정", "toolCalling": false }
```

### 2. POST /ai/drafts — 계획 초안 생성

```json
// 요청 — duration 1~14, dailyHours 1~24는 서버가 강제.
// refinementPrompt + previousTasks는 재수정 요청일 때만 함께 보낸다.
// tasksPerDay(선택, 1~5, v0.13.0): 지정하면 프롬프트가 "하루 정확히 N개"를 요구하고 서버가
//   날짜별 개수를 검증한다(어긋나면 502 AI_RESPONSE_INVALID). 생략 시 기존 동작(dailyHours 비례 범위).
{ "goalName": "정보처리기사 실기 합격", "duration": 7, "dailyHours": 2,
  "currentLevel": "필기 합격, 실기는 처음",
  "refinementPrompt": "주말은 분량 줄여줘",
  "previousTasks": { "2026-07-19": ["요구사항 분석 개념 정리"] } }

// 응답 data — 날짜맵. v0.8.0부터 서버(normalizeDraftPlan)가 날짜 키를 보장한다
// (LLM이 배열·"Day N" 키를 반환해도 오늘부터 위치 기반으로 실제 날짜를 합성)
{ "2026-07-19": ["요구사항 분석 개념 정리", "기출 1회분 풀기"],
  "2026-07-20": ["데이터베이스 SQL 정리"] }
```

오류: 400 `INVALID_INPUT`+fieldErrors, 502 `AI_UPSTREAM_ERROR` / `AI_RESPONSE_INVALID`

### 3. POST /ai/drafts/stream — 초안 생성 (SSE)

요청은 2와 동일. 응답은 `data:` 한 줄당 compact JSON 이벤트 하나.

```json
{ "type": "day", "date": "2026-07-19", "tasks": ["요구사항 분석 개념 정리", "기출 1회분 풀기"] }
{ "type": "done" }
{ "type": "error", "m": "계획 생성 스트리밍 중 오류가 발생했습니다." }
```

### 4. POST /ai/chats — 계획 코치 자유 대화

```json
// 요청 — message만 필수, 나머지는 컨텍스트(없으면 서버가 기본값 보정)
{ "goalName": "정보처리기사 실기 합격", "duration": 7, "dailyHours": 2,
  "currentLevel": "필기 합격",
  "message": "기간 3일 늘려줘",
  "tasks": { "2026-07-19": [ { "id": "t-2026-07-19-0", "content": "기출 1회분 풀기", "completed": false } ] },
  "history": [ { "role": "user", "content": "..." }, { "role": "assistant", "content": "..." } ] }

// 응답 data — [v0.9.2] LLM은 변경된 날짜만 담은 patch를 내지만(출력 토큰 절약), 서버
// (ChatPatchMerger)가 요청의 tasks에 병합해 tasks에는 정규화된 전체 계획({id,content,completed}
// 객체, 변경 안 된 날짜 포함)이 담긴다. 완료 체크는 날짜+content가 같으면 보존된다.
// planUpdated=false(단순 답변)면 tasks 필드 자체가 생략된다.
{ "reply": "기간을 3일 연장해 마지막에 복습일을 넣었어요.",
  "planUpdated": true,
  "tasks": { "2026-07-19": [ { "id": "t-2026-07-19-0", "content": "기출 1회분 풀기", "completed": false } ],
             "2026-07-26": [ { "id": "t-2026-07-26-0", "content": "오답 복습", "completed": false } ],
             "2026-07-27": [ { "id": "t-2026-07-27-0", "content": "모의시험", "completed": false } ] } }
```

### 5. POST /ai/chats/stream — 자유 대화 (SSE)

요청은 4와 동일. 산문은 token 이벤트로 흘러오고, 계획 변경(서버가 병합한 전체 tasks)은 스트림 끝에 plan 이벤트 한 번.

```json
{ "type": "token", "t": "기간을 3일 " }
{ "type": "plan", "tasks": { "2026-07-19": [ { "id": "t-2026-07-19-0", "content": "기출 1회분 풀기", "completed": false } ],
                             "2026-07-26": [ { "id": "t-2026-07-26-0", "content": "오답 복습", "completed": false } ] } }
{ "type": "done" }
{ "type": "error", "m": "AI 응답 스트리밍 중 오류가 발생했습니다." }
```

### 6. POST /ai/plan-draft-sessions — 서버 소유 계획 작성 시작 (v0.19.0)

`X-Guest-Id`가 가리키는 임시 작성 세션을 만들고 첫 질문을 반환한다. 질문 순서·입력 검증·초안
저장은 서버가 소유하며, 세션은 인메모리이므로 서버 재시작 후에는 새로 시작한다.

```json
// 응답 data
{ "sessionId": "f9c7...", "reply": "어떤 목표를 이루고 싶으신가요?",
  "slots": { "goalName": "", "duration": 0, "dailyHours": 0, "currentLevel": "" },
  "nextInput": "goalName", "plan": null }
```

### 7. POST /ai/plan-draft-sessions/{sessionId}/messages — 작성 답변 전송 (v0.19.0)

```json
// 요청
{ "message": "정보처리기사 실기 합격" }

// 응답 data — 마지막(현재 수준) 답변이면 plan이 서버에 저장되어 함께 온다
{ "sessionId": "f9c7...", "reply": "며칠 동안 진행할 계획인가요? (1~14일)",
  "slots": { "goalName": "정보처리기사 실기 합격", "duration": 0, "dailyHours": 0, "currentLevel": "" },
  "nextInput": "duration", "plan": null }
```

### 8. GET /ai/agent/tools — 에이전트 카탈로그 [v0.15.0, v0.17.0에서 profile 추가]

현재 계획 상태의 **프로필**(누가 응대하는가)과, **실제로 모델에게 노출되는** 도구만 내려온다.
프롬프트에 실리는 목록과 같은 소스(`AgentToolRegistry`)를 쓰므로, 상태별로 호출해 보면 권한
모델을 그대로 확인할 수 있다.

**필수 헤더** `X-Guest-Id` (도구가 소유 데이터를 다루므로). 쿼리 `planId`는 선택 —
생략하거나 접근할 수 없는 계획이면 보관 전 초안(DRAFT) 기준으로 답한다(404를 내지 않는다).

```json
// GET /ai/agent/tools?planId=12   (계획이 CONFIRMED인 경우)
// 응답 data — 프로필이 전문 에이전트로 바뀌고, update_plan_tasks가 목록에서 사라진다.
{ "profile": { "name": "DOMAIN_EXPERT", "label": "정보처리기사 실기 전문 에이전트" },
  "tools": [
    { "name": "get_today_tasks", "mutating": false,
      "description": "Read the tasks and their completion state for one date …",
      "parameters": { "type": "object", "properties": { "date": { "type": "string", "description": "…" } }, "required": [] } },
    { "name": "get_weekly_summary", "mutating": false, "description": "…", "parameters": { … } },
    { "name": "get_reflection_history", "mutating": false, "description": "…", "parameters": { … } },
    { "name": "get_workload_recommendation", "mutating": false, "description": "…", "parameters": { … } },
    { "name": "carry_over_tasks", "mutating": true, "description": "…", "parameters": { … } } ] }
```

> v0.17.0에서 응답이 맨 배열에서 `{profile, tools}` 래핑으로 바뀌었다(형식 변경 — 당시 이 API의
> 프론트 소비처가 없어 호환성 부담 없이 변경). 프로필 3종(코치/전문가/회고 도우미) 표는
> [에이전트 문서](AGENT.md) 참고.

상태별 노출 표는 [에이전트 문서](AGENT.md#2-권한-모델--상태-기계--도구-노출) 참고.

### 7. POST /ai/agent/chats/stream — 에이전트 대화 (SSE) [v0.15.0]

요청 본문은 4·5와 같고 **`planId`(선택, Long)** 가 추가된다 — 도구가 서버 저장본(완료율·회고)을
읽거나 도메인 액션(이월)을 부를 대상이다. 보관 전 초안이면 생략하고, 그 경우 서버 데이터를
요구하는 도구는 실행 대신 사유를 돌려준다. 기존 `/chats`·`/chats/stream`은 이 필드를 무시한다.

**필수 헤더** `X-Guest-Id`, **선택 헤더** `X-Session-Id`(이월 등 변이 도구의 이력 귀속용).

계획 상태는 요청 바디가 아니라 **서버 저장본에서** 읽는다 — 클라이언트가 상태를 주장해
고정된 계획의 수정 도구를 열 수 없게 하기 위해서다.

```json
{ "type": "profile", "name": "DOMAIN_EXPERT", "label": "정보처리기사 실기 전문 에이전트" }
{ "type": "step", "n": 1 }
{ "type": "tool_call", "id": "call_0", "name": "get_weekly_summary", "args": {} }
{ "type": "tool_result", "id": "call_0", "ok": true, "summary": "{\"startDate\":\"2026-07-20\",\"totalDone\":9,…" }
{ "type": "step", "n": 2 }
{ "type": "token", "t": "이번 주는 12개 중 9개를 끝내셨어요." }
{ "type": "plan", "tasks": { "2026-07-19": [ { "id": "t-2026-07-19-0", "content": "기출 1회분 풀기", "completed": false } ] } }
{ "type": "plan_refresh", "planId": 12 }
{ "type": "done" }
{ "type": "error", "m": "에이전트 응답 중 오류가 발생했습니다." }
```

- `profile`(v0.17.0)은 스트림 시작 시 1회 — 이번 실행이 실제로 쓴 프로필(서버 저장 상태에서
  파생). 구버전 클라이언트는 미지 타입을 무시하므로 하위 호환이다.
- `tool_result.ok=false`면 실행이 거부된 것이고, `summary`에 한국어 사유가 담긴다
  (노출되지 않은 도구 호출·인자 형식 오류·대상 없음 등). 루프는 끊기지 않고 이어진다.
- **`plan`과 `plan_refresh`는 다르다.** `plan`은 아직 저장되지 않은 변경(`update_plan_tasks`)이라
  클라이언트가 초안으로 채택해 평소 경로로 저장해야 하고, `plan_refresh`는 서버가 이미 저장한
  변경(`carry_over_tasks`)이라 클라이언트는 `GET /plans/{id}`로 다시 읽기만 해야 한다.
  후자를 초안으로 덮어쓰면 뒤따르는 PUT이 409 `PLAN_LOCKED`로 튕긴다.
- 루프 상한(4턴)까지 가고도 답을 못 내면 502 `AI_TOOL_LOOP_EXCEEDED` 사유의 error 이벤트가
  나가고, 클라이언트는 기존 `/ai/chats/stream`으로 폴백한다.

---

## 계획 보관함 (`/api/v1/plans`)

**모든 메서드(GET 포함)는 필수 헤더 `X-Guest-Id`를 받습니다** — 게스트 ID는 브라우저가 최초 1회 생성하는 안정 식별자로, 계획·회고·변경 이력이 게스트 ID별로 격리됩니다. 닉네임은 화면 표시용 라벨일 뿐 서버로 오지 않으므로, **다른 브라우저에서 같은 닉네임을 써도 별도의 보관함**이 됩니다. ASCII 값이라 인코딩이 필요 없습니다. 예: `X-Guest-Id: 550e8400-e29b-41d4-a716-446655440000`.

- 규칙: 트림 후 영문·숫자·하이픈 8~64자 (`crypto.randomUUID` 및 폴백 허용)
- 누락/공백 → 400 `GUEST_ID_REQUIRED`, 규칙 위반 → 400 `GUEST_ID_INVALID`
- 다른 게스트의 계획 접근 → 404 `PLAN_NOT_FOUND` (존재 여부를 숨김), 변경 이력은 빈 목록

> **보안 성격**: 게스트 ID는 인증 수단이 아니지만 현재 구조에서는 데이터를 여는 bearer 토큰과 같습니다. "로그인 전 브라우저 단위 임시 개인 보관함"입니다. v0.12.0부터 서버 데이터는 PostgreSQL(Supabase)에 영속되어 서버 재시작으로는 사라지지 않지만, 이 헤더 값(브라우저 localStorage) 자체를 잃으면 — 브라우저 데이터 삭제, 다른 브라우저·기기, 프라이빗 모드 종료 — 복구할 수 없습니다. 닉네임은 서버로 전송되지 않는 표시용 라벨일 뿐이라, 닉네임을 기억하고 있어도 그것만으로 소유자를 조회해 재연결할 방법이 없습니다(로그인 도입 전까지는 게스트 ID 값 자체를 알아야만 재연결 가능 — 예: 브라우저 콘솔에서 `localStorage.setItem('delaynomore:guestId', '<UUID>')`로 수동 복원). HTTP 배포에서는 네트워크상 게스트 ID 보호도 보장되지 않으므로 민감한 정보를 저장하면 안 됩니다.

**응답 캐싱**: 계획 API 응답에는 `Cache-Control: no-store`가 붙습니다 — 소유자별 개인 데이터가 프록시·브라우저 캐시에 남아 재사용되지 않게 합니다. **CORS**: 프리플라이트(OPTIONS)에서 `X-Guest-Id` 요청 헤더가 허용됩니다(`Access-Control-Allow-Headers`).

변이 메서드(POST/PUT/DELETE)는 추가로 선택 헤더 `X-Session-Id: s-abc123`을 받아 변경 이력에 세션을 귀속시킵니다(같은 게스트 ID를 여러 브라우저 탭에서 쓸 때 "이 브라우저/다른 세션" 구분용). 없으면(구형 클라이언트·curl) 이력에 null로 기록됩니다. 읽기(GET)는 이력을 남기지 않으므로 이 헤더는 받지 않습니다.

### 6. POST /plans — 계획 보관

```json
// 요청 — goalName·currentLevel: 공백 제외 2자 이상 / duration: 1~365 / dailyHours: 1~24
// tasks(v0.8.0 형식 검증): 키는 YYYY-MM-DD, 값은 배열, 항목은 {id, content, completed?}
// status: DRAFT|CONFIRMED만 허용, 생략(null) 시 DRAFT. POST로 CONFIRMED 직접 생성은 허용.
// [날짜 규칙 — v0.9.1] startDate·duration은 서버가 tasks 날짜 키에서 산출한다(요청 값은
//   무시): startDate = 최초 날짜 키, duration = [startDate, endDate] 기간(일수). endDate는
//   요청 값을 유지하되 ISO(YYYY-MM-DD)이고 마지막 할 일 날짜 이상인지 검증(@ValidPlanDates,
//   위반은 400 fieldErrors.endDate). 응답에는 서버 산출·검증된 값이 담긴다.
{ "goalName": "정보처리기사 실기 합격", "duration": 7, "dailyHours": 2,
  "currentLevel": "필기 합격, 실기는 처음",
  "tasks": { "2026-07-19": [ { "id": "t-2026-07-19-0", "content": "기출 1회분 풀기", "completed": false } ] },
  "status": "DRAFT", "confirmedAt": null,
  "startDate": "2026-07-19", "endDate": "2026-07-25",
  "createdAt": "2026-07-19T09:30:00.000Z" }

// 응답 data (PlanResponse) — 요청 본문 + id, savedAt(서버가 찍는 epoch millis),
// progress(전체 완료/전체 개수 — 서버가 tasks에서 계산, 프론트는 표시만)
{ "id": 1, "goalName": "정보처리기사 실기 합격", "duration": 7, "dailyHours": 2,
  "currentLevel": "필기 합격, 실기는 처음",
  "tasks": { "2026-07-19": [ { "id": "t-2026-07-19-0", "content": "기출 1회분 풀기", "completed": false } ] },
  "status": "DRAFT", "confirmedAt": null,
  "startDate": "2026-07-19", "endDate": "2026-07-25",
  "createdAt": "2026-07-19T09:30:00.000Z", "savedAt": 1784856600000,
  "progress": { "done": 0, "total": 1 }, "recommendationEligible": false }
```

`recommendationEligible`(v0.13.0): 다음 계획 분량 추천 버튼 노출 조건을 서버가 내려준다 — 고정(CONFIRMED)
+ 전부 완료했거나, 3일 이상 실행(오늘 이하 날짜 버킷 3개 이상)한 계획이면 `true`.

오류: 400 `INVALID_INPUT`+fieldErrors, 400 `GUEST_ID_REQUIRED`/`GUEST_ID_INVALID`, 400 `PLAN_LIMIT_EXCEEDED`(소유자당 10개), 503 `PLAN_STORE_FULL`(전역 200개)

### 7. GET /plans — 목록 조회 (최근 저장순)

```json
// 응답 data — PlanResponse 배열. 진행률은 서버 계산 progress 필드를 쓴다
// (추후 목록 API가 tasks 전체를 안 내려도 되는 기반).
[ { "id": 2, "goalName": "...", "tasks": { }, "progress": { "done": 1, "total": 4 }, "savedAt": 1784860000000 },
  { "id": 1, "goalName": "...", "tasks": { }, "progress": { "done": 0, "total": 1 }, "savedAt": 1784856600000 } ]
```

### 8. GET /plans/{id} — 단건 조회

응답 data는 6의 PlanResponse와 동일. 없는 id **또는 다른 소유자의 계획**이면 404 `PLAN_NOT_FOUND`(존재 은닉).

### 9. PUT /plans/{id} — 계획 수정

요청·응답 본문은 6과 동일(POST·PUT 공용 DTO). 날짜 규칙(6 참고)도 동일하게 적용되며,
`startDate`는 **생성 시 산출된 뒤 불변**이라 수정 요청의 startDate 값은 무시된다(duration은 매
수정마다 `[startDate, endDate]`로 재산출). v0.8.0 서버 가드:

- CONFIRMED 계획은 **completed 토글과 완전 동일(no-op) PUT만 허용**
- 그 외 변경(goalName·duration·항목 내용/구조·DRAFT 롤백·confirmedAt 변경)은 409
- 토글도 **오늘(KST)·미래 날짜만** 허용(v0.14.2) — 이월이 "오늘 → 내일"뿐이라 미루지 않은 지난
  항목은 놓친 것으로 확정되며, 지난 날짜는 체크·해제 모두 409 `PAST_TASK_LOCKED`(완료율 소급
  조작 방지). DRAFT는 자유 수정 단계라 적용되지 않는다.

```json
// 409 응답
{ "success": false, "data": null,
  "error": { "code": "PLAN_LOCKED",
             "message": "고정(CONFIRMED)된 계획은 완료 체크 외에는 수정할 수 없습니다.",
             "fieldErrors": null } }
```

그 외 오류: 400 `INVALID_INPUT`, 400 `GUEST_ID_REQUIRED`/`GUEST_ID_INVALID`, 404 `PLAN_NOT_FOUND`(없는 id 또는 다른 소유자)

### 10. PUT /plans/{id}/tasks/{taskId}/completion — 단일 작업 완료 상태 변경 (v0.19.0)

전체 계획 문서를 보내지 않는 실행 명령이다. 서버가 저장된 tasks에서 `taskId`와 날짜를 찾으므로
클라이언트가 날짜·구조를 바꾸거나 CONFIRMED 계획의 지난 날짜 잠금을 우회할 수 없다.

```json
// 요청
{ "completed": true }

// 응답 data — 일반 PlanResponse, progress는 서버가 재계산
{ "id": 12, "status": "CONFIRMED", "progress": { "done": 4, "total": 10 }, "tasks": { "...": [] } }
```

오류: 400 `INVALID_INPUT`(없는/중복 taskId 또는 본문 형식), 404 `PLAN_NOT_FOUND`, 409
`PLAN_LOCKED`(종결 계획) / `PAST_TASK_LOCKED`(CONFIRMED 계획의 지난 날짜).

### 11. DELETE /plans/{id} — 계획 삭제

CONFIRMED여도 삭제는 허용합니다(잠긴 계획의 탈출구). 변경 이력은 지우지 않습니다(이벤트에 소유자가 기록되어, 삭제 후에도 소유자는 이력을 조회할 수 있습니다 — 16 참고).

```json
{ "success": true, "data": null, "error": null }
```

없는 id **또는 다른 소유자의 계획**이면 404 `PLAN_NOT_FOUND`(존재 은닉).

### 11. POST /plans/{id}/carry-over — 미완료 이월

**본문 없는 POST**(`X-Session-Id` 선택 헤더만). 이월 규칙은 서버 소유입니다: **오늘(KST)의
미완료 항목만 내일로** 옮기고(항목 ID 보존 — 어제 이전으로 밀린 항목은 대상이 아님), 내일이
계획 기간 밖이면 endDate·duration을 하루 연장합니다. 하루씩만 이동합니다 — 내일로 미룬 항목은
그날이 "오늘"이 되면 다시 다음 날로 미룰 수 있습니다. 예전엔 프론트가 계산해 PUT으로 보냈지만,
연산 소유권이 서버로 이관됐습니다. v0.14.1부터 이월은 실행 단계 액션으로 취급되어
**고정(CONFIRMED) 계획에서도 허용**됩니다(`PlanStatus.allowsCarryOver`) — 종결 상태만 거부.

```json
// 응답 data — movedCount 0은 "옮길 미완료 없음"의 정상 no-op(계획 불변, 이력 없음)
{ "movedCount": 2, "targetDate": "2026-07-21",
  "plan": { "id": 1, "goalName": "...", "tasks": { }, "endDate": "2026-07-21",
            "progress": { "done": 1, "total": 3 }, "savedAt": 1784860000000 } }
```

이동이 있으면 변경 이력에 `PLAN_UPDATED`(detail: `미완료 2건을 2026-07-21로 이동`)가 발행됩니다.

오류: 404 `PLAN_NOT_FOUND`(없는 id 또는 다른 소유자), 409 `PLAN_LOCKED`(종결(COMPLETED/CANCELLED) 계획 — 전면 잠금)

## 계획 상태 수명주기 (`PlanStatus`)

계획 상태의 집합·전이 규칙·상태별 허용 동작은 서버의 `PlanStatus` enum(선언적 전이표)이
단일 소유합니다. DB의 CHECK 제약은 최후 안전망일 뿐입니다.

```
DRAFT ──confirm──▶ CONFIRMED ──complete──▶ COMPLETED   (종결)
  │                    │
  └──────cancel────────┴──────────────────▶ CANCELLED   (종결)
```

| 상태 | 라벨 | 허용 동작 |
|---|---|---|
| `DRAFT` | 초안 | 자유 수정(PUT)·이월·confirm·cancel |
| `CONFIRMED` | 고정 | completed 토글 PUT만·이월(v0.14.1)·complete·cancel |
| `COMPLETED` | 완료 | 없음(종결) — 조회·회고·삭제만 |
| `CANCELLED` | 중단 | 없음(종결) — 조회·회고·삭제만 |

- self-loop 없음: 같은 상태로의 전이(예: CONFIRMED에 confirm)는 409 `INVALID_STATUS_TRANSITION`.
- **PUT 하위 호환**: 저장 요청 바디의 `status`는 여전히 `DRAFT|CONFIRMED`만 허용되고, PUT을 통한
  DRAFT→CONFIRMED 고정도 계속 동작합니다(기존 프론트 무변경). 종결 상태는 아래 전이
  엔드포인트로만 진입할 수 있습니다. PUT 위반은 기존과 같이 409 `PLAN_LOCKED`.
- `confirmedAt`·`completedAt`은 전이 엔드포인트에서 **서버가 발급**합니다(응답 `PlanResponse`에
  `completedAt` 필드 추가 — 미완료면 null).
- 프론트 시연: 고정·중단은 체크리스트 패널의 계획 동작 바, 완료는 회고 저장 연동(마무리 시점 확인 창)·보관함 행 ✓ 버튼이 이 전이 엔드포인트를 호출합니다(E2E 캡처 —
  완료 전이 후 "완료됨" 배지·전면 잠금 상태: [plan-status-transition-e2e.png](images/plan-status-transition-e2e.png)).

### 11-1. POST /plans/{id}/confirm — 계획 고정 (DRAFT→CONFIRMED)

**본문 없는 POST**(`X-Session-Id` 선택). 성공 시 계획 전체(`PlanResponse`)를 돌려주고 변경
이력에 `PLAN_CONFIRMED`가 발행됩니다.

```json
// 응답 data (일부)
{ "id": 1, "status": "CONFIRMED", "confirmedAt": "2026-07-25T09:00:00Z", "completedAt": null }
```

오류: 404 `PLAN_NOT_FOUND`, 409 `INVALID_STATUS_TRANSITION`(DRAFT가 아닌 상태에서 호출)

### 11-2. POST /plans/{id}/complete — 계획 완료 (CONFIRMED→COMPLETED · 종결)

**본문 없는 POST**. 100% 달성이 조건은 아니며, 진행률은 이력 detail로 남습니다
(`PLAN_COMPLETED`, detail: `3/5 완료`). 성공 시 `completedAt`이 서버 발급됩니다.

오류: 404 `PLAN_NOT_FOUND`, 409 `INVALID_STATUS_TRANSITION`(DRAFT·종결 상태에서 호출)

### 11-3. POST /plans/{id}/cancel — 계획 중단 (DRAFT|CONFIRMED→CANCELLED · 종결)

**본문 없는 POST**. 이력에 `PLAN_CANCELLED`(detail: `"목표명" 중단`)가 발행됩니다. 시각 필드는
바꾸지 않습니다(중단 시각은 이력의 createdAt이 담당).

오류: 404 `PLAN_NOT_FOUND`, 409 `INVALID_STATUS_TRANSITION`(종결 상태에서 호출)

### 12. GET /plans/{id}/summary/weekly — 주간 완료율 요약 (v0.10.0)

계획을 **시작일(startDate) 기준 7일 버킷("N주차")**으로 묶어 주별 완료율을 내려주는 읽기 전용
엔드포인트입니다. 1주차 = `[startDate, startDate+6]`, 2주차 = `[+7, +13]` … 마지막 주는 endDate에서
잘린 부분 주입니다. 완료 개수 계산은 서버 소유(`plan.tasks` 기준, `PlanResponse.progress`와 같은
소스) — 프론트는 표시만 합니다. `rate`는 `total>0 ? round(done*100/total) : 0`.

```json
// 응답 data — 8일(07-16~07-23) 계획 예시: 1주차는 07-16~07-22, 2주차는 07-23 하루
{ "planId": 1, "startDate": "2026-07-16", "endDate": "2026-07-23",
  "totalDone": 2, "totalTotal": 3,
  "weeks": [
    { "index": 1, "startDate": "2026-07-16", "endDate": "2026-07-22", "done": 1, "total": 2, "rate": 50 },
    { "index": 2, "startDate": "2026-07-23", "endDate": "2026-07-23", "done": 1, "total": 1, "rate": 100 }
  ] }
```

startDate/endDate가 없으면(비정상) `weeks`는 빈 배열입니다. 없는 id면 404 `PLAN_NOT_FOUND`.

---

## 다음 계획 분량 추천 (`/api/v1/plans/{id}/recommendation`) — v0.13.0

완료했거나 3일 이상 실행한 계획에서, **서버가 수행 기록을 계산하고 규칙으로 다음 하루 분량을
결정**합니다(로드맵 4·5번). 분량 숫자는 서버 규칙이 소유하고 AI는 이유 설명·내용 생성만 합니다.
버튼 노출 여부는 `PlanResponse.recommendationEligible`(완료 또는 3일 이상 실행)로 서버가 내려줍니다.
세 엔드포인트 모두 소유자 격리 — 남의 계획은 404 `PLAN_NOT_FOUND`.

### 12-1. POST /plans/{id}/recommendation — 수행 기록 + 규칙 분량 + 이유

계산 + AI 이유 생성 + `WORKLOAD_RECOMMENDATION_VIEWED` 이력 기록을 유발하므로 POST입니다. 완료율은
미래 날짜를 제외한 관찰 창(`startDate ~ min(endDate, 오늘)`)에서만 계산하고, 회고는 실제 저장된 것만
집계합니다. **규칙**: 관찰 3일 미만이면 기존 유지 · 완료율 50% 미만 −1 · 완료율 85%+ 이면서 여유
회고 절반 이상 +1 · 벅찬 회고 절반 이상 + "분량 많음" 2회 이상이면 −1. 안전 범위 1~5개, 한 번에 ±1.

**합산 표본(v0.13.1)**: 클릭한 계획과 **같은 `goalName`의 최근 계획을 최대 3건** 합산해
완료율·관찰일수·회고를 집계합니다(`findAllByOwner` savedAt 내림차순, 타 owner·다른 목표 제외 —
소유자 격리 유지). `currentTasksPerDay`는 가장 최근(클릭한) 계획 기준이고, `observedDays`·
`completedCount`·`totalCount`·`hardCount`는 합산값입니다. 계획이 1건이면 v0.13.0과 동일합니다.
목표명은 자유 텍스트라 목표명을 바꾸면 합산 그룹이 갈라집니다(구조적 계보는 이후 릴리스 예정).

```json
// 응답 data
{ "sourcePlanId": 12, "currentTasksPerDay": 3, "recommendedTasksPerDay": 2,
  "observedDays": 6, "completedCount": 9, "totalCount": 18, "completionRate": 50,
  "hardCount": 3, "topReason": { "code": "TOO_MUCH_WORK", "label": "분량이 많았어요" },
  "insufficientHistory": false, "observedPlanCount": 2,
  "reason": "최근 2개 계획 기록을 보면 완료율이 50%였고 '분량이 많았어요' 회고가 이어져 하루 3→2개로 줄이면 꾸준히 이어가기 좋아요.",
  "aiReasonUsed": true }
```

`observedPlanCount`(v0.13.1)는 합산에 쓴 계획 수(1~3)입니다 — 2 이상이면 이유 문구에도 "최근 N개
계획 기록"이 붙습니다. `aiReasonUsed`가 `false`면 AI 미가용으로 서버 규칙 템플릿이 이유를 채운
것입니다(분량 숫자는 항상 서버 규칙 소유). `topReason`은 회고가 없으면 `null`.

### 12-2. POST /plans/{id}/recommendation/draft — 초안 생성 (미저장)

선택한 분량으로 초안을 생성해 **미리보기로만** 돌려줍니다(저장하지 않음). 목표·기간·수준은 서버가
원본 계획에서 승계합니다. AI가 실패(키 미설정·업스트림 오류·정확개수 불일치)하면 서버 템플릿
생성기로 폴백해 항상 초안을 반환합니다(`aiUsed: false`).

```json
// 요청 body
{ "selectedTasksPerDay": 2 }   // 1~5

// 응답 data
{ "sourcePlanId": 12, "goalName": "정보처리기사 실기", "duration": 7, "dailyHours": 2,
  "currentLevel": "기본 개념은 아는 수준", "startDate": "2026-07-22", "endDate": "2026-07-28",
  "tasksPerDay": 2, "aiUsed": true,
  "tasks": { "2026-07-22": [ { "id": "t-2026-07-22-0", "content": "…", "completed": false }, … ], … } }
```

### 12-3. POST /plans/{id}/recommendation/confirm — 승인 → 새 계획 저장

미리보기에서 승인한 `tasks`를 **새 계획으로 저장**합니다. 목표·수준은 서버가 원본에서 다시 승계하고
날짜·기간은 tasks에서 산출합니다. 소유자 한도(`PLAN_LIMIT_EXCEEDED`/`PLAN_STORE_FULL`)는 계획 저장과
동일하게 적용됩니다. 저장 시 새 계획 이력에 `WORKLOAD_RECOMMENDATION_ACCEPTED` 또는
`_OVERRIDDEN`(선택 ≠ 추천)과 `PLAN_CREATED_FROM_RECOMMENDATION`이 함께 남습니다. **원본 계획·회고는
변경되지 않습니다.**

```json
// 요청 body — tasks는 계획 저장과 같은 형식(@ValidPlanTasks)
{ "tasks": { "2026-07-22": [ { "id": "…", "content": "…", "completed": false } ], … },
  "selectedTasksPerDay": 2, "recommendedTasksPerDay": 2 }
```

응답은 저장된 계획의 `PlanResponse`(아래 계획 보관함 응답과 동일 형식).

---

## 오늘 Dashboard (`/api/v1/dashboard`)

### GET /dashboard/today — 오늘 화면 읽기 모델 (v0.19.0)

계획 목록, 오늘 날짜 작업, 오늘 회고를 프론트에서 여러 번 조합할 때 생기던 요청 수와 응답 시점 차이를
없애기 위한 읽기 전용 API다. 오늘 작업이 없는 계획은 `plans`에서 제외한다.

```json
// 응답 data
{ "date": "2026-08-14", "done": 2, "total": 5,
  "plans": [
    { "plan": { "id": 12, "goalName": "정보처리기사 실기", "status": "CONFIRMED",
                "progress": { "done": 4, "total": 10 } },
      "tasks": [ { "id": "t-2026-08-14-0", "content": "기출 1회", "completed": true } ],
      "done": 1, "total": 2,
      "reflection": null,
      "completionEligible": false }
  ] }
```

`reflection`은 오늘 회고가 없으면 `null`이다. `completionEligible`은 CONFIRMED 계획이 종료일에
도달했거나 전체 작업을 모두 마쳤는지 서버가 판정한 값이다.

## 하루 회고 (`/api/v1/plans/{planId}/reflections`)

### 13. PUT /plans/{planId}/reflections/{date} — 오늘 회고 저장 (업서트)

`X-Session-Id` 선택 헤더를 받습니다. 날짜는 KST 오늘만 허용, 계획·날짜당 1건.

```json
// 요청 — difficulty: EASY | NORMAL | HARD
// reason: AS_PLANNED | NOT_ENOUGH_TIME | TOO_MUCH_WORK | HARD_TO_FOCUS | HARDER_THAN_EXPECTED
// 선택지 코드+한글 라벨은 메타 API(16. GET /meta/reflection-options) 참고 — 소스오브트루스는 서버 enum
{ "difficulty": "NORMAL", "reason": "NOT_ENOUGH_TIME" }

// 응답 data — completedCount/totalCount는 서버가 plan.tasks의 오늘 항목에서 재계산한다
// (클라이언트가 보낸 수치를 믿지 않으므로 요청에 개수 필드가 없다)
{ "planId": 1, "date": "2026-07-19", "completedCount": 2, "totalCount": 3,
  "difficulty": "NORMAL", "reason": "NOT_ENOUGH_TIME",
  "createdAt": "2026-07-19T13:05:22.123456Z", "updatedAt": "2026-07-19T13:40:02.987654Z" }
```

오류: 400 `INVALID_INPUT`(선택지 오타), 400 `GUEST_ID_REQUIRED`/`GUEST_ID_INVALID`, 400 `REFLECTION_DATE_INVALID`, 400 `REFLECTION_DATE_NOT_TODAY`, 404 `PLAN_NOT_FOUND`(없는 id 또는 다른 소유자 — 회고는 계획 소유권 상속)

### 14. GET /plans/{planId}/reflections/{date} — 특정 날짜 회고 조회

응답 data는 12와 동일. 없으면 404 `REFLECTION_NOT_FOUND`.

### 15. GET /plans/{planId}/reflections — 회고 목록 (날짜 내림차순)

```json
[ { "planId": 1, "date": "2026-07-19", "completedCount": 2, "totalCount": 3,
    "difficulty": "NORMAL", "reason": "NOT_ENOUGH_TIME",
    "createdAt": "...", "updatedAt": "..." },
  { "planId": 1, "date": "2026-07-18", "completedCount": 3, "totalCount": 3,
    "difficulty": "EASY", "reason": "AS_PLANNED",
    "createdAt": "...", "updatedAt": "..." } ]
```

---

## 변경 이력 (`/api/v1/plans/{planId}/audit-events`) — 읽기 전용

이벤트는 서버가 변경 서비스 안에서 직접 발행하므로 쓰기 엔드포인트가 없습니다.

### 16. GET /plans/{planId}/audit-events — 이력 조회 (최신순)

```json
// type 7종: PLAN_CREATED | PLAN_UPDATED | PLAN_CONFIRMED |
//           TASK_COMPLETED | TASK_REOPENED | REFLECTION_SAVED | PLAN_DELETED
[ { "id": 5, "planId": 1, "type": "TASK_COMPLETED",
    "detail": "\"기출 1회분 풀기\" · 2026-07-19",
    "sessionId": "s-abc123", "createdAt": "2026-07-19T13:00:41.512345Z" },
  { "id": 4, "planId": 1, "type": "PLAN_CONFIRMED", "detail": null,
    "sessionId": "s-abc123", "createdAt": "2026-07-19T12:58:10.001234Z" },
  { "id": 1, "planId": 1, "type": "PLAN_CREATED", "detail": null,
    "sessionId": null, "createdAt": "2026-07-19T12:50:00.000001Z" } ]
```

> **응답 스키마는 v0.10.0과 동일**합니다(`AuditEventResponse`: `id`·`planId`·`type`·`detail`·`sessionId`·`createdAt`). v0.11.0에서 이벤트에 **소유자(`ownerId` = 게스트 ID)를 내부적으로 저장**하지만, 격리 필터링에만 쓰고 **응답 바디에는 노출하지 않습니다**.

detail의 실제 형식(type별):

| type | detail |
|---|---|
| `PLAN_CREATED` · `PLAN_CONFIRMED` | `null` |
| `TASK_COMPLETED` / `TASK_REOPENED` | `"항목 내용" · 날짜` |
| `PLAN_UPDATED` | `계획 내용 변경`, 이월 액션(11)이 발행한 경우 `미완료 N건을 <날짜>로 이동` |
| `REFLECTION_SAVED` | `2026-07-19 회고 저장 (2/3 완료)` |
| `PLAN_DELETED` | `"목표명" 삭제` |

동작 규칙:

- 모르는 planId·다른 소유자의 계획은 404가 아니라 **빈 목록** `[]` (존재 여부를 숨김). 이벤트에 소유자(게스트 ID)가 함께 기록되므로, **삭제된 계획의 `PLAN_DELETED` 이력도 소유자에게는 조회된다**("언제 삭제됐는가" 계약 유지). 다른 소유자에게는 삭제 후에도 빈 목록이다
- 한 번의 PUT에서 여러 이벤트가 발행될 수 있고(디바운스 배칭), 순서는 `PLAN_CONFIRMED` → `TASK_*`(날짜·항목 순) → `PLAN_UPDATED`
- 완전 동일(no-op) PUT은 이벤트를 발행하지 않는다
- 이월의 "미완료 N건 이동" detail은 carry-over 액션(11)이 직접 발행한다 — PUT diff에서의 이월 패턴 역감지는 제거됨(구형 클라이언트가 PUT으로 이월하면 일반 `계획 내용 변경`으로 기록)

---

## 메타 (`/api/v1/meta`) — 읽기 전용

프론트가 하드코딩하던 선택지·라벨의 소스오브트루스를 서버 enum으로 옮긴 조회 전용
엔드포인트입니다. 프론트는 마운트 시 한 번 받아 쓰고, 서버 미가용 시엔 자체 폴백 사본을 씁니다.

### 17. GET /meta/reflection-options — 회고 선택지 (코드+라벨)

```json
{ "difficulties": [ { "code": "EASY", "label": "여유로웠어요" },
                    { "code": "NORMAL", "label": "적당했어요" },
                    { "code": "HARD", "label": "벅찼어요" } ],
  "reasons": [ { "code": "AS_PLANNED", "label": "계획대로 진행됐어요" },
               { "code": "NOT_ENOUGH_TIME", "label": "시간이 부족했어요" },
               { "code": "TOO_MUCH_WORK", "label": "분량이 많았어요" },
               { "code": "HARD_TO_FOCUS", "label": "집중이 잘 안 됐어요" },
               { "code": "HARDER_THAN_EXPECTED", "label": "생각보다 어려웠어요" } ] }
```

### 18. GET /meta/audit-event-types — 이력 이벤트 종류 (코드+라벨)

```json
[ { "code": "PLAN_CREATED", "label": "계획 생성" },
  { "code": "PLAN_UPDATED", "label": "계획 수정" },
  { "code": "PLAN_CONFIRMED", "label": "계획 고정" },
  { "code": "PLAN_COMPLETED", "label": "계획 완료" },
  { "code": "PLAN_CANCELLED", "label": "계획 중단" },
  { "code": "TASK_COMPLETED", "label": "할 일 완료" },
  { "code": "TASK_REOPENED", "label": "완료 해제" },
  { "code": "REFLECTION_SAVED", "label": "회고 저장" },
  { "code": "PLAN_DELETED", "label": "계획 삭제" },
  { "code": "WORKLOAD_RECOMMENDATION_VIEWED", "label": "다음 분량 추천 조회" },
  { "code": "WORKLOAD_RECOMMENDATION_ACCEPTED", "label": "추천 분량 채택" },
  { "code": "WORKLOAD_RECOMMENDATION_OVERRIDDEN", "label": "추천 분량 변경" },
  { "code": "PLAN_CREATED_FROM_RECOMMENDATION", "label": "추천 기반 계획 생성" } ]
```

### 19. GET /meta/plan-statuses — 계획 상태 종류 (코드+라벨)

계획 상태 수명주기(`PlanStatus`)의 코드·라벨 소스오브트루스. 전이 규칙은 "계획 상태 수명주기"
섹션 참고.

```json
[ { "code": "DRAFT", "label": "초안" },
  { "code": "CONFIRMED", "label": "고정" },
  { "code": "COMPLETED", "label": "완료" },
  { "code": "CANCELLED", "label": "중단" } ]
```

---

## 타임스탬프 형식 참고

세 가지 형식이 혼재하므로 QA 시 주의합니다.

| 필드 | 형식 | 생성 주체 |
|---|---|---|
| `savedAt` (계획) | epoch millis 숫자 (`1784856600000`) | 서버 |
| `createdAt` · `confirmedAt` (계획) | 프론트가 보낸 ISO 문자열 그대로 왕복 (confirmedAt은 전이 엔드포인트 사용 시 서버 발급) | 프론트/서버 |
| `completedAt` (계획) | 서버 `Instant.now().toString()` — POST /plans/{id}/complete만 기록 | 서버 |
| `createdAt` · `updatedAt` (회고·이력) | 서버 `Instant.now().toString()` (`2026-07-19T13:05:22.123456Z`) | 서버 |
