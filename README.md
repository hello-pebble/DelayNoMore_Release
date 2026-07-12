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

```
DelayNoMore_Release/
├── frontend/   # React 19 + Vite (순수 JS/JSX)
│   └── src/
│       ├── App.jsx                    # 테마 + AI 상태 LED + 코치 화면 마운트
│       ├── ai_engine.js               # 슬롯필링 로직 + 계획 생성 + mock 폴백
│       ├── db_service.js              # 백엔드 AI 프록시 호출(단일 REST 클라이언트)
│       └── components/chat_coach.jsx  # 대화 UI + 생성된 투두 카드
└── backend/    # Spring Boot 3.3.1 / Java 17 (AI 프록시 전용)
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

## 배포

- **프론트엔드**: `npm run build` → `frontend/dist`를 정적 호스팅(Vercel · Netlify · Cloudflare Pages 등). 배포 시 `/api/*`를 백엔드 주소로 프록시/리라이트하도록 설정하세요.
- **백엔드**: `backend/Dockerfile`로 컨테이너 이미지를 빌드해 임의의 Java 호스팅에 배포하고 `OPENROUTER_API_KEY`를 주입하세요.

## 환경변수

| 변수 | 대상 | 설명 |
| :--- | :--- | :--- |
| `OPENROUTER_API_KEY` | backend | OpenRouter API 키(서버에만 보관). 미설정 시 프론트 mock 폴백. |
| `OPENROUTER_MODEL` | backend | 사용할 모델 ID (선택). |
