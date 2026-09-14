#!/usr/bin/env bash
# Redeploy fixed server jar + rotate leaked SWAP_DEV_SECRET (leak surfaced by admin cabinet page, now masked).
set -e
ENV_FILE=/opt/swap/config/swap.env
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)

sudo systemctl stop swap-server swap-sim
sleep 3
sudo cp /tmp/swapdeploy/swap-server-1.0.0.jar /opt/swap/swap-server-1.0.0.jar
sudo chown ubuntu:ubuntu /opt/swap/swap-server-1.0.0.jar

NEW_SECRET=$(openssl rand -hex 16)
sudo sed -i "s/^SWAP_DEV_SECRET=.*/SWAP_DEV_SECRET=$NEW_SECRET/" "$ENV_FILE"
echo "secret-length=$(sudo grep '^SWAP_DEV_SECRET=' $ENV_FILE | cut -d= -f2 | wc -c)"

mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -e "UPDATE cabinet SET secret='$NEW_SECRET'" 2>/dev/null
echo "cabinets-updated=$(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*) FROM cabinet' 2>/dev/null)"

sudo systemctl start swap-server
sleep 15
sudo systemctl start swap-sim
sleep 15
echo "server=$(systemctl is-active swap-server) sim=$(systemctl is-active swap-sim)"
echo "server-health=$(curl -sf http://127.0.0.1:8400/api/actuator/health || echo FAIL)"
echo "sim-health=$(curl -sf http://127.0.0.1:8500/actuator/health || echo FAIL)"
ADMIN_TOKEN=$(grep '^SWAP_ADMIN_TOKEN=' "$ENV_FILE" | cut -d= -f2)
echo "=== admin cabinet page secret check (expect no 'secret' field) ==="
curl -sf "http://127.0.0.1:8400/api/admin/cabinet?page=1&limit=1" -H "X-Admin-Token: $ADMIN_TOKEN" | grep -c '"secret"' || echo "0 (masked)"
echo "=== heartbeat freshness after rotation ==="
sleep 10
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e \
  "SELECT COUNT(*) FROM cabinet WHERE last_heartbeat_time > (UNIX_TIMESTAMP()*1000 - 60000)" 2>/dev/null
