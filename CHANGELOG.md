# 변경 이력 (Changelog)

이 프로젝트의 모든 주요 변경사항을 이 파일에 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르며,
버전 규칙은 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 따릅니다.

- **MAJOR**: 구조가 크게 바뀌는 변경 (예: 인증 도입, DB 추가)
- **MINOR**: 하위 호환되는 기능 추가 (예: 목표 저장, 팀 공유)
- **PATCH**: 하위 호환되는 버그/디자인 수정

## [Unreleased]

### Added
- **계획 자동 저장** — 계획이 생성·수정되거나 항목 완료를 토글할 때마다 localStorage에 자동
  반영되어, 새로고침해도 **항목별 완료 여부와 진행률까지** 그대로 복원된다. (기존에는 수동
  "계획 저장" 버튼을 다시 누르지 않으면 완료 체크가 새로고침 시 유실됐다.)
- "처음부터 다시 만들기"(전체 초기화)에 **실수 방지 확인 창** 추가 — 자동 저장 체제에서는
  초기화가 곧 저장분 삭제라 되돌릴 수 없기 때문.

### Changed
- 수동 **"계획 저장" 버튼 제거** — 자동 저장으로 대체. 체크리스트 하단 안내 문구에
  "계획과 완료 기록은 이 브라우저에 자동 저장됩니다"를 명시.
- **백엔드 코드 규칙(CONVENTIONS) 정렬 리팩토링** — 단일 `AiController`(약 1,000줄)에 몰려 있던
  로직을 레이어로 분리: `domain/ai/{controller,service,client,dto}` + `global/{response,error,config}`.
  - Controller는 `@Valid` 검증·Service 호출만, 비즈니스 로직(프롬프트 조립·응답 정제·SSE 릴레이)은
    Service 계층(`AiService`/`AiPromptBuilder`/`AiResponseParser`)으로, OpenRouter HTTP 호출은
    `OpenRouterClient`로 이동.
  - `Map<String, Object>` 수동 검증 → **요청 DTO + Bean Validation**(`@NotBlank`/`@Min`/`@Max`)으로 교체.
  - `RestTemplate` → **`RestClient`** 전환(Boot 4 규칙, `spring-boot-starter-restclient` 추가).
  - 예외 처리를 `BusinessException(ErrorCode)` + `GlobalExceptionHandler` 한 곳으로 통일.
  - springdoc(Swagger) 도입 — 컨트롤러에 `@Tag`/`@Operation`, `/swagger-ui.html` 제공.
  - Service 단위 테스트 추가(`AiServiceTest`, `AiResponseParserTest`).
- **(호환성 깨짐) REST API 계약 변경** — URL 버저닝과 공통 응답 래퍼 도입. 프론트 호출부(`db_service.js`),
  `render.yaml` 헬스체크 경로, 배포 스크립트/문서도 함께 갱신.
  - 경로: `/api/ai/{health,draft,draft/stream,chat,chat/stream}` →
    `/api/v1/ai/{health,drafts,drafts/stream,chats,chats/stream}` (리소스 복수형).
  - JSON 응답을 `{ success, data, error }`(ApiResponse)로 래핑. 검증 실패는
    `error.fieldErrors`(필드 → 사유), 오류 분기는 `error.code`(ErrorCode)로 판별.
    SSE 스트림 이벤트 계약(`day`/`token`/`plan`/`done`/`error`)은 그대로 유지.

## [0.3.0] - 2026-07-16

실시간성·안정성·품질 강화 릴리스. v0.2.0의 대화형 계획 생성/조작 흐름 위에,
봇 답변과 초안 계획을 **실시간 스트리밍**으로 흘리고 토큰 사용량을 대폭 줄였으며, 서버 입력
검증·계획 범위 분배·한국어 출력 순도 같은 **품질/견고성**을 보강했다.

### Added
- 자유 대화 **실시간 토큰 스트리밍**(SSE) — 새 엔드포인트 `POST /api/ai/chat/stream`.
  봇의 산문 답변을 토큰이 도착하는 대로 흘려보내 실제 타이핑처럼 나타나게 하고,
  계획 변경분은 스트림 끝에서 한 번에 반영한다. 프론트는 `fetch` + `ReadableStream`으로
  SSE를 직접 파싱하며, 스트림 실패 시 비스트리밍(`/api/ai/chat`) → mock 순으로 폴백한다.
  - 응답은 "산문 reply → `===PLAN===` 구분자 → 계획 JSON" 형태로 분리해, JSON을 토큰 단위로
    흘리지 않고 사람이 읽는 텍스트만 스트리밍한다(깨진 JSON이 화면을 죽이는 문제를 구조적으로 제거).
- 초안 계획 **Day별 실시간 스트리밍** — 새 엔드포인트 `POST /api/ai/draft/stream`. 계획을
  "하루 = 한 줄(NDJSON)"로 생성하게 하고, 한 줄(=하루)이 완성될 때마다 `day` 이벤트로 흘려보내
  우측 체크리스트가 **Day1부터 하나씩** 채워진다("분석 중 → 통째로"가 아니라 실제 순차 생성).
  프론트는 도착한 Day까지의 부분 계획을 즉시 반영하며, 스트림 실패/0건이면 비스트리밍
  `/api/ai/draft` → mock 순으로 폴백한다. 태스크 문자열에도 CJK(한자/가나) 후처리 필터가 적용된다.

### Changed
- **토큰 사용량 절감** — 자유 대화 경로를 전면 개편.
  - 계획을 통째로 재전송하던 것을 **변경된 날짜만 담는 patch**로 바꿔 출력 토큰을 크게 줄였다
    (수정=해당 날짜만, 기간 연장=새 날짜만, 단축=제거할 날짜를 `null`로). 병합은 프론트에서 수행.
  - 모델에 주고받는 계획에서 `id`·`completed` 보일러플레이트를 제거하고 **"날짜 → 문자열 배열"**
    compact 형태로 통일(초안 생성 출력도 동일). 프론트가 `id`/상태를 복원한다.
  - 대화 이력 전송을 **최근 12턴 → 6턴**으로 축소.
  - 자유 대화 응답에 `max_tokens` 상한을 둬 폭주 생성 비용을 방어(추론이 꺼져 있어 정상 응답은 잘리지 않음).
- 비스트리밍 `/api/ai/chat` 응답 계약도 patch로 통일 — `{reply, planUpdated, patch}`.
- 프론트 기간 입력 상한을 **30일 → 14일**로 낮춰 서버 검증 규칙과 일치시켰다(불일치 시 mock으로
  새던 문제 예방).

### Fixed
- **서버측 입력 검증** — `/api/ai/draft`가 빈 목표·`0일`·`25시간` 같은 잘못된 입력도 `200 OK`로
  계획을 생성하던 문제. 이제 서버에서 `goalName`(공백 제외 2자+)·`duration`(정수 1~14)·
  `dailyHours`(정수 1~24)·`currentLevel`(2자+)을 검증하고, 위반 시 계획을 만들지 않고
  **`400 Bad Request` + 필드별 오류**(`{error, message, fields}`)를 반환한다. 프론트가 막아도 API가
  직접 호출될 수 있으므로 서버가 최종 방어선이 된다.
- **생성 계획의 범위 쏠림 완화** — 정보처리기사 실기처럼 여러 영역으로 구성된 목표에서 계획이 한
  소주제(예: 데이터베이스/SQL)로만 몰리던 문제. 초안 프롬프트에 "목표의 주요 영역을 먼저 식별하고
  일수에 비례해 전 영역에 고르게 분배(breadth-before-depth)"하도록 지침을 보강했다.
- **출력 한국어 순도 규칙 + 후처리 필터** — qwen 모델이 한국어 사이에 한자/중국어(限時·重點·陷阱 등)나
  불필요한 마크다운 기호(`_`,`*` 등)를 섞어 내던 문제. 초안·자유대화 프롬프트에 "순수 한국어만 출력,
  한자/비한국어 문자 및 군더더기 마크다운 기호 금지"를 명시했다. 프롬프트만으로는 가끔 새기 때문에
  **백엔드 후처리로 결정적으로 제거**한다 — 초안 계획 JSON·자유대화 patch의 태스크 문자열과 응답 산문,
  스트리밍 토큰에서 비한국어 CJK 문자(한자·히라가나·가타카나)를 걸러내고 그 자리에 생긴 공백을 정리한다
  (한글·영문·숫자·기호는 보존).

## [0.2.0] - 2026-07-14

UX 개선 릴리스. v0.1.0의 대화형 계획 생성 흐름 위에, 생성된 계획을 **직접 다루고**
(완료 체크·진행률), **내보내고**(복사·다운로드), **더 빠르게 조작**(질문 빠른 선택,
저장·기간연장·다시 만들기)할 수 있게 했다. 코드/배포 변경은 없고 프론트 UX와
백엔드 프롬프트 위주.

### Added
- 체크리스트 할 일 **완료 토글** — 클릭하면 체크/취소선으로 표시되고, 요약 헤더에
  완료/전체 진행률 바가 실시간으로 반영된다(세션 내 로컬 상태, 서버 저장 없음).
- 계획 **복사/다운로드** — 우측 패널 상단 버튼으로 계획을 텍스트로 클립보드 복사하거나
  `.txt` 파일로 다운로드. `navigator.clipboard`가 없는 비-HTTPS 배포 환경을 위해
  `execCommand` 레거시 폴백을 포함하고, 실패 시 "복사 실패"로 사용자에게 알린다.
- 슬롯필링 질문에 **빠른 선택 버튼** — 기간(3/5/7일), 하루 투자 시간(1/2/4/6시간),
  현재 수준(완전 초보/기본 개념은 아는 수준/실전 경험 있음)을 버튼 클릭으로 바로 전송.
  버튼은 하단 입력바 옆이 아니라 **질문 말풍선 바로 아래(대화 흐름 안)** 에 표시되어
  질문을 읽는 위치에서 바로 선택할 수 있다. 자유 입력도 계속 가능.
- 계획 생성 후 **빠른 동작 버튼** — "계획 저장"(localStorage에 보관, 새로고침해도 자동
  복원, 서버/DB 없음), "기간 +3일"(기존 자유 대화 파이프라인을 재사용해 기존 Day는
  그대로 두고 새 Day만 이어붙임), "처음부터 다시 만들기"(저장된 계획도 함께 삭제).
- 백엔드 `/api/ai/chat`이 "기간을 늘려줘/연장해줘" 같은 요청에 응답할 수 있도록
  프롬프트 보강 — 이런 요청에서는 예외적으로 기존 날짜는 그대로 두고, 마지막 날짜
  다음부터 이어지는 새 날짜 키를 추가(또는 단축 시 마지막 날짜 제거)하도록 허용.
  mock 폴백에도 동일 로직(`extendMockChecklistDays`) 추가.

## [0.1.0] - 2026-07-13

첫 데모 릴리스. 원본 [DelayNoMore](https://github.com/hello-pebble/DelayNoMore)의
"대화를 통해 투두리스트(하루 단위 실행 계획)를 생성하는" 핵심 흐름만 떼어낸 최소 배포판.
Oracle Cloud Always Free VM에 단일 컨테이너로 배포되어 동작 확인까지 완료한 버전이다.

### Added
- AI 코치와의 슬롯필링 대화 → 계획 초안(투두리스트) 생성/보완 흐름.
- 좌우 분할 화면: 왼쪽 = AI 코치 대화, 오른쪽 = 생성된 체크리스트 (모바일 폭에서는 상하 스택).
- 백엔드 AI 프록시(`/api/ai/draft`, `/api/ai/health`) — OpenRouter 키를 서버에만 보관.
- 대화 엔드포인트 `/api/ai/chat` — 초안 생성 이후 유저 메시지를 LLM이 직접 해석해
  **의도(계획 수정 / 질문·잡담 / 불명확)** 를 판단. 최근 대화 이력을 함께 전달해
  "반영 안됐는데?" 같은 맥락 의존 발화도 처리한다.
- `OPENROUTER_API_KEY` 미설정/백엔드 미가용 시 프론트의 템플릿 기반 mock 폴백.
- 단일 배포 구성: Spring Boot가 빌드된 프론트엔드 정적 파일과 `/api/*`를 함께 서빙,
  루트 `Dockerfile` 하나로 컨테이너 배포.
- 이미지 빌드/푸시 CI(`.github/workflows/image.yml`) — `main` 푸시/`v*` 태그마다
  Docker 이미지를 빌드해 `ghcr.io`에 push(latest·커밋 SHA·시맨틱 버전 태그).
- pull 방식 배포 스크립트(`deploy/oci-pull.sh`) — VM에서 빌드하지 않고 ghcr.io
  이미지를 받아 실행. 낮은 사양 VM(1GB Micro 등)이 빌드로 마비되는 문제를 근본 해결.
  런타임 JVM 힙 상한(`-XX:MaxRAMPercentage=50`)도 함께 건다.
- Oracle Cloud(OCI) Always Free 배포 가이드(`docs/DEPLOY_OCI.md`)와
  VM 빌드 방식 스크립트(`deploy/oci-setup.sh`).
- Render 배포용 블루프린트(`render.yaml`) — 루트 Dockerfile 기반 단일 웹 서비스.
- 기능 점검 체크리스트 문서(`docs/QA_CHECKLIST.md`) — 버전별 QA 항목.
- 배포 회고 및 파라미터 운영 노트(`docs/DEPLOY_RETROSPECTIVE.md`) — 속도(추론 모드 끔)·
  응답 스키마 매칭 진단/수정 내역과, 향후 AI 파라미터(모델·추론·temperature·개수 정책)를
  설정으로 외부화하는 제어 방향 정리.

### Changed
- 디자인을 심플하게 정리 — 커스텀 폰트·노트/그리드 배경·글래스모피즘·테마 토글 제거,
  시스템 폰트와 단순 색상만 사용(기능 동작 우선).
- 빌드 Java 버전을 17 → 21로 상향 (`build.gradle` toolchain, `Dockerfile` 이미지).
- Spring Boot 3.3.1 → 4.1.0 최신화. Jackson 3(`tools.jackson`) 이전에 따라
  `AiController`의 Jackson 사용부 마이그레이션(`asText` → `asString`).
- 서버 포트를 `${PORT:8080}`로 변경 — 배포 플랫폼(Render·Cloud Run·OCI 등)이
  주입하는 `PORT`를 사용하고, 로컬은 8080 기본값 유지.
- **LLM 대화 고도화**: 초안 생성 후 채팅이 모든 입력을 수정 요청으로 간주해
  무조건 재생성하고 고정 문구("요청하신 사항을 계획에 반영했습니다")로 답하던 방식을 제거.
  이제 LLM의 자연어 답변(무엇을 어떻게 바꿨는지)을 그대로 표시하고,
  실제로 계획이 바뀐 경우에만 오른쪽 체크리스트를 갱신한다.
- 기본 모델을 `meta-llama/llama-3-8b-instruct:free` → `qwen/qwen3.7-plus`로 변경
  (`OPENROUTER_MODEL` 환경변수로 여전히 덮어쓰기 가능).
- **추론(thinking) 모드 비활성화** — qwen3.7-plus가 추론 모델이라 요청당 약 95초·
  reasoning 4천 토큰을 소모하던 것을 `reasoning: {enabled: false}`로 해결.
- 하루 할 일 개수를 **투자 시간에 비례**하도록 변경(1h→1~2개 … 7h+→5~6개).
  초안/수정/자유대화 프롬프트와 mock 폴백에 동일 기준 적용.
- OCI 배포 스크립트가 `~/.delaynomore.env`(또는 `ENV_FILE`)를 자동 로드 —
  API 키를 최초 1회만 파일(chmod 600)로 저장하면 이후 배포에서 키 입력이 불필요하고,
  셸 히스토리에 키가 남지 않는다. (명령줄로 준 값이 파일 값보다 우선)
- mock 폴백 대화 개선 — 인식 못 하는 요청에 "반영했다"고 답하지 않고,
  오프라인 모드임을 밝히며 가능한 요청 예시와 함께 되묻는다.

### Fixed
- LLM이 기대와 다른 JSON 스키마(`{plan:[{date,tasks}]}` 등)로 응답하면 체크리스트가
  비어 보이거나 화면 전체가 죽던(white-screen) 문제 — 다양한 응답 형태를
  날짜맵으로 흡수하는 정규화(`coerceToDateMap`/`normalizeTasks`)와 렌더링 방어 추가.
- 백엔드 `sanitizeJson`이 코드펜스 밖 설명/사고 텍스트가 섞인 응답에서도
  중괄호 균형으로 최상위 JSON 객체만 추출하도록 보강.
- OCI 배포 스크립트가 `OPENROUTER_API_KEY`/`OPENROUTER_MODEL` 미설정 시
  **빈 문자열**을 컨테이너에 넘겨 `application.yml` 기본값이 무시되던 문제 수정 —
  이제 값이 있을 때만 `-e`를 전달한다.

### Removed
- 중복되던 `backend/Dockerfile` 제거(루트 `Dockerfile`로 단일화).

[Unreleased]: https://github.com/hello-pebble/DelayNoMore_Release/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/hello-pebble/DelayNoMore_Release/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/hello-pebble/DelayNoMore_Release/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/hello-pebble/DelayNoMore_Release/releases/tag/v0.1.0
