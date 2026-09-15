#!/usr/bin/env bash
# S7 WP-C cloud rollout: snapshot -> db/13 -> new jar -> restart -> recon smoke (import fake bill + report + ignore diff)
set -e
ENV_FILE=/opt/swap/config/swap.env
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)

echo "== step0: snapshot =="
sudo /opt/swap/config/backup.sh | tail -1

echo "== step1: apply db/13 =="
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < /tmp/swapdeploy/13-channel-recon.sql
echo "tables: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e "SHOW TABLES LIKE 'channel_bill'; SHOW TABLES LIKE 'recon_diff'")"

echo "== step2: deploy jar =="
sudo systemctl stop swap-server
sudo cp /tmp/swapdeploy/swap-server-1.0.0.jar /opt/swap/swap-server-1.0.0.jar
sudo chown ubuntu:ubuntu /opt/swap/swap-server-1.0.0.jar
sudo systemctl start swap-server
sleep 15
echo "server=$(systemctl is-active swap-server) health=$(curl -sf http://127.0.0.1:8400/api/actuator/health || echo FAIL)"

echo "== step3: recon smoke =="
ADMIN_PASS=$(grep '^SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
TOKEN=$(curl -sf -X POST http://127.0.0.1:8400/api/admin/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "admin-token-length=${#TOKEN}"
DATE=$(date +%F)
CSVJSON="bill_date,trade_no,amount_fen,status\\n$DATE,RFAKECLOUD1,100,SUCCESS"
IMP=$(curl -sf -X POST "http://127.0.0.1:8400/api/admin/recon/import?date=$DATE" \
    -H "X-Admin-Token: $TOKEN" -H 'Content-Type: application/json' \
    --data-raw "{\"csv\":\"$CSVJSON\"}")
echo "import: $(echo "$IMP" | head -c 200)"
echo "report: $(curl -sf "http://127.0.0.1:8400/api/admin/recon/report?date=$DATE" -H "X-Admin-Token: $TOKEN" | head -c 300)"
DIFF_ID=$(curl -sf "http://127.0.0.1:8400/api/admin/recon/diff?date=$DATE&status=OPEN" -H "X-Admin-Token: $TOKEN" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
if [ -n "$DIFF_ID" ]; then
  echo "handle: $(curl -sf -X POST "http://127.0.0.1:8400/api/admin/recon/diff/$DIFF_ID/handle?status=IGNORED&remark=cloud-smoke" -H "X-Admin-Token: $TOKEN" | head -c 120)"
fi
echo "== done =="
