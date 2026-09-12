#!/usr/bin/env bash
# db-backup.sh를 매일 자동 실행하는 cron을 등록한다 — VM에서 1회 실행(멱등: 재실행 시 갱신).
# 운영 가이드 P0 "백업 자동화"의 구현체다([docs/OPERATIONS.md] 2-2장).
#
# 사용법 (VM에서, 저장소 어디서든):
#   ./deploy/setup-backup-cron.sh            # cron 등록(기본). 재실행하면 기존 항목을 교체
#   ./deploy/setup-backup-cron.sh run        # 백업 1회 실행 + 보존 정리 (cron이 부르는 진입점)
#   ./deploy/setup-backup-cron.sh remove     # cron 항목 제거
# 인자(환경변수):
#   BACKUP_CRON    : (선택) cron 스케줄. 기본 '0 19 * * *' — cron은 **VM 시간대** 기준이라
#                    UTC VM(우분투 기본)에서 19:00 = KST 새벽 4시. `timedatectl`로 확인.
#   RETENTION_DAYS : (선택) 덤프 보존 일수(기본 14). 지나면 run 단계에서 삭제.
#   OUT_DIR        : (선택) 덤프 저장 폴더(기본 <저장소>/backups). 로그도 여기에 쌓인다.
#   ENV_FILE       : (선택) DB_* env 파일(기본 ~/.delaynomore.env) — db-backup.sh로 전달.
#   BACKUP_UPLOAD_CMD : (선택) 덤프를 VM 밖으로 복사하는 명령(db-backup.sh로 전달 — 덤프 경로가
#                    마지막 인자로 붙는다). install 시점의 값이 cron 항목에 그대로 기록되므로,
#                    바꾸려면 같은 변수로 다시 install 하면 된다. 예)
#                      BACKUP_UPLOAD_CMD=/home/ubuntu/upload-backup.sh ./deploy/setup-backup-cron.sh
#
# 주의: cron은 이 스크립트의 **절대 경로**를 기록하므로 저장소를 옮기면 재등록해야 한다.
#       백업은 VM 로컬에 남는다 — VM과 함께 잃지 않으려면 덤프를 주기적으로 VM 밖으로
#       복사한다(OPERATIONS.md 2-2). 복원 리허설: ./deploy/db-restore.sh <dump>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "${SCRIPT_DIR}")"
MARKER="# delaynomore-db-backup"
BACKUP_CRON="${BACKUP_CRON:-0 19 * * *}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
OUT_DIR="${OUT_DIR:-${REPO_DIR}/backups}"
ENV_FILE="${ENV_FILE:-${HOME}/.delaynomore.env}"
LOG_FILE="${OUT_DIR}/backup.log"

case "${1:-install}" in
  run)
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 백업 시작"
    OUT_DIR="${OUT_DIR}" ENV_FILE="${ENV_FILE}" BACKUP_UPLOAD_CMD="${BACKUP_UPLOAD_CMD:-}" \
      "${SCRIPT_DIR}/db-backup.sh"
    find "${OUT_DIR}" -name 'delaynomore-*.dump' -mtime +"${RETENTION_DAYS}" -delete
    KEPT="$(find "${OUT_DIR}" -name 'delaynomore-*.dump' | wc -l)"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 백업 끝 — 보존 ${RETENTION_DAYS}일, 현재 ${KEPT}개 보관"
    ;;

  remove)
    # grep은 남는 줄이 없으면 exit 1 — pipefail로 스크립트가 죽지 않게 || true
    { crontab -l 2>/dev/null | grep -vF "${MARKER}" || true; } | crontab -
    echo "==> cron 항목 제거됨 (${MARKER})"
    ;;

  install)
    # 사전 점검 1: pg_dump — 없으면 cron이 매일 밤 조용히 실패한다.
    if ! command -v pg_dump >/dev/null 2>&1; then
      echo "오류: pg_dump가 없습니다. 먼저 설치하세요:" >&2
      echo "  sudo apt-get update && sudo apt-get install -y postgresql-client" >&2
      echo "  (서버 PG17과 버전을 맞추려면 PGDG 저장소의 postgresql-client-17 권장 — db-backup.sh 주석 참고)" >&2
      exit 1
    fi
    # 사전 점검 2: DB 접속 정보 — 인메모리 모드(DB_URL 없음)면 백업할 DB가 없다.
    if [ -z "${DATABASE_URL:-}" ] && ! { [ -f "${ENV_FILE}" ] && grep -q '^DB_URL=' "${ENV_FILE}"; }; then
      echo "오류: ${ENV_FILE}에 DB_URL이 없습니다. 인메모리 모드면 백업 대상 DB가 없고," >&2
      echo "      영속 모드라면 DB_URL·DB_USERNAME·DB_PASSWORD를 env 파일에 채우세요(DEPLOY_OCI.md)." >&2
      exit 1
    fi

    # 오프사이트 명령에 %가 있으면 cron이 개행으로 바꿔 항목이 깨진다 — 래퍼 스크립트로 우회.
    if [ -n "${BACKUP_UPLOAD_CMD:-}" ] && [ "${BACKUP_UPLOAD_CMD}" != "${BACKUP_UPLOAD_CMD//%/}" ]; then
      echo "오류: BACKUP_UPLOAD_CMD에 %를 쓸 수 없습니다(cron이 개행으로 해석)." >&2
      echo "      그 명령을 셸 스크립트로 감싸고 그 경로를 BACKUP_UPLOAD_CMD로 주세요." >&2
      exit 1
    fi

    mkdir -p "${OUT_DIR}"
    # cron은 환경을 물려받지 않는다 — 필요한 변수는 항목에 직접 적는다.
    UPLOAD_ASSIGN=""
    if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
      UPLOAD_ASSIGN="BACKUP_UPLOAD_CMD='${BACKUP_UPLOAD_CMD}' "
    fi
    ENTRY="${BACKUP_CRON} ENV_FILE=${ENV_FILE} OUT_DIR=${OUT_DIR} RETENTION_DAYS=${RETENTION_DAYS} ${UPLOAD_ASSIGN}${SCRIPT_DIR}/setup-backup-cron.sh run >> ${LOG_FILE} 2>&1 ${MARKER}"
    # crontab이 아직 없거나 기존 항목이 없어도(grep exit 1) 등록이 이어지게 || true
    { crontab -l 2>/dev/null | grep -vF "${MARKER}" || true; echo "${ENTRY}"; } | crontab -

    echo "==> cron 등록됨: ${BACKUP_CRON} (VM 시간대: $(date '+%Z %z'))"
    echo "    로그: ${LOG_FILE} · 보존: ${RETENTION_DAYS}일 · 확인: crontab -l"
    if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
      echo "    오프사이트: ${BACKUP_UPLOAD_CMD} <dump>"
    else
      echo "    오프사이트 복사 없음 — 백업이 VM에만 남습니다(BACKUP_UPLOAD_CMD로 켜세요)."
    fi
    echo "==> 첫 백업을 지금 검증합니다..."
    "${SCRIPT_DIR}/setup-backup-cron.sh" run | tee -a "${LOG_FILE}"
    ;;

  *)
    echo "사용법: $0 [install|run|remove]" >&2
    exit 1
    ;;
esac
