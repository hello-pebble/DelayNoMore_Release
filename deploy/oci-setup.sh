#!/usr/bin/env bash
# Oracle Cloud(OCI) Always Free VM에 DelayNoMore를 배포한다.
# 사용법: 저장소 루트에서 실행.
#   OPENROUTER_API_KEY=<key> ./deploy/oci-setup.sh
# 인자:
#   HOST_PORT (기본 80)  : 외부로 노출할 호스트 포트
#   OPENROUTER_API_KEY   : (선택) 미설정 시 프론트 mock 폴백으로 동작
#   OPENROUTER_MODEL     : (선택) 사용할 모델 ID
#
# 이 스크립트가 하는 일:
#   1) Docker 설치(없으면)
#   2) OS 방화벽(iptables)에서 HOST_PORT 인바운드 허용
#   3) 이미지 빌드 후 컨테이너 실행(재시작 정책 포함)
# 주의: OCI '보안 목록(Security List)' 인바운드 규칙은 콘솔에서 따로 열어야 한다(아래 문서 참고).
set -euo pipefail

HOST_PORT="${HOST_PORT:-80}"
IMAGE="delaynomore"
NAME="delaynomore"

echo "==> [1/3] Docker 확인/설치"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo systemctl enable --now docker
fi

echo "==> [2/3] 방화벽(iptables)에서 ${HOST_PORT}/tcp 허용"
# OCI 우분투 이미지는 기본 iptables가 인바운드를 막는다. INPUT 체인에 규칙을 추가한다.
if sudo iptables -C INPUT -p tcp --dport "${HOST_PORT}" -j ACCEPT 2>/dev/null; then
  echo "    이미 허용됨"
else
  # REJECT 규칙 앞(2번째)에 삽입. netfilter-persistent가 있으면 저장.
  sudo iptables -I INPUT 1 -p tcp --dport "${HOST_PORT}" -j ACCEPT
  if command -v netfilter-persistent >/dev/null 2>&1; then
    sudo netfilter-persistent save || true
  fi
fi

echo "==> [3/3] 이미지 빌드 및 실행"
sudo docker build -t "${IMAGE}" .
sudo docker rm -f "${NAME}" 2>/dev/null || true
sudo docker run -d \
  --name "${NAME}" \
  --restart unless-stopped \
  -p "${HOST_PORT}:8080" \
  -e PORT=8080 \
  -e OPENROUTER_API_KEY="${OPENROUTER_API_KEY:-}" \
  -e OPENROUTER_MODEL="${OPENROUTER_MODEL:-}" \
  "${IMAGE}"

echo
echo "완료. 상태 확인:"
echo "  sudo docker ps"
echo "  curl -s http://localhost:${HOST_PORT}/api/ai/health"
echo "브라우저: http://<VM_PUBLIC_IP>:${HOST_PORT}"
