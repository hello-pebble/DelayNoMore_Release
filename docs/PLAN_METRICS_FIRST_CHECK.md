# 지표 A·B 계측 — 현재 상황과 수정 계획

작성일: 2026-11-10 · 기준: main v0.36.0 → **v0.37.0** (구현 반영됨)

상태: 구현 완료(이 문서의 §2·§3대로). 기준선 수집은 배포 후 시작한다.

## 0. 왜 지금 하나

`docs/MEETING_character_dock.md` §6은 캐릭터 독의 성공 판정을 세 지표로 고정했고,
"현재 값이 계측되지 않았다면 **지표 A 계측을 1단계보다 먼저** 넣는다"고 못 박았다.
독 1단계를 시작하기 전에 지표 A·B의 **기준선**을 서버 데이터로 뽑을 수 있어야 한다.

| 지표 | 정의(회의록 §6) | 판정 |
| :-- | :-- | :-- |
| A | 앱 오픈 → 그 방문에서 첫 완료 체크 도달률 | +10%p 이상 |
| B | 계획 생성 후 24h 내 1건 이상 체크 비율 | +10%p 이상 |
| C | 7일 재방문율 | 하락 시 롤백 |

## 1. 현재 상황 vs 변경 후 (코드에서 확인한 사실 기준)

| 항목 | 현재 | 변경 후 |
| :-- | :-- | :-- |
| **지표 B** | `audit_events`에 `PLAN_CREATED`·`TASK_COMPLETED`가 `plan_id`·`created_at`(UTC ISO 문자열)과 함께 이미 기록됨. 아무도 집계하지 않음 | **코드 변경 없음.** `OPERATIONS.md`에 SQL 한 개 추가(`plan_id`로 조인, 24h 창, `session_id='slack'` 제외 옵션) |
| **지표 A — "앱 오픈" 신호** | 없음. 읽기 요청은 설계상 기록하지 않음(`AuditEvent.java:6`). `GET /plans`가 마운트마다 1회 호출되지만 새로고침·생성 후 재조회와 구분 불가. Actuator·접근 로그·Micrometer 없음 | **`POST /api/v1/app-opens`** 신설(본문 없음, `@Owner`). `ChatCoach` 마운트 시 1회 fire-and-forget. `GET`에 부작용을 두지 않고, 재조회와 섞이지 않음 |
| **지표 A — "그 방문"의 경계** | `X-Session-Id`는 `localStorage`에 영구 저장된 **브라우저 ID**(방문 구분 불가), GET에는 안 실림 | 새 **`X-Visit-Id`**: 페이지 로드마다 생성(모듈 상수 `crypto.randomUUID()`), 모든 요청에 실림. 기존 `X-Session-Id` 의미는 그대로 |
| **지표 A — 체크와 방문 연결** | `TASK_COMPLETED` 행에 방문 식별자 없음 | `audit_events.visit_id TEXT` 컬럼 추가, `AuditEventService.append`가 함께 저장. 기존 행은 NULL(기준선은 배포 이후부터) |
| **저장소** | 운영 숫자는 `OPERATIONS.md`의 수작업 SQL + `docker logs | grep ai.usage` | 새 테이블 `app_opens(visit_id PK, owner_id, created_at)` — V16 마이그레이션 하나에 컬럼 추가와 함께. 로그 라인 방식은 로그 로테이션에 날아가므로 채택하지 않음 |
| **중복 방지** | — | `visit_id` PK + `ON CONFLICT DO NOTHING` — StrictMode 이중 마운트·재시도에 멱등 |
| **인메모리 프로필** | — | no-op 구현(`absorbGuest`와 같은 관례). 계측은 postgres 전용 |
| **지표 C** | 측정 불가 | `app_opens`의 `owner_id`·날짜로 SQL만으로 산출(보너스, 추가 코드 없음) |
| **운영자가 읽는 방법** | — | `OPERATIONS.md`에 A·B·C 쿼리 3개(KST 일 단위, `created_at::timestamptz AT TIME ZONE 'Asia/Seoul'`) |

### 채택하지 않은 대안
- `GET /plans`에 헤더 힌트로 오픈 기록 — 읽기 요청에 쓰기 부작용이 생기고 재조회와 구분을 프론트 힌트에 의존.
- `audit_events`에 `APP_OPENED` 타입 — `plan_id NOT NULL`이고 테이블 목적("계획 변경 이력")과 어긋남.
- `ai.usage`식 로그 라인 — 로그 로테이션 미설정 상태라 기준선이 사라질 수 있음.
- 분석 SDK — `PLAN_TODAY_HOME.md` §9와 같은 이유로 제외.

## 2. 변경 범위

| 파일 | 작업 |
| :-- | :-- |
| `backend/.../db/migration/V16__app_opens.sql` | `CREATE TABLE app_opens(visit_id TEXT PK, owner_id TEXT NOT NULL, created_at TEXT NOT NULL)` + 인덱스(owner_id, created_at) + `ALTER TABLE audit_events ADD COLUMN visit_id TEXT` + RLS(기존 관례) |
| `domain/plan/entity/AuditEvent.java`, `AuditEventService.java`, `JdbcAuditEventRepository.java`, `InMemoryAuditEventRepository.java` | `visitId` 필드·INSERT 컬럼 추가. 호출처는 `sessionId`와 같은 경로로 전달(`X-Visit-Id` 헤더 → 컨트롤러 → 서비스) |
| `domain/metrics/AppOpenController.java` · `AppOpenRepository`(+Jdbc/InMemory) | `POST /api/v1/app-opens` — 헤더 `X-Visit-Id` 필수(없으면 400), `@Owner`, 204 |
| `frontend/src/visit_id.js`(신규, 5줄) · `db_service.js` | 방문 ID 생성·모든 요청에 `X-Visit-Id` 첨부 |
| `frontend/src/components/chat_coach.jsx` | 마운트 1회 `postAppOpen()` (실패 무시) |
| `docs/OPERATIONS.md` | "지표 A·B·C 조회" 절 + 주간 루틴에 추가 |
| `docs/API_REFERENCE.md` | `POST /app-opens`, `X-Visit-Id` 헤더 |
| `docs/QA_CHECKLIST.md` | `F-47. 지표 계측 (v0.37.0)` — 오픈 1회 기록·StrictMode 중복 없음·체크에 visit_id 기록·SQL 결과 |
| `README.md`·`CHANGELOG.md`·버전 3곳 | 릴리스 규칙대로 |

새 의존성 없음. 개인정보 추가 없음(기존 owner ID + 무작위 방문 ID만). 프롬프트 무변경.

## 3. 구현 순서
1. V16 마이그레이션 + `audit_events.visit_id` 저장 경로(헤더 → 서비스 → INSERT). 기존 테스트 통과 확인.
2. `POST /app-opens` + 저장소(Jdbc·InMemory). MockMvc 테스트: 204, 같은 visit_id 2회 → 1행, 헤더 없음 → 400.
3. 프론트 `X-Visit-Id` 첨부 + 마운트 1회 POST. lint·build.
4. `OPERATIONS.md` 쿼리 3개를 로컬 postgres에서 실제 실행해 결과 형식 확인.
5. 릴리스 문서. 배포 후 **최소 7일 기준선 수집** → 그 뒤 캐릭터 독 1단계 착수.

## 4. 검증
- `./gradlew test` 전체(감사 이력 INSERT 시그니처 변경이 다른 테스트를 깨지 않는지).
- postgres 프로필 로컬: 앱 오픈 → `app_opens` 1행, 새로고침 → 새 visit_id 1행, 체크 → `audit_events.visit_id`가 같은 값. StrictMode에서 2행이 아닌지.
- 지표 A 쿼리가 "오픈 수 / 그중 체크 도달 수 / 비율"을 KST 일 단위로 내는지. B 쿼리가 기존 데이터(배포 전)에서도 값을 내는지.
- 슬랙 체크(`session_id='slack'`, visit_id NULL)가 A의 분자에 섞이지 않는지.

## 5. 한계
- 기준선은 배포 시점 이후부터만 쌓인다(과거 체크에는 visit_id가 없다).
- 탭을 닫지 않고 자정을 넘기면 한 방문이 두 날에 걸친다 — 오픈 시각 기준 하루로 귀속한다.
- 게스트 → 로그인 흡수 시 `app_opens.owner_id`는 re-key하지 않는다(A는 visit_id 기준이라 영향 없음, C는 전환 당일 1명이 2명으로 보일 수 있음).
