# API 데이터 레퍼런스

모든 동작(엔드포인트)의 요청·응답 데이터를 JSON 예시로 정리한 문서입니다. **v0.8.0 기준**이며, 소스(컨트롤러·DTO)와 1:1로 대조해 작성했습니다. QA 시 curl 호출·네트워크 탭 확인의 기준 자료로 사용합니다.

- 베이스 경로: `/api/v1`
- 스펙 자동 문서: 서버 실행 후 Swagger UI(`/swagger-ui/index.html`)에서도 확인 가능
- 관련 문서: [QA_CHECKLIST.md](QA_CHECKLIST.md) · [BACKEND_MIGRATION.md](BACKEND_MIGRATION.md)

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
| `PLAN_LIMIT_EXCEEDED` | 400 | 계획 보관함 개수 초과 |
| `REFLECTION_DATE_INVALID` | 400 | 회고 날짜가 YYYY-MM-DD가 아님 |
| `REFLECTION_DATE_NOT_TODAY` | 400 | 회고 날짜가 KST 오늘이 아님 |
| `PLAN_NOT_FOUND` | 404 | 계획 단건 조회·수정·삭제·회고 저장 시 없는 id |
| `REFLECTION_NOT_FOUND` | 404 | 해당 날짜 회고 없음 |
| `PLAN_LOCKED` | 409 | CONFIRMED 계획에 완료 토글 외 변경 (v0.8.0) |
| `AI_UPSTREAM_ERROR` | 502 | OpenRouter 호출 실패 |
| `AI_RESPONSE_INVALID` | 502 | AI 응답 해석·정규화 불가 |
| `INTERNAL_ERROR` | 500 | 그 외 서버 오류 |

---

## AI (`/api/v1/ai`)

### 1. GET /ai/health — AI 연결 상태 점검

```json
// 응답 data (정상 / 실패)
{ "connected": true }
{ "connected": false, "reason": "API Key 미설정" }
```

### 2. POST /ai/drafts — 계획 초안 생성

```json
// 요청 — duration 1~14, dailyHours 1~24는 서버가 강제.
// refinementPrompt + previousTasks는 재수정 요청일 때만 함께 보낸다.
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

// 응답 data — patch는 변경된 날짜만 담고, 값이 null인 날짜는 삭제를 뜻한다.
// planUpdated=false(단순 답변)면 patch 필드 자체가 생략된다.
{ "reply": "기간을 3일 연장해 마지막에 복습일을 넣었어요.",
  "planUpdated": true,
  "patch": { "2026-07-26": ["오답 복습"], "2026-07-27": ["모의시험"], "2026-07-19": null } }
```

### 5. POST /ai/chats/stream — 자유 대화 (SSE)

요청은 4와 동일. 산문은 token 이벤트로 흘러오고, 계획 변경분은 스트림 끝에 plan 이벤트 한 번.

```json
{ "type": "token", "t": "기간을 3일 " }
{ "type": "plan", "patch": { "2026-07-26": ["오답 복습"] } }
{ "type": "done" }
{ "type": "error", "m": "AI 응답 스트리밍 중 오류가 발생했습니다." }
```

---

## 계획 보관함 (`/api/v1/plans`)

변이 메서드(POST/PUT/DELETE)는 선택 헤더 `X-Session-Id: s-abc123`을 받아 변경 이력에 세션을 귀속시킵니다. 없으면(구형 클라이언트·curl) 이력에 null로 기록됩니다. 읽기(GET)는 이력을 남기지 않으므로 헤더를 받지 않습니다.

### 6. POST /plans — 계획 보관

```json
// 요청 — goalName·currentLevel: 공백 제외 2자 이상 / duration: 1~365 / dailyHours: 1~24
// tasks(v0.8.0 형식 검증): 키는 YYYY-MM-DD, 값은 배열, 항목은 {id, content, completed?}
// status: DRAFT|CONFIRMED만 허용, 생략(null) 시 DRAFT. POST로 CONFIRMED 직접 생성은 허용.
// [날짜 규칙 — v0.10.0] startDate·duration은 서버가 tasks 날짜 키에서 산출한다(요청 값은
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
  "progress": { "done": 0, "total": 1 } }
```

오류: 400 `INVALID_INPUT`+fieldErrors, 400 `PLAN_LIMIT_EXCEEDED`

### 7. GET /plans — 목록 조회 (최근 저장순)

```json
// 응답 data — PlanResponse 배열. 진행률은 서버 계산 progress 필드를 쓴다
// (추후 목록 API가 tasks 전체를 안 내려도 되는 기반).
[ { "id": 2, "goalName": "...", "tasks": { }, "progress": { "done": 1, "total": 4 }, "savedAt": 1784860000000 },
  { "id": 1, "goalName": "...", "tasks": { }, "progress": { "done": 0, "total": 1 }, "savedAt": 1784856600000 } ]
```

### 8. GET /plans/{id} — 단건 조회

응답 data는 6의 PlanResponse와 동일. 없는 id면 404 `PLAN_NOT_FOUND`.

### 9. PUT /plans/{id} — 계획 수정

요청·응답 본문은 6과 동일(POST·PUT 공용 DTO). 날짜 규칙(6 참고)도 동일하게 적용되며,
`startDate`는 **생성 시 산출된 뒤 불변**이라 수정 요청의 startDate 값은 무시된다(duration은 매
수정마다 `[startDate, endDate]`로 재산출). v0.8.0 서버 가드:

- CONFIRMED 계획은 **completed 토글과 완전 동일(no-op) PUT만 허용**
- 그 외 변경(goalName·duration·항목 내용/구조·DRAFT 롤백·confirmedAt 변경)은 409

```json
// 409 응답
{ "success": false, "data": null,
  "error": { "code": "PLAN_LOCKED",
             "message": "고정(CONFIRMED)된 계획은 완료 체크 외에는 수정할 수 없습니다.",
             "fieldErrors": null } }
```

그 외 오류: 400 `INVALID_INPUT`, 404 `PLAN_NOT_FOUND`

### 10. DELETE /plans/{id} — 계획 삭제

CONFIRMED여도 삭제는 허용합니다(잠긴 계획의 탈출구). 변경 이력은 지우지 않습니다.

```json
{ "success": true, "data": null, "error": null }
```

없는 id면 404 `PLAN_NOT_FOUND`.

### 11. POST /plans/{id}/carry-over — 미완료 이월

**본문 없는 POST**(`X-Session-Id` 선택 헤더만). 이월 규칙은 서버 소유입니다: 오늘(KST)의
미완료 항목을 내일로 옮기고(항목 ID 보존), 내일이 계획 기간 밖이면 endDate·duration을 하루
연장합니다. 예전엔 프론트가 계산해 PUT으로 보냈지만, 연산 소유권이 서버로 이관됐습니다.

```json
// 응답 data — movedCount 0은 "옮길 미완료 없음"의 정상 no-op(계획 불변, 이력 없음)
{ "movedCount": 2, "targetDate": "2026-07-21",
  "plan": { "id": 1, "goalName": "...", "tasks": { }, "endDate": "2026-07-21",
            "progress": { "done": 1, "total": 3 }, "savedAt": 1784860000000 } }
```

이동이 있으면 변경 이력에 `PLAN_UPDATED`(detail: `미완료 2건을 2026-07-21로 이동`)가 발행됩니다.

오류: 404 `PLAN_NOT_FOUND`, 409 `PLAN_LOCKED`(CONFIRMED 계획 — 이월은 구조 변경)

---

## 하루 회고 (`/api/v1/plans/{planId}/reflections`)

### 12. PUT /plans/{planId}/reflections/{date} — 오늘 회고 저장 (업서트)

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

오류: 400 `INVALID_INPUT`(선택지 오타), 400 `REFLECTION_DATE_INVALID`, 400 `REFLECTION_DATE_NOT_TODAY`, 404 `PLAN_NOT_FOUND`

### 13. GET /plans/{planId}/reflections/{date} — 특정 날짜 회고 조회

응답 data는 12와 동일. 없으면 404 `REFLECTION_NOT_FOUND`.

### 14. GET /plans/{planId}/reflections — 회고 목록 (날짜 내림차순)

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

### 15. GET /plans/{planId}/audit-events — 이력 조회 (최신순)

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

detail의 실제 형식(type별):

| type | detail |
|---|---|
| `PLAN_CREATED` · `PLAN_CONFIRMED` | `null` |
| `TASK_COMPLETED` / `TASK_REOPENED` | `"항목 내용" · 날짜` |
| `PLAN_UPDATED` | `계획 내용 변경`, 이월 액션(11)이 발행한 경우 `미완료 N건을 <날짜>로 이동` |
| `REFLECTION_SAVED` | `2026-07-19 회고 저장 (2/3 완료)` |
| `PLAN_DELETED` | `"목표명" 삭제` |

동작 규칙:

- 모르는 planId는 404가 아니라 **빈 목록** `[]` — 삭제된 계획의 `PLAN_DELETED` 이력도 조회할 수 있어야 하므로 계획 존재를 검증하지 않는다
- 한 번의 PUT에서 여러 이벤트가 발행될 수 있고(디바운스 배칭), 순서는 `PLAN_CONFIRMED` → `TASK_*`(날짜·항목 순) → `PLAN_UPDATED`
- 완전 동일(no-op) PUT은 이벤트를 발행하지 않는다
- 이월의 "미완료 N건 이동" detail은 carry-over 액션(11)이 직접 발행한다 — PUT diff에서의 이월 패턴 역감지는 제거됨(구형 클라이언트가 PUT으로 이월하면 일반 `계획 내용 변경`으로 기록)

---

## 메타 (`/api/v1/meta`) — 읽기 전용

프론트가 하드코딩하던 선택지·라벨의 소스오브트루스를 서버 enum으로 옮긴 조회 전용
엔드포인트입니다. 프론트는 마운트 시 한 번 받아 쓰고, 서버 미가용 시엔 자체 폴백 사본을 씁니다.

### 16. GET /meta/reflection-options — 회고 선택지 (코드+라벨)

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

### 17. GET /meta/audit-event-types — 이력 이벤트 종류 (코드+라벨)

```json
[ { "code": "PLAN_CREATED", "label": "계획 생성" },
  { "code": "PLAN_UPDATED", "label": "계획 수정" },
  { "code": "PLAN_CONFIRMED", "label": "계획 고정" },
  { "code": "TASK_COMPLETED", "label": "할 일 완료" },
  { "code": "TASK_REOPENED", "label": "완료 해제" },
  { "code": "REFLECTION_SAVED", "label": "회고 저장" },
  { "code": "PLAN_DELETED", "label": "계획 삭제" } ]
```

---

## 타임스탬프 형식 참고

세 가지 형식이 혼재하므로 QA 시 주의합니다.

| 필드 | 형식 | 생성 주체 |
|---|---|---|
| `savedAt` (계획) | epoch millis 숫자 (`1784856600000`) | 서버 |
| `createdAt` · `confirmedAt` (계획) | 프론트가 보낸 ISO 문자열 그대로 왕복 | 프론트 |
| `createdAt` · `updatedAt` (회고·이력) | 서버 `Instant.now().toString()` (`2026-07-19T13:05:22.123456Z`) | 서버 |
