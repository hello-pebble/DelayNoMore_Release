# 운영 가이드 (Operations)

이 서비스를 실제로 운영할 때 필요한 것들을 정리한다. 기준 시점: v0.30.0.
배포 절차 자체는 [DEPLOY.md](DEPLOY.md) · [DEPLOY_OCI.md](DEPLOY_OCI.md)가 소유하고,
이 문서는 **배포 이후의 운영** — 감시·백업·비용·복구·루틴 — 을 다룬다.

## 1. 현재 갖춰진 것 vs 비어 있는 것

| 영역 | 이미 있음 | 비어 있음 |
| :--- | :--- | :--- |
| **배포** | GitHub Actions → ghcr.io 이미지 빌드(`image.yml`, `latest`·sha·semver 태그), VM은 `deploy/oci-pull.sh`로 pull만, `--restart unless-stopped` | 스테이징 환경, 롤백 절차 문서화(→ 4장) |
| **인프라** | OCI Always Free Ampere VM + Caddy(Let's Encrypt 자동 발급·갱신, `caddy_data` 볼륨 보존) + DuckDNS 도메인 | VM 1대 = 단일 장애점, VM 유실 시 재구축 런북(→ 4장), DuckDNS 갱신 관리 |
| **데이터** | Supabase PostgreSQL(`postgres` 프로필, Flyway 스키마) + `deploy/db-backup.sh`·`db-restore.sh`(pg_dump), 일일 백업 cron(`setup-backup-cron.sh`, 14일 보존), 오프사이트 복사 훅(`BACKUP_UPLOAD_CMD`) | 오프사이트 대상 선택·설정은 운영자 몫(훅만 제공), 복원 리허설(분기 1회 수동) |
| **관측** | `ai.usage` 토큰 사용량 로그([AGENT.md 6장](AGENT.md#6-관측--토큰-사용량-로그-v0152)), `ai.ratelimit` 차단 로그, `/api/v1/ai/health`(키·상한 소진 사유 포함) | 외부 업타임 감시·알림, 에러 알림, 로그 보존·로테이션 (Spring Actuator 미포함) |
| **비용 방어** | 계획 생성 게스트당 하루 5회 제한(v0.20.0), LLM 일일 상한 2층(소유자별·서버 전역, v0.30.0) | 상한 소진 시 알림 없음(로그 `ai.ratelimit blocked`·`/ai/health` 사유로만 확인), 상한값 튜닝은 실사용 관측 후 |
| **품질 게이트** | CI(`ci.yml`), 릴리스별 QA 체크리스트·QA 결과, EVAL 하네스([EVAL.md](EVAL.md)) | 배포 후 프로덕션 스모크 테스트 자동화 |
| **보안** | API 키 서버 전용(`~/.delaynomore.env`, chmod 600), HTTPS, `Cache-Control: no-store`, X-Guest-Id 소유자 격리 | 키 로테이션 절차, OS 자동 보안 패치, 엣지 레이트리밋 |

## 2. 운영 시작 전 필수 (P0)

### 2-1. 업타임 감시 + 알림

서버가 죽어도 지금은 아무도 모른다. 특히 챌린지 정산이 "목록 조회 시 lazy"라서
서버가 죽으면 정산까지 함께 멈춘다.

- **생존 감시(주 감시)**는 `GET /api/v1/challenges`(X-Guest-Id 헤더 필요)로 둔다 — 외부
  감시(UptimeRobot 등 무료 티어)에서 1~5분 주기, HTTP 200 실패 시 이메일/메신저 알림.
  이 URL은 업타임 확인이 **정산 트리거를 겸한다**(트래픽이 없어도 만기 챌린지가 정산됨).

  ```bash
  curl -s -H 'X-Guest-Id: uptime-probe-0001' https://delaynomoreapp.duckdns.org/api/v1/challenges
  ```

- **AI 상태 점검**은 별도 항목으로 둔다(생존 감시로 쓰지 않는다):

  ```bash
  curl -s https://delaynomoreapp.duckdns.org/api/v1/ai/health
  # → {"success":true,"data":{"connected":true,"toolCalling":true},...}
  # 한도 소진일: {"connected":false,"reason":"오늘 AI 사용량 한도 소진 (내일 자동 해제)"}
  ```

  v0.30.0부터 이 엔드포인트는 **일일 상한 소진도 `connected:false`로 알린다**(프론트가 그
  신호로 mock 폴백을 택하기 때문). 서버는 멀쩡한데 상한만 찬 날이 있으므로, 이걸 생존
  감시에 걸면 "서버 다운"으로 잘못 울린다 — `connected`가 아니라 `reason` 문자열로 구분하거나
  경보 등급을 낮춰 둔다.

### 2-2. 백업 자동화 (cron 적용 완료 · 오프사이트 훅 v0.30.0)

Supabase 자동 백업이 1차 보호막이고, `db-backup.sh`는 이식 가능한 오프사이트 사본이다.
VM에서 cron을 한 번 걸면 그다음부터는 사람 손이 필요 없다 — 남은 사람 몫은 **복원 리허설**뿐이다.

- VM에서 `deploy/setup-backup-cron.sh`를 한 번 실행하면 일 1회 cron이 등록된다
  (기본 매일 19:00 VM시 = UTC VM 기준 KST 새벽 4시, 14일 보존·자동 정리, 등록 직후 첫 백업 1회로 검증):

  ```bash
  ./deploy/setup-backup-cron.sh          # 등록(멱등 — 재실행하면 갱신) + 첫 백업 검증
  ./deploy/setup-backup-cron.sh remove   # 해제
  tail backups/backup.log                # 야간 실행 확인
  ```

  스케줄·보존 일수는 `BACKUP_CRON`·`RETENTION_DAYS` 환경변수로 조정한다(스크립트 머리 주석 참고).

- 덤프를 VM 밖(OCI Object Storage, 로컬 PC 등)으로 주기 복사 — VM과 백업이 같이 죽는 구성을 피한다.
  `BACKUP_UPLOAD_CMD`에 복사 명령을 주면 백업 직후 **덤프 경로를 마지막 인자로 붙여** 실행한다
  (미설정이면 지금까지와 동일 — 로컬에만 남는다). 업로드가 실패하면 스크립트가 비정상 종료해
  `backups/backup.log`에 남는다("조용한 성공"이 가장 위험한 실패라서):

  ```bash
  # 예: 래퍼 스크립트 하나로 감싸는 방식(자격증명은 CLI 설정·인스턴스 프린시펄에 둔다)
  cat > ~/upload-backup.sh <<'EOF'
  #!/usr/bin/env bash
  set -euo pipefail
  oci os object put --bucket-name dnm-backups --force --file "$1"
  EOF
  chmod +x ~/upload-backup.sh
  BACKUP_UPLOAD_CMD=~/upload-backup.sh ./deploy/setup-backup-cron.sh   # cron 항목에 함께 기록된다
  ```

  명령줄은 `ps`에 노출되므로 **비밀값을 이 변수에 넣지 않는다**(`%`는 cron 제약으로 금지 — 설치 시 거부).
- 분기 1회 `db-restore.sh`로 **복원 리허설**(별도 DB에). 복원해 본 적 없는 백업은 백업이 아니다.
- Supabase 무료 플랜은 장기간 미사용 시 프로젝트가 일시정지될 수 있다 — 대시보드 상태를 루틴에 포함.

### 2-3. LLM 비용 한도 (v0.30.0 적용)

대화·에이전트 경로에 **일일 상한 2층**이 걸려 있다. 층이 둘인 이유는 단순하다 —
게스트 ID는 브라우저가 만드는 값이라 새로 발급하면 소유자 카운터가 초기화된다.
소유자 한도는 "정상 사용자의 폭주"를 막고, **지갑을 실제로 지키는 것은 전역 상한**이다.

| 층 | 세는 단위 | 어디에서 | 기본값 | 넘으면 |
| :--- | :--- | :--- | :--- | :--- |
| 소유자별 | **요청 수**(대화 1번 = 1) | `AgentRunner`·슬랙 명령 진입점 | `AI_DAILY_CALLS_PER_OWNER=60` | 에이전트는 SSE `error` 이벤트 → 프론트 폴백 / 슬랙은 안내 답장 |
| 서버 전역 | **업스트림 호출 수**(도구 루프 내부 호출 포함 = 비용 단위) | `OpenRouterClient`(모든 경로가 지남) | `AI_DAILY_CALLS_GLOBAL=1500` | 비스트리밍 경로는 429 `AI_DAILY_LIMIT_EXCEEDED`, SSE는 `error` 이벤트, `/ai/health`는 `connected:false` |

- 리셋은 **KST 자정**(계획 생성 한도와 같은 기준). 카운터는 인메모리라 재기동하면 리셋된다 —
  단일 컨테이너 배포라 카운터가 갈라지지 않고, 스키마·DB 쓰기가 늘지 않는 쪽을 택했다.
- `0` 이하로 두면 그 층은 무제한(로컬·부하 테스트 탈출구).
- 상한이 차도 화면은 멈추지 않는다: `/ai/health`가 사유와 함께 `connected:false`를 내리므로
  프론트가 기존 폴백 체인을 타고 **mock 응답**으로 수렴한다("오늘은 AI가 없는 것과 같다").
- 여전히 필요한 것: OpenRouter 대시보드의 크레딧 한도·사용량 알림(코드 밖 마지막 방어선).
- 주기 점검:

  ```bash
  docker logs delaynomore 2>&1 | grep 'ai.usage site=agent.total'   # 실제 토큰 소비
  docker logs delaynomore 2>&1 | grep 'ai.ratelimit blocked'        # 상한에 막힌 호출
  curl -s https://delaynomoreapp.duckdns.org/api/v1/ai/health        # 지금 상한이 찼는지
  ```

  막힌 기록이 매일 쌓이면 상한을 올릴 게 아니라 **누가 왜 그렇게 부르는지**를 먼저 본다
  (`scope=global`만 차고 소유자별은 여유롭다면 게스트 ID를 갈아끼우는 사용 패턴이다).

### 2-4. 개인정보처리방침·이용약관

Google 로그인으로 이메일을 수집·저장하고 있다(v0.22.0). 실서비스라면 법적 의무.

- 개인정보처리방침 페이지 + 시작 화면에서 고지/동의.
- 탈퇴(계정·데이터 삭제) 경로 제공.
- 게스트 데이터의 한계(브라우저 데이터 삭제 시 재연결 불가)도 함께 고지.

### 2-5. 롤백 런북 (→ 4장)

## 3. 안정화 단계 (P1)

- **관측 강화** — Spring Actuator(health/metrics) 도입 검토, `ai.usage` 로그를 주기 집계해
  모델별 비용 리포트, docker 로그 로테이션(`--log-opt max-size=...`).
- **정산·포인트 무결성 점검** — 주 1회 점검 쿼리: 미정산·만기 초과 챌린지 수, 포인트
  원장 합계 검증(참가비 차감 총액 = 분배 + 환불 + 미정산 풀). lazy 정산이라 "조회가 없으면
  정산도 없다"는 특성을 감시로 보완한다(2-1의 겸용 트릭).
- **운영자 도구** — 문의 대응용 최소 조회 수단(게스트 ID/이메일로 계획·포인트·챌린지 조회).
  게스트 ID 유실 문의는 현재 구조상 복구 불가 — 안내 문구를 미리 정해 둔다.
- **엣지 방어** — Caddy 요청 속도·크기 제한, OS 자동 보안 패치(`unattended-upgrades`).

## 4. 장애 복구 런북

### 앱 롤백 (이전 버전 이미지로)

이미지에 semver 태그가 붙으므로 pull 스크립트에 태그만 지정하면 된다:

```bash
IMAGE=ghcr.io/hello-pebble/delaynomore_release:0.24.0 ./deploy/oci-pull.sh
```

**주의**: Flyway 마이그레이션(V8 등)이 포함된 릴리스는 DB 스키마를 되돌릴 수 없다.
릴리스 노트에 "코드만 롤백 가능한지"(스키마 변경 유무)를 기록해 두고, 스키마가 바뀐
릴리스의 롤백은 이전 코드가 새 스키마와 호환되는지 먼저 확인한다.

### VM 유실 시 재구축

1. OCI 콘솔에서 Ampere VM 재생성 + 보안 목록 80/443 개방([DEPLOY_OCI.md](DEPLOY_OCI.md) 1~2장).
2. `~/.delaynomore.env` 복원(키·DB 접속 정보 — 백업해 둔 사본에서. 없으면 OpenRouter 키 재발급 + Supabase 대시보드에서 접속 정보 재확인).
3. DuckDNS의 도메인 IP를 새 VM 퍼블릭 IP로 갱신.
4. `git clone` 후 `./deploy/oci-pull.sh` — 인증서는 새로 발급된다(수십 초).
5. 데이터는 Supabase에 있으므로 앱 재기동만으로 복원. DB 자체가 유실됐다면 `db-restore.sh`로 최신 덤프 복원.

### OpenRouter 장애

키 미설정/호출 실패 시 mock 폴백으로 동작하도록 이미 설계돼 있다(서비스 전체 중단은 아님).
모델 교체로 대응할 때는 `OPENROUTER_MODEL` 변경 후 재배포하되, 도구 미지원 모델이면
`OPENROUTER_TOOL_CALLING=false`([DEPLOY.md](DEPLOY.md) 환경변수 표). 모델 변경은
EVAL 하네스로 실측 후가 원칙([EVAL.md](EVAL.md)).

## 5. 성장 단계 (P2)

- 스테이징 환경(별도 컨테이너 + Supabase 브랜치/별도 프로젝트)에서 Flyway 마이그레이션 사전 검증
- 배포 후 스모크 테스트 자동화(QA 체크리스트 중 curl 가능한 항목을 스크립트로)
- Supabase 유료 플랜 검토(PITR, 일시정지 없음), OCI 외 대체 호스팅 시나리오
- 키 로테이션 주기화(OpenRouter · Google OAuth · DB 비밀번호 · Slack 봇 토큰·서명 시크릿)

## 6. 운영 루틴 요약

| 주기 | 할 일 |
| :--- | :--- |
| 상시(자동) | 업타임 감시·알림(정산 트리거 겸용), 일일 DB 백업 cron(+오프사이트 복사), LLM 일일 상한, OpenRouter 비용 알림 |
| 주 1회 | 정산·포인트 원장 점검, `ai.usage` 비용 집계 + `ai.ratelimit blocked` 확인, 슬랙 발송 실패 확인(`docker logs`에서 `slack dispatch failed`·`slack postMessage failed`), 디스크·로그·Supabase 상태 확인 |
| 릴리스마다 | (기존 관례) QA 체크리스트 + README 변경 표, 배포 후 health 확인, 스키마 변경 유무(롤백 가능 여부) 기록 |
| 분기 1회 | 복원 리허설, 키 로테이션, OS 패치 상태 점검 |

관련 문서: [실행·배포](DEPLOY.md) · [OCI 배포](DEPLOY_OCI.md) · [동시성(정산 불변식)](CONCURRENCY.md) · [QA 체크리스트](QA_CHECKLIST.md)
