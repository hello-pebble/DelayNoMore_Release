# 프론트 → 백엔드 로직 이관 현황

프론트엔드가 떠안고 있던 비즈니스 규칙을 백엔드로 옮기는 작업의 기록입니다.
원칙: **규칙의 소유권은 서버**, 프론트는 UX(빠른 피드백·낙관적 UI)만 담당합니다.

## 이관 완료

### LLM 채팅 patch 병합 (2026-07)

| # | 항목 | 서버 구현 | 프론트에 남은 것 |
|---|---|---|---|
| 1 | **자유 대화 patch 병합** | `ChatPatchMerger`(신규, ai 도메인 stateless 유틸) — LLM이 여전히 변경된 날짜만 담은 sparse patch를 내면(출력 토큰 절약 유지), 서버가 현재 계획(`AiChatRequest.tasks`)에 병합해 정규화된 전체 tasks(`{id, content, completed}` 객체)를 응답에 담는다. `AiChatResponse.patch` → `tasks`로 계약 변경(clean cut — 단일 배포 데모라 프론트·백엔드 항상 함께 배포). SSE `plan` 이벤트도 `patch`→`tasks`. 완료 체크 보존은 **날짜+content 매칭**(예전 프론트 `carryOverCompleted`와 같은 semantics). `AiResponseParser`는 파싱만 담당(`toChatResponse`→`parseChat`으로 분리), 병합은 현재 계획을 아는 `AiService`가 소유 | 서버가 반환한 전체 tasks를 채택만 한다(`draftWithTasks`) — draft의 duration/endDate 로컬 재계산은 즉시 표시 UX로 유지 |

### startDate/endDate/duration 산출·검증 (2026-07)

| # | 항목 | 서버 구현 | 프론트에 남은 것 |
|---|---|---|---|
| 1 | **startDate/duration 산출** | `PlanService`가 `startDate`를 tasks의 **최초 날짜 키**로 산출(생성 시 1회 → 이후 불변, carry-over가 오늘 키를 지워도 보존)하고 `duration`을 `[startDate, endDate]` **span**으로 산출한다. 클라이언트가 보낸 startDate/duration은 무시. carry-over의 기존 `duration+1`도 같은 규칙으로 일원화. 공유 로직은 `PlanDates`(support) 유틸(`isIsoDate`/`minTaskKey`/`maxTaskKey`/`spanDays`) — tasks 키 검증의 ISO 파서도 여기로 통합 | 라이브 draft(`ai_engine.js`)의 startDate/endDate/duration 로컬 계산 — 서버 왕복 전 즉시 표시 UX. 보관 후 표시는 `fromPlanResponse`가 서버 산출값 채택 |
| 2 | **endDate 검증** | `@ValidPlanDates`(record TYPE 레벨 교차필드 제약) — endDate가 ISO(YYYY-MM-DD)이고 tasks의 마지막 날짜 키 이상인지 검증, 위반은 400 + `fieldErrors.endDate`. endDate는 계획의 지평선이라 마지막 할 일 날짜보다 뒤여도 유효(상한 아닌 하한만 검증) — carry-over만 연장 | 배포 스큐 안전을 위해 `toPlanPayload`가 endDate를 계속 전송(신클라이언트→구서버 호환). 서버가 형식·범위를 강제 |

### 진행률·이월·enum 메타 (2026-07)

| # | 항목 | 서버 구현 | 프론트에 남은 것 |
|---|---|---|---|
| 1 | **진행률/완료율 계산** | `Plan.countAllTasks()`(회고의 `countTasksOn`과 같은 도메인 메서드) → `PlanResponse.progress {done, total}`. 목록·단건 응답 모두 포함 | 라이브 draft(`draftChecklist`)의 진행률 로컬 계산(`getPlanProgress`) — 600ms 디바운스 동기화 전의 즉시 표시 UX. 서버 스냅샷 표시는 progress 필드 사용 |
| 2 | **미완료 이월(carry-over) 연산** | `POST /plans/{id}/carry-over` 도메인 액션 — 오늘(KST) 미완료를 내일로 이동(ID 보존), 기간 밖이면 endDate/duration 하루 연장, CONFIRMED는 409 `PLAN_LOCKED`. 연산·가드는 `PlanRepository.mutate`의 키 단위 원자 구간에서 실행. 이력("미완료 N건을 D로 이동")은 액션이 직접 발행 — PUT diff의 이월 역감지(`detectCarryOver`)는 제거됨 | 확인창용 미완료 카운트(`countTodayIncomplete`)와 응답 반영만. 이월 후 `lastSyncedRef` 선갱신으로 디바운스 PUT과의 경합 차단 |
| 3 | **회고/이력 enum 소스오브트루스** | `ReflectionDifficulty`·`ReflectionReason`·`AuditEventType` enum(코드+한글 라벨) + `GET /meta/reflection-options`·`GET /meta/audit-event-types`. 저장 검증(`@Pattern`)과 enum의 일치는 드리프트 가드 테스트가 보장 | `DEFAULT_*` 폴백 사본 — 백엔드 미가용 시에도 회고/이력 화면 유지(마운트 시 메타 API로 교체) |

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
- 회고 완료 개수 재계산(`Plan.countTasksOn` — 클라이언트 수치를 믿지 않음. 진행률 이관 때 `ReflectionService`의 private 메서드에서 엔티티 도메인 메서드로 추출됨)
- 변경 이력 diff 발행(`AuditEventService` — PUT 전체 교체에서 이벤트 종류 복원. 이월 detail은 이관 후 carry-over 액션이 직접 발행)

## 이관 보류 (선결 과제 이후 재검토)

| # | 항목 | 현재 위치 | 보류 사유 |
|---|---|---|---|
| 1 | **AI 초안의 서버 측 저장 연결** | 초안 파싱(서버) 후 프론트가 별도 POST /plans(`chat_coach.jsx` `archiveNewPlan`) | ① 초안 SSE(`/ai/drafts/stream`)의 종료 이벤트가 `{type:"done"}`뿐이라 서버가 저장해도 planId를 돌려줄 창구가 없다 — `{type:"saved","planId":...}` 신규 계약이 필요하고 비스트리밍 `/ai/drafts`도 저장 응답으로 바꿔야 일관. ② **mock 폴백 초안도 같은 `archiveNewPlan`으로 저장**되는데, 백엔드가 죽었을 때는 서버가 대신 저장할 수 없으므로 클라 저장을 없앨 수 없다 → 서버·클라 **이중 저장 경로**가 생긴다. ③ `archiveNewPlan`이 쥔 `PLAN_LIMIT_EXCEEDED` 안내·서버 복구 후 재시도(`archivePendingRef`)·no-op PUT 억제(`lastSyncedRef`) 조율도 함께 옮겨야 한다. "누가 저장했나"(소유권)가 자동 저장의 의미를 바꾸므로 아래 **선결 과제(인증·DB) 이후** 재검토한다 |

## 이관하지 않는 것 (프론트 소유가 자연스러움)

- **mock 폴백 계획 생성기**(`ai_engine.js` `generateMockChecklistDraft`·`extendMockChecklistDays`·`mockChatWithCoach`) — `OPENROUTER_API_KEY` 미설정/백엔드 미가용 시에도 데모 흐름이 끊기지 않게 하는 **프론트 최후 폴백**(FEATURES.md #4가 보장). 백엔드 자체가 죽으면 서버가 대신 생성해줄 수 없으므로 프론트에 남는 것이 자연스럽다. "AI는 죽었지만 백엔드는 살아있는"(502·키 미설정) 경우만 떼어 서버 템플릿 생성(`AiPromptBuilder.tasksPerDayRange`/`targetDates`로 규칙 일원화)으로 옮길 수는 있으나, 그때도 백엔드 다운용 프론트 생성기는 그대로 필요해 템플릿 로직이 양쪽에 남는다 — 중복 대비 이득이 적어 현행 유지
- 로컬 날짜 유틸(`date_utils.js`) — 사용자 타임존 의존 표시
- 세션 식별자(`session_id.js`)·마지막 본 계획 포인터(localStorage)
- 600ms 디바운스 동기화·낙관적 UI·실패 롤백(`chat_coach.jsx`)
- 슬롯필링 대화 흐름·`isPlanModificationRequest` 키워드 휴리스틱(사전 차단 UX — 최종 강제는 서버 가드)
- 슬롯 입력 검증(`parseUserMessage`) — 서버 규칙(1~14일 등)의 UX 힌트 사본. 규칙 소유는 서버

## 선결 과제

이관을 더 확대하기 전에 필요한 것 (ROADMAP 항목 6과 연동):

1. **인메모리 → DB 전환** — 리포지토리 시그니처는 이미 DB 관례(save/findAll/findById/update/deleteById)로 준비됨
2. **사용자 인증/격리** — 현재 전 방문자 공용 저장소. "누가 고정했나"가 생겨야 소유권 기반 가드로 발전 가능
