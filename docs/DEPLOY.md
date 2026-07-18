# 실행 · 배포

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

- **Oracle Cloud(OCI) Always Free** (권장 · 상시 무료 · 현재 라이브 데모가 배포된 곳): [`DEPLOY_OCI.md`](DEPLOY_OCI.md) — GitHub Actions가 이미지를 `ghcr.io`에 빌드/푸시하고, VM은 `deploy/oci-pull.sh`로 **빌드 없이 pull만** 해서 배포(낮은 사양 VM 권장). RAM이 넉넉하면 `deploy/oci-setup.sh`로 VM에서 직접 빌드도 가능.

## 환경변수

| 변수 | 대상 | 설명 |
| :--- | :--- | :--- |
| `OPENROUTER_API_KEY` | backend | OpenRouter API 키(서버에만 보관). 미설정 시 프론트 mock 폴백. |
| `OPENROUTER_MODEL` | backend | 사용할 모델 ID (선택). |

관련 문서: [배포 회고](DEPLOY_RETROSPECTIVE.md) · [구조](ARCHITECTURE.md)
