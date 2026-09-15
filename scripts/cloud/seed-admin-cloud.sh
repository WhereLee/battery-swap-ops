#!/usr/bin/env bash
# Seed admin account on cloud using the NEW jar (already deployed) + env bootstrap password.
set -e
ENV_FILE=/opt/swap/config/swap.env
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)

sudo systemctl stop swap-server swap-sim
sleep 2
cd /opt/swap
set -a
. "$ENV_FILE"
set +a
timeout 70 java -Xms256m -Xmx768m -jar /opt/swap/swap-server-1.0.0.jar \
    --swap.dev.enabled=true > /opt/swap/logs/seed-wpa2.log 2>&1 || true
echo "admin rows: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*),MAX(username) FROM admin_user')"
grep -c "管理员种子就绪" /opt/swap/logs/seed-wpa2.log || true

sudo systemctl start swap-server
sleep 15
sudo systemctl start swap-sim
sleep 12
echo "server=$(systemctl is-active swap-server) sim=$(systemctl is-active swap-sim)"
echo "server-health=$(curl -sf http://127.0.0.1:8400/api/actuator/health || echo FAIL)"

echo "== RBAC smoke =="
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
