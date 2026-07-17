# DelayNoMore — 대화형 투두리스트 생성 데모

> [DelayNoMore](https://github.com/hello-pebble/DelayNoMore)의 **"대화를 통해 투두리스트(하루 단위 실행 계획)를 생성하는"** 핵심 흐름만 떼어낸 최소 배포판입니다.

원본 서비스의 인증 · 목표 저장 · 팀 공유 · 알림 · 지연 리포트 등은 제거하고,
**AI 코치와의 슬롯필링 대화 → 계획 초안(투두리스트) 생성/보완** 흐름만 남겼습니다.

## 기능

1. AI 코치가 4가지를 순서대로 질문합니다 — **목표 → 기간(1~30일) → 하루 투자 시간(1~24h) → 현재 수준**.
2. 입력이 모두 채워지면 백엔드가 OpenRouter로 하루 단위 계획 초안(투두리스트)을 생성합니다.
3. 초안 생성 후에는 **자유 대화 모드**가 됩니다 — LLM이 메시지의 의도를 판단해, 수정 요청이면 계획을 고치고 무엇을 바꿨는지 설명하고(예: "주말은 빼줘"), 질문이면 답하고, 불명확하면 되묻습니다.
4. `OPENROUTER_API_KEY`가 없거나 백엔드가 응답하지 않으면 프론트가 **템플릿 기반 mock 계획**으로 자동 폴백하여 데모 흐름이 끊기지 않습니다.
5. **여러 계획 보관(데모 공유 보관함)** — 완성된 계획은 서버 보관함에 **자동 보관**되고, 이후의 수정·완료 체크·고정도 자동 동기화됩니다. 우측 패널의 "보관된 계획" 목록에서 여러 계획을 전환/삭제할 수 있고, 새로고침하면 마지막으로 보던 계획이 복원됩니다. 로그인/DB 없이 **서버 메모리에만 휘발성으로** 보관하므로 모든 방문자가 같은 목록을 보고(원격 기능 테스트용), 서버 재시작 시 초기화됩니다.
6. **"계획 저장" 버튼은 계획을 고정(확정)합니다** — 고정 후에는 대화로 계획을 수정할 수 없고(강제성 부여: 확정한 계획은 재협상 없이 실행), 완료 체크와 질문만 가능합니다. 바꾸려면 "처음부터 다시 만들기"로 새 계획을 시작하세요(이전 계획은 보관함에 남습니다).
7. **오늘 할 일(오늘 보기)** — 화면 상단의 접이식 밴드가 **모든 보관된 계획**에서 오늘 날짜의 할 일만 모아 계획 이름과 함께 보여줍니다. 밴드에서 바로 완료 체크할 수 있고(원본 계획과 즉시 양방향 반영 + 서버 저장), "오늘 n/m 완료" 진행률과 "계획 보기" 이동 버튼을 제공합니다. 오늘 항목이 없으면 "오늘 할 일이 없습니다"로 안내합니다.

## 구조

화면은 좌우로 분할되어 **왼쪽=AI 코치와의 대화**, **오른쪽=생성된 체크리스트**를 동시에 보여줍니다. (모바일 폭에서는 위아래로 스택됩니다.)

```
DelayNoMore_Release/
├── Dockerfile  # 단일 배포: 프론트 빌드 → 백엔드 static 포함 → 하나의 jar/컨테이너
├── frontend/   # React 19 + Vite (순수 JS/JSX) — 심플 디자인(시스템 폰트, 무배경)
│   └── src/
│       ├── App.jsx                    # 헤더 + AI 상태 표시 + 코치 화면 마운트
│       ├── ai_engine.js               # 슬롯필링 로직 + 계획 생성 + mock 폴백
│       ├── db_service.js              # 백엔드 호출(단일 REST 클라이언트) — AI 프록시 + 계획 보관함
│       └── components/chat_coach.jsx  # 좌우 분할: 대화 패널 + 체크리스트/보관함 패널
└── backend/    # Spring Boot 4.1 / Java 21 (AI 프록시 + 계획 보관함 + 정적 화면 서빙)
    └── src/main/java/.../
        ├── domain/ai/   # controller·service·client·dto — /api/v1/ai/{health,drafts,chats}(+/stream)
        ├── domain/plan/ # 계획 보관함(인메모리·휘발성) — /api/v1/plans CRUD, 추후 DB로 교체 예정
        └── global/      # 공통: response(ApiResponse) · error(ErrorCode, GlobalExceptionHandler) · config
```

## 로컬 실행

### 1. 백엔드 (포트 8080)

```bash
cd backend
OPENROUTER_API_KEY=<your_key> ./gradlew bootRun   # Windows: gradlew.bat bootRun
```

- `OPENROUTER_API_KEY`를 주지 않아도 서버는 기동됩니다(이 경우 프론트가 mock 폴백).
- 모델은 `OPENROUTER_MODEL` 환경변수로 바꿀 수 있습니다(기본: `qwen/qwen3.7-plus`).

### 2. 프론트엔드 (포트 5173)

```bash
cd frontend
npm install
npm run dev
```

Vite 개발 서버가 `/api/*` 요청을 `http://localhost:8080`으로 프록시합니다.

## 배포 (단일 컨테이너)

프론트엔드와 백엔드를 **하나의 이미지**로 빌드/배포합니다. Spring Boot가 빌드된 프론트엔드 정적 파일과 `/api/*`를 같은 서버(포트 8080)에서 함께 서빙하므로, 프론트/백엔드를 따로 배포하거나 `/api/*` 프록시를 별도로 설정할 필요가 없습니다.

```bash
# 저장소 루트에서
docker build -t delaynomore .
docker run -p 8080:8080 -e OPENROUTER_API_KEY=<your_key> delaynomore
# http://localhost:8080 접속
```

- `OPENROUTER_API_KEY` 미설정 시에도 컨테이너는 기동되며, 이 경우 프론트가 mock 폴백으로 동작합니다.
- 앱은 배포 플랫폼이 주입하는 `PORT`로 바인딩합니다(로컬 기본값 8080).
- Cloud Run · Render · Railway 등 컨테이너를 받는 어떤 호스팅에도 이 이미지 하나만 올리면 됩니다.

### 플랫폼별 가이드

- **Oracle Cloud(OCI) Always Free** (권장 · 상시 무료): [`docs/DEPLOY_OCI.md`](./docs/DEPLOY_OCI.md) — GitHub Actions가 이미지를 `ghcr.io`에 빌드/푸시하고, VM은 `deploy/oci-pull.sh`로 **빌드 없이 pull만** 해서 배포(낮은 사양 VM 권장). RAM이 넉넉하면 `deploy/oci-setup.sh`로 VM에서 직접 빌드도 가능.
- **Render**: 루트 `render.yaml` 블루프린트로 배포(New → Blueprint). 무료 티어는 미사용 시 슬립.

## 환경변수

| 변수 | 대상 | 설명 |
| :--- | :--- | :--- |
| `OPENROUTER_API_KEY` | backend | OpenRouter API 키(서버에만 보관). 미설정 시 프론트 mock 폴백. |
| `OPENROUTER_MODEL` | backend | 사용할 모델 ID (선택). |

## 버전 관리

- 이 프로젝트는 **버전을 나눠 점진적으로 진화**합니다. 현재 버전: **v0.5.0**.
- 버전 규칙은 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 따르며, 프론트엔드(`package.json`)와 백엔드(`build.gradle`)는 **하나의 제품 버전**으로 통일합니다.
- 버전별 변경사항은 [`CHANGELOG.md`](./CHANGELOG.md)에 기록합니다.
- 버전별 기능 점검은 [`docs/QA_CHECKLIST.md`](./docs/QA_CHECKLIST.md)로 확인합니다.
- 배포 회고와 AI 파라미터 제어 방향은 [`docs/DEPLOY_RETROSPECTIVE.md`](./docs/DEPLOY_RETROSPECTIVE.md)에 정리했습니다.
- 브랜치 전략은 **트렁크 기반** — `main`은 항상 배포 가능한 상태로 유지하고, 기능마다 짧게 사는 브랜치 → PR → `main` 머지 → `vX.Y.Z` 태그를 찍습니다.
- PR/`main` 푸시 시 [CI](./.github/workflows/ci.yml)가 프론트(lint+build)·백엔드(bootJar) 빌드를 검증합니다.
