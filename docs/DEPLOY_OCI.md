# Oracle Cloud(OCI) 배포 가이드 — Always Free

단일 컨테이너(Spring Boot가 프론트+API 서빙)를 OCI **Always Free** 자원으로 배포한다.
권장 구성: **Ampere A1 Compute VM(ARM) + Docker**. (이미지 베이스가 모두 arm64 멀티아치라 그대로 동작)

> AMD 마이크로(x86) Always Free는 RAM이 1GB뿐이라 JVM 빌드/구동이 빠듯하다. **Ampere A1**(최대 4 OCPU / 24GB, 예: 1 OCPU/6GB만 써도 충분)을 권장한다.

## 1. VM 생성 (OCI 콘솔)

1. **Compute → Instances → Create instance**
2. **Image**: Canonical Ubuntu 22.04 (또는 24.04)
3. **Shape**: `VM.Standard.A1.Flex` (Ampere) → 예: **1 OCPU / 6 GB** (Always Free 한도 내)
   - Ampere 용량 부족(Out of capacity) 에러가 나면 다른 가용 도메인/리전으로 재시도.
4. **Networking**: 퍼블릭 IP **할당**
5. **SSH key**: 본인 공개키 등록 → 생성
6. 생성 후 **퍼블릭 IP** 확인

## 2. 인바운드 포트 열기 (2군데 모두 필요)

OCI는 **① 보안 목록(클라우드)** 과 **② 인스턴스 방화벽(OS)** 두 겹을 막는다.

### ① 보안 목록 (콘솔)
- **Networking → Virtual Cloud Networks → (VCN) → Security Lists → Default Security List**
- **Add Ingress Rules**:
  - Source CIDR: `0.0.0.0/0`
  - IP Protocol: `TCP`
  - Destination Port Range: `80` (HTTP 기본값)
  - **HTTPS를 쓸 거면 `443` 도 함께 추가** (아래 6번 참고).

### ② OS 방화벽
- 아래 `oci-setup.sh`가 iptables 규칙을 자동으로 추가한다.

## 3. 배포 (VM에서 SSH 접속 후)

**권장: pull 방식** — GitHub Actions가 이미지를 빌드해 `ghcr.io`에 올리므로, VM은 빌드 없이 이미지를 받기만 한다. 낮은 사양 VM(예: 1GB Micro)에서 gradle/npm 빌드로 마비되는 것을 피한다.

> 사전 준비(최초 1회): GitHub 저장소에서 이미지가 한 번 이상 빌드되어 있어야 한다.
> `main`에 푸시하면 `.github/workflows/image.yml`가 자동 실행되어 이미지를 만든다.
> 그리고 **패키지를 public으로 공개**해야 VM이 인증 없이 pull 할 수 있다
> (GitHub → 저장소 → Packages → 해당 패키지 → Package settings → Change visibility → Public).
> private로 유지하려면 VM에서 `echo <PAT> | sudo docker login ghcr.io -u <GH_USER> --password-stdin` 로 로그인한다.

```bash
# git 설치(없으면) 후 저장소 클론 (스크립트만 있으면 되므로 얕은 클론도 가능)
sudo apt-get update && sudo apt-get install -y git
git clone https://github.com/hello-pebble/DelayNoMore_Release.git
cd DelayNoMore_Release

# (최초 1회) API 키를 env 파일로 저장 — 이후 배포부터는 키 입력이 필요 없다
cat > ~/.delaynomore.env <<'EOF'
OPENROUTER_API_KEY=sk-or-여기에_실제_키
OPENROUTER_MODEL=qwen/qwen3.7-plus
EOF
chmod 600 ~/.delaynomore.env

# 이미지 받아서 실행 (Docker 설치 + 방화벽 + pull + 실행) — env 파일을 자동으로 읽는다
./deploy/oci-pull.sh
```

> **키 보안**: env 파일은 `chmod 600`(본인만 읽기)으로 두고, **절대 git에 커밋하지 않는다**.
> 명령줄에 키를 직접 치면 셸 히스토리(`~/.bash_history`)에 남으므로 env 파일 방식을 권장한다.

- pull 방식은 이미지 다운로드만 하므로 수십 초 내에 끝나고 메모리를 거의 쓰지 않는다.
- 다른 포트로 노출하려면: `HOST_PORT=8080 ./deploy/oci-pull.sh` (보안 목록도 해당 포트로)

<details>
<summary>대안: VM에서 직접 빌드 (<code>oci-setup.sh</code>)</summary>

레지스트리를 쓰지 않고 VM에서 소스로 이미지를 빌드한다. **RAM이 넉넉한 shape(Ampere 6GB 등)에서만 권장** — 1GB Micro에서는 빌드가 메모리 부족으로 실패/마비될 수 있다.

```bash
./deploy/oci-setup.sh   # Docker 설치 + 방화벽 + npm/gradle 빌드 + 실행
```
</details>

## 4. 확인

```bash
sudo docker ps
curl -s http://localhost/api/ai/health         # {"success":...}
```
브라우저에서 **http://<VM_PUBLIC_IP>** 접속.

## 5. 운영 팁

- **업데이트 배포(pull 방식)**: `main`에 변경이 머지되면 GitHub Actions가 새 이미지를 올린다. VM에서는 `./deploy/oci-pull.sh` 만 다시 실행하면 최신 이미지를 받아 컨테이너를 교체한다(빌드 없음). 키는 `~/.delaynomore.env`에서 자동 로드된다.
- **로그**: `sudo docker logs -f delaynomore`
- **재부팅 후 자동 기동**: `--restart unless-stopped`로 이미 처리됨.
- **메모리**(선택): 6GB면 여유롭지만, 더 작은 shape면 `-e JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` 추가.

## 6. HTTPS 적용 (도메인 + 인증서 + 자동 리다이렉트)

공개 운영 전에는 HTTPS가 필요하다. `oci-pull.sh`는 **`DOMAIN` 환경변수만 주면** 앞단에
[Caddy](https://caddyserver.com) 리버스 프록시를 자동 구성한다. Caddy가 하는 일:

- **Let's Encrypt 인증서 자동 발급/갱신** (별도 certbot·크론 불필요)
- **HTTP(80) → HTTPS(443) 자동 리다이렉트**
- HTTPS를 종단한 뒤 뒤단 앱(평문 `127.0.0.1:8080`)으로 프록시. 앱은 공개 포트에 직접 노출되지 않는다.

### 사전 준비
1. **도메인**을 하나 준비하고, 그 도메인의 **A 레코드(있으면 AAAA도)** 를 이 VM의 **퍼블릭 IP**로 설정.
   DNS가 전파되어 `dig +short todo.example.com` 이 VM IP를 돌려줘야 인증서 발급이 성공한다.
2. **OCI 보안 목록**에 인바운드 **80** 과 **443** 을 모두 추가(위 2번). (80은 ACME 챌린지·리다이렉트용)

### 실행
```bash
# 도메인만 넘기면 끝 — 앱 컨테이너를 로컬 바인딩으로 재기동하고 Caddy를 띄운다.
DOMAIN=todo.example.com ./deploy/oci-pull.sh

# 상시로 쓰려면 env 파일에 넣어두면 이후 배포에도 자동 적용된다.
echo 'DOMAIN=todo.example.com' >> ~/.delaynomore.env
```

### 확인
```bash
sudo docker ps                              # delaynomore + delaynomore-caddy 두 컨테이너
sudo docker logs -f delaynomore-caddy       # "certificate obtained successfully" 확인
curl -sI http://todo.example.com            # 301/308 → https 리다이렉트
curl -s  https://todo.example.com/api/ai/health
```
브라우저에서 **https://todo.example.com** 접속(자물쇠 표시). `http://` 로 들어와도 자동으로 `https://` 로 전환된다.

> - 인증서/설정은 Docker named volume(`caddy_data`,`caddy_config`)에 저장돼 재시작해도 재발급하지 않는다(레이트리밋 회피).
> - 만료 알림 메일을 받고 싶으면 `deploy/Caddyfile`의 전역 `email` 블록 주석을 풀고 주소를 채운다(선택).
> - DNS가 아직 VM을 안 가리키면 발급이 실패하며 Caddy 로그에 사유가 남는다 — DNS 전파 후 `sudo docker restart delaynomore-caddy`.

## (대안) OCI Container Instances
VM 관리 없이 이미지만 올려 실행하는 관리형 옵션. 단, **Always Free 대상이 아님**(사용량 과금).
이미지를 **OCIR**(Oracle Container Registry)에 푸시한 뒤 Container Instance로 실행한다. 상시 무료가 목적이면 위의 Ampere VM을 권장.
