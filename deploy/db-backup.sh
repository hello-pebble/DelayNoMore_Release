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
echo "복원:  ./deploy/db-restore.sh ${OUT_FILE}"
