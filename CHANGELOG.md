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

### Changed
- 서버 포트를 `${PORT:8080}`로 변경 — 배포 플랫폼(Render·Cloud Run·OCI 등)이
  주입하는 `PORT`를 사용하고, 로컬은 8080 기본값 유지.

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
