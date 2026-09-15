#!/usr/bin/env bash
# S7 WP-B/韧性补丁 cloud rollout: snapshot -> db/12 + db/15 -> new jar -> restart -> smoke (agent create/list, settlement report, station direct view)
set -e
ENV_FILE=/opt/swap/config/swap.env
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
B=http://127.0.0.1:8400/api

echo "== step0: snapshot =="
sudo /opt/swap/config/backup.sh | tail -1

echo "== step1: apply db/12 + db/15 =="
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < /tmp/swapdeploy/12-agent-settlement.sql
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < /tmp/swapdeploy/15-resilience-patch.sql
echo "tables: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e "SHOW TABLES LIKE 'agent'; SHOW TABLES LIKE 'order_settlement'; SHOW TABLES LIKE 'settlement_statement'" | tr '\n' ' ')"
echo "arrears-reason: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e "SHOW COLUMNS FROM arrears_record LIKE 'reason'" | awk '{print $1}')"

echo "== step2: deploy jar =="
sudo systemctl stop swap-server
sudo cp /tmp/swapdeploy/swap-server-1.0.0.jar /opt/swap/swap-server-1.0.0.jar
sudo chown ubuntu:ubuntu /opt/swap/swap-server-1.0.0.jar
sudo systemctl start swap-server
sleep 15
echo "server=$(systemctl is-active swap-server) health=$(curl -sf $B/actuator/health || echo FAIL)"

echo "== step3: smoke =="
ADMIN_PASS=$(grep '^SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
ATOKEN=$(curl -sf -X POST $B/admin/auth/login -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "admin-token=${#ATOKEN}"
AG=$(curl -sf -X POST $B/admin/agent -H "X-Admin-Token: $ATOKEN" -H 'Content-Type: application/json' \
    -d "{\"agentNo\":\"AGCLOUD$RANDOM\",\"name\":\"云冒烟代理\",\"shareBp\":5000,\"settlementCycle\":\"MONTHLY\"}" | head -c 200)
echo "agent-create: $AG"
echo "agent-list: $(curl -sf "$B/admin/agent" -H "X-Admin-Token: $ATOKEN" | head -c 200)"
NOW=$(date +%s)000
echo "stmt-generate-no-flow: $(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/admin/settlement/generate?agentId=1&periodStart=$((NOW-3600000))&periodEnd=$((NOW+3600000))" -H "X-Admin-Token: $ATOKEN")"
echo "report: $(curl -sf "$B/admin/settlement/report?from=$((NOW-86400000))&to=$NOW" -H "X-Admin-Token: $ATOKEN" | head -c 200)"
echo "stations: $(curl -sf "$B/admin/station?page=1&limit=3" -H "X-Admin-Token: $ATOKEN" | head -c 200)"
echo "== done =="
