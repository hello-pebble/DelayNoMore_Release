# 변경 이력 (Changelog)

이 프로젝트의 모든 주요 변경사항을 이 파일에 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르며,
버전 규칙은 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 따릅니다.

- **MAJOR**: 구조가 크게 바뀌는 변경 (예: 인증 도입, DB 추가)
- **MINOR**: 하위 호환되는 기능 추가 (예: 목표 저장, 팀 공유)
- **PATCH**: 하위 호환되는 버그/디자인 수정

## [Unreleased]

### Added
- Render 배포용 블루프린트(`render.yaml`) — 루트 Dockerfile 기반 단일 웹 서비스.
- Oracle Cloud(OCI) Always Free 배포 가이드(`docs/DEPLOY_OCI.md`)와
  VM 자동 세팅 스크립트(`deploy/oci-setup.sh`) — Ampere A1 + Docker.
- 기능 점검 체크리스트 문서(`docs/QA_CHECKLIST.md`) — 버전별 QA 항목.
- 대화 엔드포인트 `/api/ai/chat` — 초안 생성 이후 유저 메시지를 LLM이 직접 해석해
  **의도(계획 수정 / 질문·잡담 / 불명확)** 를 판단. 최근 대화 이력을 함께 전달해
  "반영 안됐는데?" 같은 맥락 의존 발화도 처리한다.

### Changed
- 서버 포트를 `${PORT:8080}`로 변경 — 배포 플랫폼(Render·Cloud Run·OCI 등)이
  주입하는 `PORT`를 사용하고, 로컬은 8080 기본값 유지.
- **LLM 대화 고도화**: 초안 생성 후 채팅이 모든 입력을 수정 요청으로 간주해
  무조건 재생성하고 고정 문구("요청하신 사항을 계획에 반영했습니다")로 답하던 방식을 제거.
  이제 LLM의 자연어 답변(무엇을 어떻게 바꿨는지)을 그대로 표시하고,
  실제로 계획이 바뀐 경우에만 오른쪽 체크리스트를 갱신한다.
  LLM 응답의 tasks 형식을 검증(normalize)해 깨진 응답이 화면을 망가뜨리지 않게 한다.
- mock 폴백 대화 개선 — 인식 못 하는 요청에 "반영했다"고 답하지 않고,
  오프라인 모드임을 밝히며 가능한 요청 예시와 함께 되묻는다.
- 기본 모델을 `meta-llama/llama-3-8b-instruct:free` → `qwen/qwen3.7-plus`로 변경
  (`OPENROUTER_MODEL` 환경변수로 여전히 덮어쓰기 가능).

### Fixed
- OCI 배포 스크립트가 `OPENROUTER_API_KEY`/`OPENROUTER_MODEL` 미설정 시
  **빈 문자열**을 컨테이너에 넘겨 `application.yml` 기본값이 무시되던 문제 수정 —
  이제 값이 있을 때만 `-e`를 전달한다. (빈 model로 OpenRouter 호출이 조용히 실패해
  키가 있어도 mock 폴백으로 동작할 수 있었다.)

## [0.1.0] - 2026-07-13

첫 데모 릴리스. 원본 [DelayNoMore](https://github.com/hello-pebble/DelayNoMore)의
"대화를 통해 투두리스트(하루 단위 실행 계획)를 생성하는" 핵심 흐름만 떼어낸 최소 배포판.

### Added
- AI 코치와의 슬롯필링 대화 → 계획 초안(투두리스트) 생성/보완 흐름.
- 좌우 분할 화면: 왼쪽 = AI 코치 대화, 오른쪽 = 생성된 체크리스트 (모바일 폭에서는 상하 스택).
- 백엔드 AI 프록시(`/api/ai/draft`, `/api/ai/health`) — OpenRouter 키를 서버에만 보관.
- `OPENROUTER_API_KEY` 미설정/백엔드 미가용 시 프론트의 템플릿 기반 mock 폴백.
- 단일 배포 구성: Spring Boot가 빌드된 프론트엔드 정적 파일과 `/api/*`를 함께 서빙,
  루트 `Dockerfile` 하나로 컨테이너 배포.

### Changed
- 디자인을 심플하게 정리 — 커스텀 폰트·노트/그리드 배경·글래스모피즘·테마 토글 제거,
  시스템 폰트와 단순 색상만 사용(기능 동작 우선).
- 빌드 Java 버전을 17 → 21로 상향 (`build.gradle` toolchain, `Dockerfile` 이미지).
- Spring Boot 3.3.1 → 4.1.0 최신화. Jackson 3(`tools.jackson`) 이전에 따라
  `AiController`의 Jackson 사용부 마이그레이션(`asText` → `asString`).

### Removed
- 중복되던 `backend/Dockerfile` 제거(루트 `Dockerfile`로 단일화).

[Unreleased]: https://github.com/hello-pebble/DelayNoMore_Release/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/hello-pebble/DelayNoMore_Release/releases/tag/v0.1.0
