# DelayNoMore — 대화형 투두리스트 생성 데모

> [DelayNoMore](https://github.com/hello-pebble/DelayNoMore)의 **"대화를 통해 투두리스트(하루 단위 실행 계획)를 생성하는"** 핵심 흐름만 떼어낸 최소 배포판입니다.

원본 서비스의 인증 · 목표 저장 · 팀 공유 · 알림 · 지연 리포트 등은 제거하고,
**AI 코치와의 슬롯필링 대화 → 계획 초안(투두리스트) 생성/보완** 흐름만 남겼습니다.

## 기능

1. AI 코치가 4가지를 순서대로 질문합니다 — **목표 → 기간(1~30일) → 하루 투자 시간(1~24h) → 현재 수준**.
2. 입력이 모두 채워지면 백엔드가 OpenRouter로 하루 단위 계획 초안(투두리스트)을 생성합니다.
3. 생성된 카드에서 **"계획 보완/수정"** 으로 채팅을 통해 계획을 재조정할 수 있습니다 (예: "주말은 빼줘", "일정을 늘려줘").
4. `OPENROUTER_API_KEY`가 없거나 백엔드가 응답하지 않으면 프론트가 **템플릿 기반 mock 계획**으로 자동 폴백하여 데모 흐름이 끊기지 않습니다.

## 구조

화면은 좌우로 분할되어 **왼쪽=AI 코치와의 대화**, **오른쪽=생성된 체크리스트**를 동시에 보여줍니다. (모바일 폭에서는 위아래로 스택됩니다.)

```
DelayNoMore_Release/
├── Dockerfile  # 단일 배포: 프론트 빌드 → 백엔드 static 포함 → 하나의 jar/컨테이너
├── frontend/   # React 19 + Vite (순수 JS/JSX) — 심플 디자인(시스템 폰트, 무배경)
│   └── src/
│       ├── App.jsx                    # 헤더 + AI 상태 표시 + 코치 화면 마운트
│       ├── ai_engine.js               # 슬롯필링 로직 + 계획 생성 + mock 폴백
│       ├── db_service.js              # 백엔드 AI 프록시 호출(단일 REST 클라이언트)
│       └── components/chat_coach.jsx  # 좌우 분할: 대화 패널 + 체크리스트 패널
└── backend/    # Spring Boot 3.3.1 / Java 17 (AI 프록시 + 정적 화면 서빙)
    └── src/main/java/.../controller/AiController.java   # /api/ai/draft, /api/ai/health
```

## 로컬 실행

### 1. 백엔드 (포트 8080)

```bash
cd backend
OPENROUTER_API_KEY=<your_key> ./gradlew bootRun   # Windows: gradlew.bat bootRun
```

- `OPENROUTER_API_KEY`를 주지 않아도 서버는 기동됩니다(이 경우 프론트가 mock 폴백).
- 모델은 `OPENROUTER_MODEL` 환경변수로 바꿀 수 있습니다(기본: `meta-llama/llama-3-8b-instruct:free`).

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
- Cloud Run · Render · Railway · Fly.io 등 컨테이너를 받는 어떤 호스팅에도 이 이미지 하나만 올리면 됩니다.

## 환경변수

| 변수 | 대상 | 설명 |
| :--- | :--- | :--- |
| `OPENROUTER_API_KEY` | backend | OpenRouter API 키(서버에만 보관). 미설정 시 프론트 mock 폴백. |
| `OPENROUTER_MODEL` | backend | 사용할 모델 ID (선택). |
