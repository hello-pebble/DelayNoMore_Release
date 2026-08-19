# 실행 · 배포

## 로컬 실행

### 1. 백엔드 (포트 8080)

```bash
cd backend
OPENROUTER_API_KEY=<your_key> ./gradlew bootRun   # Windows: gradlew.bat bootRun
```

- `OPENROUTER_API_KEY`를 주지 않아도 서버는 기동됩니다(이 경우 프론트가 mock 폴백).
- 모델은 `OPENROUTER_MODEL` 환경변수로 바꿀 수 있습니다(기본: `qwen/qwen3.7-plus`).
- 코치의 에이전트(도구 호출) 경로는 기본으로 켜져 있습니다. **도구 호출을 지원하지 않는 모델로
  바꿨다면** `OPENROUTER_TOOL_CALLING=false`로 끄세요 — 코드 배포 없이 기존 자유 대화 경로로
  되돌아갑니다(끄지 않아도 실패 시 자동 폴백하지만, 매 요청마다 헛된 왕복이 한 번 더 생깁니다).
  모델의 도구 지원 확인 방법은 [에이전트 문서](AGENT.md#모델-스위치)를 보세요.

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

- **Oracle Cloud(OCI) Always Free** (권장 · 상시 무료 · 현재 라이브 데모가 배포된 곳): [`DEPLOY_OCI.md`](DEPLOY_OCI.md) — GitHub Actions가 이미지를 `ghcr.io`에 빌드/푸시하고, VM은 `deploy/oci-pull.sh`로 **빌드 없이 pull만** 해서 배포(낮은 사양 VM 권장). RAM이 넉넉하면 `deploy/oci-setup.sh`로 VM에서 직접 빌드도 가능.

## 환경변수

| 변수 | 대상 | 설명 |
| :--- | :--- | :--- |
| `OPENROUTER_API_KEY` | backend | OpenRouter API 키(서버에만 보관). 미설정 시 프론트 mock 폴백. |
| `OPENROUTER_MODEL` | backend | 사용할 모델 ID (선택). |
| `OPENROUTER_TOOL_CALLING` | backend | 에이전트(도구 호출) 경로 on/off (선택, 기본 `true`). 도구 미지원 모델로 바꿀 때 `false`. |
| `OPENROUTER_STREAM_USAGE` | backend | 스트리밍 응답 끝의 usage 청크 요청 on/off (선택, 기본 `true`). 끄면 스트리밍 경로의 토큰 사용량 로그만 사라지고 스트리밍 자체는 그대로 동작한다. |
| `GOOGLE_CLIENT_ID` | backend | Google 로그인(GIS) 클라이언트 ID (선택, v0.22.0). 미설정 시 로그인 기능이 통째로 꺼지고 프론트 버튼이 숨는다. GIS는 https(또는 localhost) 오리진 필수 — 배포 스크립트의 `DOMAIN` 옵션으로 HTTPS를 먼저 켠다([DEPLOY_OCI.md](DEPLOY_OCI.md)). |
| `GOOGLE_LOGIN_ENABLED` | backend | 로그인 긴급 오프 스위치 (선택, 기본 `true`). 클라이언트 ID를 지우지 않고 로그인만 끈다. |
| `DOMAIN` | 배포 스크립트 | HTTPS 도메인 (선택). 설정하면 Caddy 컨테이너가 80/443에 서고 Let's Encrypt 인증서를 자동 발급한다. |

> **토큰 사용량 보기** — 모든 LLM 호출이 `ai.usage`로 시작하는 로그 한 줄을 남깁니다.
> 경로별 비교는 `site` 라벨로 합니다(`chat.stream` = 에이전트 이전 경로, `agent.total` = 에이전트
> 요청 하나의 합계). 자세한 형식은 [에이전트 문서 6장](AGENT.md#6-관측--토큰-사용량-로그-v0152).
> ```bash
> docker logs <container> 2>&1 | grep 'ai.usage site=agent.total'
> ```

관련 문서: [배포 회고](DEPLOY_RETROSPECTIVE.md) · [구조](ARCHITECTURE.md)
