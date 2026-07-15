#!/usr/bin/env bash
# VM에서 빌드하지 않고, GitHub Actions가 빌드해 ghcr.io에 올린 이미지를 받아 실행한다.
# 사양이 낮은 Always Free VM(예: 1GB AMD Micro)에서 gradle/npm 빌드로 마비되는 것을 피한다.
#
# 사용법 (저장소 루트 또는 어디서든):
#   ./deploy/oci-pull.sh                      # HTTP 로 80 포트 노출 (기본)
#   DOMAIN=todo.example.com ./deploy/oci-pull.sh   # HTTPS (Caddy 리버스 프록시 자동 구성)
# 인자(환경변수):
#   HOST_PORT (기본 80)  : (HTTP 모드) 외부로 노출할 호스트 포트
#   DOMAIN               : (선택) 설정 시 HTTPS 모드 — Caddy가 이 도메인으로 Let's Encrypt
#                          인증서를 자동 발급하고 80→443 리다이렉트, 앱은 127.0.0.1:8080 로만 노출.
#                          사전조건: 이 도메인의 A/AAAA 레코드가 이 VM 퍼블릭 IP를 가리켜야 하고,
#                          OCI 보안 목록에서 80·443 인바운드를 열어둬야 한다.
#   IMAGE                : 받을 이미지 (기본 ghcr.io/hello-pebble/delaynomore_release:latest)
#   OPENROUTER_API_KEY   : (선택) 미설정 시 프론트 mock 폴백
#   OPENROUTER_MODEL     : (선택) 사용할 모델 ID
#   ENV_FILE             : (선택) 환경변수 파일 경로 (기본 ~/.delaynomore.env)
#
# 키를 매번 입력하지 않으려면 ~/.delaynomore.env 를 만들어 둔다 (git 커밋 금지, chmod 600):
#   OPENROUTER_API_KEY=sk-or-...
#   OPENROUTER_MODEL=qwen/qwen3.7-plus
#   DOMAIN=todo.example.com          # HTTPS 를 상시 쓰려면 여기 넣어두면 된다
set -euo pipefail

# env 파일 자동 로드 — 명령줄로 이미 준 값은 덮어쓰지 않는다(명령줄 우선).
ENV_FILE="${ENV_FILE:-${HOME}/.delaynomore.env}"
if [ -f "${ENV_FILE}" ]; then
  echo "==> env 파일 로드: ${ENV_FILE}"
  _cli_key="${OPENROUTER_API_KEY:-}"
  _cli_model="${OPENROUTER_MODEL:-}"
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
  [ -n "${_cli_key}" ] && OPENROUTER_API_KEY="${_cli_key}"
  [ -n "${_cli_model}" ] && OPENROUTER_MODEL="${_cli_model}"
fi

HOST_PORT="${HOST_PORT:-80}"
IMAGE="${IMAGE:-ghcr.io/hello-pebble/delaynomore_release:latest}"
NAME="delaynomore"
DOMAIN="${DOMAIN:-}"                 # 설정되면 HTTPS 모드
CADDY_NAME="delaynomore-caddy"
# HTTPS 모드에서 앱은 로컬에만(127.0.0.1) 바인딩하고 Caddy가 80/443을 담당한다.
# HTTP 모드에서는 앱을 공개 포트에 그대로 노출한다.
if [ -n "${DOMAIN}" ]; then
  APP_PUBLISH="127.0.0.1:8080:8080"
else
  APP_PUBLISH="${HOST_PORT}:8080"
fi

echo "==> [1/4] Docker 확인/설치"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo systemctl enable --now docker
fi

# iptables 인바운드 허용 헬퍼(중복 방지).
open_port() {
  local p="$1"
  if sudo iptables -C INPUT -p tcp --dport "${p}" -j ACCEPT 2>/dev/null; then
    echo "    ${p}/tcp 이미 허용됨"
  else
    sudo iptables -I INPUT 1 -p tcp --dport "${p}" -j ACCEPT
  fi
}

echo "==> [2/4] 방화벽(iptables) 인바운드 허용"
if [ -n "${DOMAIN}" ]; then
  open_port 80    # ACME HTTP-01 챌린지 + HTTPS 리다이렉트
  open_port 443   # HTTPS
else
  open_port "${HOST_PORT}"
fi
if command -v netfilter-persistent >/dev/null 2>&1; then
  sudo netfilter-persistent save || true
fi

echo "==> [3/4] 이미지 받아서 실행: ${IMAGE}"
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

sudo docker rm -f "${NAME}" 2>/dev/null || true
sudo docker run -d \
  --name "${NAME}" \
  --restart unless-stopped \
  -p "${APP_PUBLISH}" \
  "${ENV_ARGS[@]}" \
  "${IMAGE}"

echo "==> [4/4] HTTPS(Caddy) 구성"
if [ -n "${DOMAIN}" ]; then
  # Caddyfile 위치 파악(이 스크립트와 같은 deploy/ 폴더). 저장소 밖에서 실행돼도 동작하도록
  # 스크립트 기준 경로를 쓰되, 없으면 최소 설정을 즉석에서 생성한다.
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  CADDYFILE="${SCRIPT_DIR}/Caddyfile"
  if [ ! -f "${CADDYFILE}" ]; then
    CADDYFILE="/tmp/delaynomore.Caddyfile"
    printf '%s\n' '{$DOMAIN} {' '	encode gzip' '	reverse_proxy 127.0.0.1:8080' '}' > "${CADDYFILE}"
  fi
  echo "    도메인: ${DOMAIN}  (Caddyfile: ${CADDYFILE})"
  echo "    Let's Encrypt 인증서는 Caddy가 자동 발급/갱신하고 80→443 리다이렉트를 건다."
  # 인증서/설정은 named volume에 저장해 재시작 시 재발급(레이트리밋)을 피한다.
  sudo docker rm -f "${CADDY_NAME}" 2>/dev/null || true
  sudo docker run -d \
    --name "${CADDY_NAME}" \
    --restart unless-stopped \
    --network host \
    -e DOMAIN="${DOMAIN}" \
    -v "${CADDYFILE}:/etc/caddy/Caddyfile:ro" \
    -v caddy_data:/data \
    -v caddy_config:/config \
    caddy:2
else
  echo "    (HTTP 모드 — DOMAIN 미설정이라 Caddy는 실행하지 않음)"
fi

# 예전 이미지 레이어가 디스크에 쌓이는 것을 정리한다(작은 VM 디스크 보호).
sudo docker image prune -f >/dev/null 2>&1 || true

echo
echo "완료. 상태 확인:"
echo "  sudo docker ps"
if [ -n "${DOMAIN}" ]; then
  echo "  sudo docker logs -f ${CADDY_NAME}     # 인증서 발급 진행 확인"
  echo "  curl -s http://localhost:8080/api/ai/health   # 앱(로컬)"
  echo "브라우저: https://${DOMAIN}   (http:// 로 접속해도 https 로 자동 전환)"
else
  echo "  curl -s http://localhost:${HOST_PORT}/api/ai/health"
  echo "브라우저: http://<VM_PUBLIC_IP>:${HOST_PORT}"
fi
