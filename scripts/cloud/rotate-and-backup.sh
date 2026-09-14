#!/usr/bin/env bash
# Cloud ops round 2 (2026-09-14): rotate Redis password (old one exposed in transcript),
# install daily backup (minimal-privilege account + script + cron), run first backup + restore drill.
set -e
ENV_FILE=/opt/swap/config/swap.env
BACKUP_DIR=/opt/swap/backup

echo "== step1: rotate redis password =="
NEW_REDIS_PASS=$(openssl rand -hex 24)
sudo sed -i "s/^requirepass .*/requirepass $NEW_REDIS_PASS/" /etc/redis/redis.conf
sudo systemctl restart redis-server
sleep 3
redis-cli -a "$NEW_REDIS_PASS" --no-auth-warning ping
sudo sed -i "s/^SPRING_DATA_REDIS_PASSWORD=.*/SPRING_DATA_REDIS_PASSWORD=$NEW_REDIS_PASS/" "$ENV_FILE"
echo "redis-password-rotated"
sudo systemctl restart swap-server
sleep 15
sudo systemctl restart swap-sim
sleep 10
echo "server=$(systemctl is-active swap-server) sim=$(systemctl is-active swap-sim)"
echo "server-health=$(curl -sf http://127.0.0.1:8400/api/actuator/health || echo FAIL)"

echo "== step2: backup account (minimal privileges) =="
BACKUP_PASS=$(openssl rand -hex 16)
sudo mysql <<EOF
CREATE USER IF NOT EXISTS 'swap_backup'@'localhost' IDENTIFIED BY '$BACKUP_PASS';
ALTER USER 'swap_backup'@'localhost' IDENTIFIED BY '$BACKUP_PASS';
GRANT SELECT, SHOW VIEW, TRIGGER, LOCK TABLES, EVENT, PROCESS, RELOAD ON *.* TO 'swap_backup'@'localhost';
FLUSH PRIVILEGES;
EOF

sudo mkdir -p "$BACKUP_DIR/mysql" "$BACKUP_DIR/redis"
sudo tee /opt/swap/config/backup.env > /dev/null <<EOF
export MYSQL_USER=swap_backup
export MYSQL_PASSWORD=$BACKUP_PASS
export MYSQL_DATABASE=swap_ops
export REDIS_PASSWORD=$NEW_REDIS_PASS
export BACKUP_DIR=/opt/swap/backup
EOF
sudo chmod 600 /opt/swap/config/backup.env
sudo chown ubuntu:ubuntu /opt/swap/config/backup.env
sudo cp /tmp/swapdeploy/backup.sh /opt/swap/config/backup.sh
sudo chmod 700 /opt/swap/config/backup.sh
sudo chown ubuntu:ubuntu /opt/swap/config/backup.sh

echo "== step3: cron (daily 02:30) =="
( sudo crontab -l 2>/dev/null | grep -v '/opt/swap/config/backup.sh' || true; \
  echo '30 2 * * * /opt/swap/config/backup.sh >> /opt/swap/logs/backup.log 2>&1' ) | sudo crontab -
sudo crontab -l | tail -1

echo "== step4: first backup + artifact checks =="
sudo /opt/swap/config/backup.sh
ls -la "$BACKUP_DIR/mysql" "$BACKUP_DIR/redis" | tail -6
gzip -t "$BACKUP_DIR"/mysql/*.sql.gz && echo "gzip-ok"

echo "== step5: restore dry-run (temp db, source untouched) =="
LATEST=$(ls -t "$BACKUP_DIR"/mysql/*.sql.gz | head -1)
sudo mysql -e "DROP DATABASE IF EXISTS swap_ops_restore_check; CREATE DATABASE swap_ops_restore_check"
zcat "$LATEST" | sudo mysql swap_ops_restore_check
echo "restore-cabinets=$(sudo mysql -N -e 'SELECT COUNT(*) FROM swap_ops_restore_check.cabinet')"
echo "restore-orders=$(sudo mysql -N -e 'SELECT COUNT(*) FROM swap_ops_restore_check.swap_order')"
echo "restore-cells=$(sudo mysql -N -e 'SELECT COUNT(*) FROM swap_ops_restore_check.cell')"
sudo mysql -e "DROP DATABASE swap_ops_restore_check"
echo "restore-drill-done"

echo "== step6: cleanup staging (contains legacy secret file) =="
rm -rf /tmp/swapdeploy
ls /tmp/swapdeploy 2>/dev/null || echo "staging-removed"
