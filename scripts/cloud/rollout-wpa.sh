#!/usr/bin/env bash
# S7 WP-A cloud rollout: snapshot -> db/11 -> seed admin (one-shot dev) -> new jar -> restart -> smoke
set -e
ENV_FILE=/opt/swap/config/swap.env
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)

echo "== step0: pre-migration snapshot (daily backup script) =="
sudo /opt/swap/config/backup.sh | tail -1

echo "== step1: apply db/11 =="
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < /tmp/swapdeploy/11-admin-rbac.sql
echo "admin_user table: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e "SHOW TABLES LIKE 'admin_user'")"

echo "== step2: bootstrap password env =="
if ! grep -q '^SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=' "$ENV_FILE"; then
  NEW_PASS=$(openssl rand -hex 12)
  echo "SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=$NEW_PASS" | sudo tee -a "$ENV_FILE" > /dev/null
  echo "appended bootstrap password (24hex, 600)"
else
  echo "bootstrap password already present, keep"
fi

echo "== step3: one-shot dev seed for admin account =="
sudo systemctl stop swap-server swap-sim
sleep 2
cd /opt/swap
set -a
. "$ENV_FILE"
set +a
timeout 70 java -Xms256m -Xmx768m -jar /opt/swap/swap-server-1.0.0.jar \
    --swap.dev.enabled=true > /opt/swap/logs/seed-wpa.log 2>&1 || true
echo "admin rows: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*) FROM admin_user')"

echo "== step4: deploy new jar =="
sudo cp /tmp/swapdeploy/swap-server-1.0.0.jar /opt/swap/swap-server-1.0.0.jar
sudo chown ubuntu:ubuntu /opt/swap/swap-server-1.0.0.jar
sudo systemctl start swap-server
sleep 15
sudo systemctl start swap-sim
sleep 12
echo "server=$(systemctl is-active swap-server) sim=$(systemctl is-active swap-sim)"
echo "server-health=$(curl -sf http://127.0.0.1:8400/api/actuator/health || echo FAIL)"

echo "== step5: RBAC smoke (localhost) =="
echo "unauth-code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8400/api/admin/account)"
ADMIN_PASS=$(grep '^SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
TOKEN=$(curl -sf -X POST http://127.0.0.1:8400/api/admin/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "admin-login-token-length=${#TOKEN}"
echo "session-account-code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8400/api/admin/account -H "X-Admin-Token: $TOKEN")"
STATIC=$(grep '^SWAP_ADMIN_TOKEN=' "$ENV_FILE" | cut -d= -f2)
echo "breakglass-dashboard-code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8400/api/admin/dashboard/overview -H "X-Admin-Token: $STATIC")"
echo "== done =="
