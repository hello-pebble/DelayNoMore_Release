# 프론트 → 백엔드 로직 이관 현황

프론트엔드가 떠안고 있던 비즈니스 규칙을 백엔드로 옮기는 작업의 기록입니다.
원칙: **규칙의 소유권은 서버**, 프론트는 UX(빠른 피드백·낙관적 UI)만 담당합니다.

## 이관 완료

### PR #35 (2026-07)

| # | 항목 | 서버 구현 | 프론트에 남은 것 |
|---|---|---|---|
| 1 | **고정(CONFIRMED) 계획 수정 차단** | `PlanService.update()` 가드 — 완료(completed) 토글·no-op PUT만 허용, 구조 변경·DRAFT 롤백·confirmedAt 변경은 **409 `PLAN_LOCKED`**. 가드는 `PlanRepository.update`의 computeIfPresent 람다 안(키 단위 원자 구간)에서 실행해 레이스 차단. 판정 기준은 변경 이력과 `PlanTaskDiff`로 공유 | 잠금 UX(수정 요청 사전 차단 말풍선, 버튼 숨김)는 유지 — 불필요한 AI 호출을 막는 사용자 경험용이고, 강제는 서버가 한다 |
| 2 | **tasks 구조·status 검증** | `@ValidPlanTasks`(날짜 키 YYYY-MM-DD, 항목 `{id, content, completed?}`) + status `@Pattern(DRAFT\|CONFIRMED)` → 400 + fieldErrors. 내용은 원본 그대로 보관(정규화·변조 없음) | 화면 렌더링의 방어적 파싱만(서버 검증과 무관한 표시 안전장치) |
| 3 | **AI 초안 응답의 날짜 키 보장** | `AiResponseParser.normalizeDraftPlan` — LLM이 계약을 어긴 출력(최상위 배열, "Day N" 키, `{plan:[...]}` 래퍼)을 시작일부터 위치 기반으로 실제 날짜에 매핑. 실패 시 `AI_RESPONSE_INVALID`(프론트는 mock 폴백) | 문자열 → `{id, content, completed}` 객체 변환만(화면 모델 조립) — 스키마 흡수/날짜 합성 로직은 제거됨 |

### 처음부터 서버 소유였던 것 (이관 불필요 확인)

- LLM 프롬프트 조립 전체(`AiPromptBuilder` — 시스템 프롬프트 3종, targetDates, tasksPerDayRange, 이력 압축, 인젝션 방어)
- LLM 응답 해석(`AiResponseParser` — 코드펜스/JSON 추출, ===PLAN=== 분리, 비한국어 CJK 제거)
- OpenRouter API 키(서버 환경변수에만 존재)
- 초안 생성 입력 범위(`AiDraftRequest` — 기간 1~14일, 하루 1~24시간). 보관(`PlanSaveRequest`)의
  duration 1~365는 의도된 느슨함: 이월·"기간 +3일" 반복이 기간을 계속 연장할 수 있어서다
- 회고 완료 개수 재계산(`ReflectionService.countTodayTasks` — 클라이언트 수치를 믿지 않음)
- 변경 이력 diff 발행(`AuditEventService` — PUT 전체 교체에서 이벤트 종류 복원)

## 이관 예정 (우선순위순)

| # | 항목 | 현재 위치 | 이관 방안 |
|---|---|---|---|
| 1 | **진행률/완료율 계산** | `chat_coach.jsx` `getPlanProgress`·`todayGroups` | 서버의 `countTodayTasks` 재사용해 `PlanResponse`에 progress 필드 추가 — 목록 API가 tasks 전체를 안 내려도 되게 됨 |
| 2 | **미완료 이월(carry-over) 연산** | `chat_coach.jsx` `carryOverTasks` (서버는 결과를 `detectCarryOver`로 역감지) | `POST /plans/{id}/carry-over` 도메인 액션 엔드포인트 — 서버가 수행하면 감지 로직 자체가 불필요 |
| 3 | **회고/이력 enum 소스오브트루스** | `chat_coach.jsx` `DIFFICULTY_OPTIONS`·`REASON_OPTIONS`·`AUDIT_EVENT_LABELS` 하드코딩 | 메타 API(예: `GET /api/v1/meta/reflection-options`)로 코드+라벨 제공 |
| 4 | **startDate/endDate 산출·검증** | `ai_engine.js` `getFormattedDate` 기반 계산, 서버는 무검증 왕복 | 계획 생성/수정 시 서버가 산출·검증(duration과 tasks 날짜 개수 일관성 포함) |
| 5 | **AI 초안의 서버 측 저장 연결** | 초안 파싱(서버) 후 프론트가 별도 POST /plans | draft 완료 시 서버가 바로 보관(옵션) — 스트리밍 UX와 조율 필요 |
| 6 | **LLM patch 병합** | `ai_engine.js` `applyPlanPatch`·`draftWithPatch` | 서버가 병합 후 정규화된 전체 계획 반환 — 토큰/페이로드 트레이드오프 검토 |
| 7 | **mock 폴백 계획 생성기** | `ai_engine.js` `generateMockChecklistDraft` 등(서버 프롬프트 규칙 재구현) | "AI 미가용 시 서버가 템플릿 초안 생성"으로 이관 — 단, 백엔드 자체가 죽었을 때의 폴백이라는 존재 이유가 있어 프론트 폴백 유지 여부는 별도 결정 |

## 이관하지 않는 것 (프론트 소유가 자연스러움)

- 로컬 날짜 유틸(`date_utils.js`) — 사용자 타임존 의존 표시
- 세션 식별자(`session_id.js`)·마지막 본 계획 포인터(localStorage)
- 600ms 디바운스 동기화·낙관적 UI·실패 롤백(`chat_coach.jsx`)
- 슬롯필링 대화 흐름·`isPlanModificationRequest` 키워드 휴리스틱(사전 차단 UX — 최종 강제는 서버 가드)
- 슬롯 입력 검증(`parseUserMessage`) — 서버 규칙(1~14일 등)의 UX 힌트 사본. 규칙 소유는 서버

## 선결 과제

이관을 더 확대하기 전에 필요한 것 (ROADMAP 항목 6과 연동):

1. **인메모리 → DB 전환** — 리포지토리 시그니처는 이미 DB 관례(save/findAll/findById/update/deleteById)로 준비됨
2. **사용자 인증/격리** — 현재 전 방문자 공용 저장소. "누가 고정했나"가 생겨야 소유권 기반 가드로 발전 가능
