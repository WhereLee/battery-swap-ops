#!/usr/bin/env bash
# Daily backup: MySQL dump (gzip) + Redis RDB snapshot, 14-day retention.
# Requires backup.env (600) next to this script: MYSQL_USER/MYSQL_PASSWORD/MYSQL_DATABASE/REDIS_PASSWORD/BACKUP_DIR
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/backup.env"
TS=$(date +%Y%m%d_%H%M%S)
mkdir -p "$BACKUP_DIR/mysql" "$BACKUP_DIR/redis"

mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --single-transaction --routines --events \
  "$MYSQL_DATABASE" | gzip > "$BACKUP_DIR/mysql/${MYSQL_DATABASE}_${TS}.sql.gz"

redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --rdb "$BACKUP_DIR/redis/dump_${TS}.rdb" >/dev/null

find "$BACKUP_DIR/mysql" -name '*.sql.gz' -mtime +14 -delete
find "$BACKUP_DIR/redis" -name '*.rdb' -mtime +14 -delete

echo "[backup] $(date '+%F %T') mysql+redis done ts=$TS"
