#!/usr/bin/env bash
# PostgreSQL(Supabase) 논리 복원 — db-backup.sh가 만든 custom 포맷(.dump)을 되돌린다.
#
# 사용법:
#   DATABASE_URL='postgresql://postgres.<ref>:<PW>@<host>:5432/postgres?sslmode=require' \
#     ./deploy/db-restore.sh backups/delaynomore-YYYYMMDD-HHMMSS.dump
#   # 또는 ~/.delaynomore.env 의 DB_* 로부터 자동 조립:
#   ./deploy/db-restore.sh backups/delaynomore-YYYYMMDD-HHMMSS.dump
# 인자:
#   $1           : 복원할 .dump 파일 경로(필수)
#   DATABASE_URL : (선택) libpq 접속 URL. 없으면 env 파일 DB_*에서 조립.
#   ENV_FILE     : (선택) env 파일 경로(기본 ~/.delaynomore.env)
#
# 주의:
#   - 복원은 앱 컨테이너를 멈춘 상태에서 하는 것이 안전하다(동시 쓰기 방지):
#       sudo docker stop delaynomore && ./deploy/db-restore.sh <dump> && sudo docker start delaynomore
#   - --clean --if-exists: 기존 객체를 지우고 되살린다. flyway_schema_history도 덤프에 포함되므로
#     스키마 버전 상태까지 재현되고, 이후 앱 기동 시 Flyway는 no-op이 된다.
set -euo pipefail

DUMP_FILE="${1:-}"
if [ -z "${DUMP_FILE}" ] || [ ! -f "${DUMP_FILE}" ]; then
  echo "오류: 복원할 .dump 파일 경로를 첫 번째 인자로 주세요." >&2
  exit 1
fi

ENV_FILE="${ENV_FILE:-${HOME}/.delaynomore.env}"
if [ -z "${DATABASE_URL:-}" ]; then
  if [ -f "${ENV_FILE}" ]; then
    set -a; # shellcheck disable=SC1090
    . "${ENV_FILE}"; set +a
  fi
  if [ -z "${DB_URL:-}" ] || [ -z "${DB_USERNAME:-}" ] || [ -z "${DB_PASSWORD:-}" ]; then
    echo "오류: DATABASE_URL 또는 (DB_URL·DB_USERNAME·DB_PASSWORD)를 제공하세요." >&2
    exit 1
  fi
  _hostpart="${DB_URL#jdbc:postgresql://}"
  DATABASE_URL="postgresql://${DB_USERNAME}:${DB_PASSWORD}@${_hostpart}"
fi

echo "==> pg_restore ← ${DUMP_FILE}"
echo "    대상: ${DATABASE_URL%%\?*}"
# --no-owner/--no-privileges: 복원지 역할 차이에 견고. --clean --if-exists: 재복원 안전.
pg_restore --clean --if-exists --no-owner --no-privileges -d "${DATABASE_URL}" "${DUMP_FILE}"

echo "완료. 앱을 다시 기동하세요(예: sudo docker start delaynomore)."
