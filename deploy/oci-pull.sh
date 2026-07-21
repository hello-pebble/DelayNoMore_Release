#!/usr/bin/env bash
# VM에서 빌드하지 않고, GitHub Actions가 빌드해 ghcr.io에 올린 이미지를 받아 실행한다.
# 사양이 낮은 Always Free VM(예: 1GB AMD Micro)에서 gradle/npm 빌드로 마비되는 것을 피한다.
#
# 사용법 (저장소 루트 또는 어디서든):
#   ./deploy/oci-pull.sh
# 인자(환경변수):
#   HOST_PORT (기본 80)  : 외부로 노출할 호스트 포트
#   IMAGE                : 받을 이미지 (기본 ghcr.io/hello-pebble/delaynomore_release:latest)
#   OPENROUTER_API_KEY   : (선택) 미설정 시 프론트 mock 폴백
#   OPENROUTER_MODEL     : (선택) 사용할 모델 ID
#   DB_URL               : (선택) PostgreSQL(Supabase) JDBC URL. 지정하면 영속 모드(postgres 프로필)로
#                          기동한다. 미지정이면 인메모리(휘발성) 모드 — 재시작 시 데이터 소실.
#   DB_USERNAME          : (DB_URL 지정 시 필수) DB 사용자. Supabase 세션 풀러는 postgres.<project-ref>
#   DB_PASSWORD          : (DB_URL 지정 시 필수) DB 비밀번호
#   ENV_FILE             : (선택) 환경변수 파일 경로 (기본 ~/.delaynomore.env)
#
# 키를 매번 입력하지 않으려면 ~/.delaynomore.env 를 만들어 둔다 (git 커밋 금지, chmod 600):
#   OPENROUTER_API_KEY=sk-or-...
#   OPENROUTER_MODEL=qwen/qwen3.7-plus
#   # 영속화(Supabase) — 대시보드 Connect의 "Session pooler"(포트 5432) 문자열 권장, sslmode=require:
#   DB_URL=jdbc:postgresql://aws-1-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=require
#   DB_USERNAME=postgres.<project-ref>
#   DB_PASSWORD=...
set -euo pipefail

# env 파일 자동 로드 — 명령줄로 이미 준 값은 덮어쓰지 않는다(명령줄 우선).
ENV_FILE="${ENV_FILE:-${HOME}/.delaynomore.env}"
if [ -f "${ENV_FILE}" ]; then
  echo "==> env 파일 로드: ${ENV_FILE}"
  _cli_key="${OPENROUTER_API_KEY:-}"
  _cli_model="${OPENROUTER_MODEL:-}"
  _cli_dburl="${DB_URL:-}"
  _cli_dbuser="${DB_USERNAME:-}"
  _cli_dbpass="${DB_PASSWORD:-}"
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
  [ -n "${_cli_key}" ] && OPENROUTER_API_KEY="${_cli_key}"
  [ -n "${_cli_model}" ] && OPENROUTER_MODEL="${_cli_model}"
  [ -n "${_cli_dburl}" ] && DB_URL="${_cli_dburl}"
  [ -n "${_cli_dbuser}" ] && DB_USERNAME="${_cli_dbuser}"
  [ -n "${_cli_dbpass}" ] && DB_PASSWORD="${_cli_dbpass}"
fi

HOST_PORT="${HOST_PORT:-80}"
IMAGE="${IMAGE:-ghcr.io/hello-pebble/delaynomore_release:latest}"
NAME="delaynomore"

echo "==> [1/3] Docker 확인/설치"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo systemctl enable --now docker
fi

echo "==> [2/3] 방화벽(iptables)에서 ${HOST_PORT}/tcp 허용"
if sudo iptables -C INPUT -p tcp --dport "${HOST_PORT}" -j ACCEPT 2>/dev/null; then
  echo "    이미 허용됨"
else
  sudo iptables -I INPUT 1 -p tcp --dport "${HOST_PORT}" -j ACCEPT
  if command -v netfilter-persistent >/dev/null 2>&1; then
    sudo netfilter-persistent save || true
  fi
fi

echo "==> [3/3] 이미지 받아서 실행: ${IMAGE}"
sudo docker pull "${IMAGE}"

# 값이 비어 있으면 -e 자체를 생략한다(빈 문자열을 넘기면 Spring 기본값이 무시됨).
# 낮은 사양 VM에서 JVM이 메모리를 과도하게 잡지 않도록 힙 상한도 건다.
ENV_ARGS=(-e PORT=8080 -e JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50)
if [ -n "${OPENROUTER_API_KEY:-}" ]; then
  ENV_ARGS+=(-e OPENROUTER_API_KEY="${OPENROUTER_API_KEY}")
fi
if [ -n "${OPENROUTER_MODEL:-}" ]; then
  ENV_ARGS+=(-e OPENROUTER_MODEL="${OPENROUTER_MODEL}")
fi

# DB_URL이 있으면 영속 모드(postgres 프로필)로 기동한다 — Flyway가 첫 부팅에 스키마를 적용하고
# 데이터는 Supabase(외부 관리형)에 남아 컨테이너/VM 재시작과 무관하게 복원된다. DB_URL이 없으면
# 인메모리(휘발성) 모드 — 이 스크립트의 기존 동작 그대로.
if [ -n "${DB_URL:-}" ]; then
  if [ -z "${DB_USERNAME:-}" ] || [ -z "${DB_PASSWORD:-}" ]; then
    echo "오류: DB_URL을 지정하면 DB_USERNAME·DB_PASSWORD도 필요합니다." >&2
    exit 1
  fi
  echo "==> 영속 모드(postgres 프로필) — ${DB_URL%%\?*}"
  ENV_ARGS+=(-e SPRING_PROFILES_ACTIVE=postgres)
  ENV_ARGS+=(-e DB_URL="${DB_URL}")
  ENV_ARGS+=(-e DB_USERNAME="${DB_USERNAME}")
  ENV_ARGS+=(-e DB_PASSWORD="${DB_PASSWORD}")
else
  echo "==> 인메모리(휘발성) 모드 — DB_URL 미설정. 재시작 시 데이터가 초기화됩니다."
fi

sudo docker rm -f "${NAME}" 2>/dev/null || true
sudo docker run -d \
  --name "${NAME}" \
  --restart unless-stopped \
  -p "${HOST_PORT}:8080" \
  "${ENV_ARGS[@]}" \
  "${IMAGE}"

# 예전 이미지 레이어가 디스크에 쌓이는 것을 정리한다(작은 VM 디스크 보호).
sudo docker image prune -f >/dev/null 2>&1 || true

echo
echo "완료. 상태 확인:"
echo "  sudo docker ps"
echo "  curl -s http://localhost:${HOST_PORT}/api/v1/ai/health"
echo "브라우저: http://<VM_PUBLIC_IP>:${HOST_PORT}"
