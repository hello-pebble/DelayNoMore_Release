#!/usr/bin/env bash
# PostgreSQL(Supabase) 논리 백업 — pg_dump custom 포맷(.dump)으로 한 파일에 담는다.
# Supabase 대시보드의 자동 백업(플랜별 일일/PITR)이 1차 보호막이고, 이 스크립트는 이식 가능한
# 오프사이트 백업(다른 곳으로 옮기거나 스테이징에 복원)을 위한 보조 수단이다.
#
# 사용법:
#   DATABASE_URL='postgresql://postgres.<ref>:<PW>@<host>:5432/postgres?sslmode=require' ./deploy/db-backup.sh
#   # 또는 ~/.delaynomore.env 의 DB_* 로부터 자동 조립(아래):
#   ./deploy/db-backup.sh
# 인자(환경변수):
#   DATABASE_URL : (선택) libpq 접속 URL. 지정하면 그대로 쓴다.
#   ENV_FILE     : (선택) DB_URL/DB_USERNAME/DB_PASSWORD 로부터 URL을 조립할 env 파일(기본 ~/.delaynomore.env)
#   OUT_DIR      : (선택) 덤프 저장 폴더(기본 ./backups)
#   BACKUP_UPLOAD_CMD : (선택) 덤프를 VM 밖으로 복사하는 명령. 덤프 경로가 마지막 인자로 붙어
#                    실행된다(sh -c). 예)
#                      BACKUP_UPLOAD_CMD='oci os object put --bucket-name dnm-backups --file'
#                      BACKUP_UPLOAD_CMD='rclone copy --config /home/ubuntu/rclone.conf'   # → rclone copy <dump> 는 대상이 필요하므로 래퍼 스크립트 권장
#                      BACKUP_UPLOAD_CMD='/home/ubuntu/upload-backup.sh'
#                    자격증명은 이 변수가 아니라 해당 CLI의 설정 파일·인스턴스 프린시펄에 둔다
#                    (명령줄은 ps에 노출된다 — 비밀값 금지).
#
# 주의: pg_dump 클라이언트 버전은 서버(PG17)와 맞추는 것이 안전하다(예: postgresql-client-17).
set -euo pipefail

OUT_DIR="${OUT_DIR:-backups}"
ENV_FILE="${ENV_FILE:-${HOME}/.delaynomore.env}"

# DATABASE_URL이 없으면 env 파일의 DB_*(JDBC URL)에서 libpq URL을 조립한다.
if [ -z "${DATABASE_URL:-}" ]; then
  if [ -f "${ENV_FILE}" ]; then
    set -a; # shellcheck disable=SC1090
    . "${ENV_FILE}"; set +a
  fi
  if [ -z "${DB_URL:-}" ] || [ -z "${DB_USERNAME:-}" ] || [ -z "${DB_PASSWORD:-}" ]; then
    echo "오류: DATABASE_URL 또는 (DB_URL·DB_USERNAME·DB_PASSWORD)를 제공하세요." >&2
    exit 1
  fi
  # jdbc:postgresql://host:port/db?params → postgresql://user:pass@host:port/db?params
  _hostpart="${DB_URL#jdbc:postgresql://}"
  DATABASE_URL="postgresql://${DB_USERNAME}:${DB_PASSWORD}@${_hostpart}"
fi

mkdir -p "${OUT_DIR}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="${OUT_DIR}/delaynomore-${TS}.dump"

echo "==> pg_dump → ${OUT_FILE}"
# -Fc: custom 포맷(pg_restore 대상). --no-owner/--no-privileges: 복원지 역할·권한 차이에 견고.
pg_dump -Fc --no-owner --no-privileges -f "${OUT_FILE}" "${DATABASE_URL}"

echo "완료: ${OUT_FILE} ($(du -h "${OUT_FILE}" | cut -f1))"

# 오프사이트 복사(선택) — VM이 통째로 사라지면 로컬 덤프도 함께 사라지므로, 밖으로 옮기는
# 수단은 각자 다르다(OCI Object Storage·S3·rclone·scp). 여기서는 명령을 주입받기만 한다:
# 업로드 도구·자격증명을 스크립트가 알 필요가 없고, 미설정이면 지금까지와 똑같이 동작한다.
if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
  echo "==> 오프사이트 복사: ${BACKUP_UPLOAD_CMD} ${OUT_FILE}"
  # 실패해도 로컬 덤프는 이미 남아 있다. 그래도 비정상 종료로 알린다 — 조용히 성공하면
  # "오프사이트 백업이 있다"는 믿음만 남고 실제로는 없는 상태가 된다(cron 로그에 남는다).
  if ! sh -c "${BACKUP_UPLOAD_CMD} \"\$1\"" _ "${OUT_FILE}"; then
    echo "오류: 오프사이트 복사 실패 — 로컬 덤프(${OUT_FILE})는 남아 있습니다." >&2
    exit 1
  fi
  echo "오프사이트 복사 완료"
fi

echo "복원:  ./deploy/db-restore.sh ${OUT_FILE}"
