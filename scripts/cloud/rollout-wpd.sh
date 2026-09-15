#!/usr/bin/env bash
# S7 WP-D cloud rollout: snapshot -> db/14 -> new jar -> restart -> smoke (report->work order close, arrears list, coupon grant/list, messages)
set -e
ENV_FILE=/opt/swap/config/swap.env
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
B=http://127.0.0.1:8400/api

echo "== step0: snapshot =="
sudo /opt/swap/config/backup.sh | tail -1

echo "== step1: apply db/14 =="
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < /tmp/swapdeploy/14-user-service.sql
echo "tables: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e "SHOW TABLES LIKE 'arrears_record'; SHOW TABLES LIKE 'coupon_template'; SHOW TABLES LIKE 'user_coupon'; SHOW TABLES LIKE 'user_message'" | tr '\n' ' ')"

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
UTH=$(curl -sf -X POST $B/user/login -H 'Content-Type: application/json' -d '{"phone":"13800000001"}' \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "admin-token=${#ATOKEN} user-token=${#UTH}"

REPORT=$(curl -sf -X POST $B/user/report -H "X-User-Token: $UTH" -H 'Content-Type: application/json' \
    -d '{"cabinetNo":"SWAP-C-001","cellNo":3,"type":"DEVICE_FAULT","description":"cloud smoke"}')
echo "report: $(echo "$REPORT" | head -c 160)"
WONO=$(echo "$REPORT" | sed -n 's/.*"woNo":"\([^"]*\)".*/\1/p')
WOID=$(curl -sf "$B/admin/work-order?status=1" -H "X-Admin-Token: $ATOKEN" \
    | sed -n "s/.*{\"id\":\([0-9]*\),\"woNo\":\"$WONO\".*/\1/p" | head -1)
if [ -n "$WOID" ]; then
  for STEP in "triage?severity=MEDIUM&remark=smoke" "assign?handlerId=1&remark=smoke" "start?remark=smoke" "verify?remark=smoke" "close?remark=cloud-smoke"; do
    curl -sf -X POST "$B/admin/work-order/$WOID/${STEP%%\?*}?${STEP#*\?}" -H "X-Admin-Token: $ATOKEN" | head -c 0 || echo "step-fail $STEP"
  done
  echo "wo closed: $WONO"
fi
echo "messages: $(curl -sf "$B/user/messages" -H "X-User-Token: $UTH" | head -c 200)"
echo "arrears: $(curl -sf "$B/user/arrears" -H "X-User-Token: $UTH" | head -c 120)"
echo "admin-arrears: $(curl -sf "$B/admin/arrears" -H "X-Admin-Token: $ATOKEN" | head -c 120)"
TID=$(curl -sf -X POST "$B/admin/coupon/template" -H "X-Admin-Token: $ATOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"Cloud-Smoke-$RANDOM\",\"valueFen\":100,\"minAmountFen\":100,\"totalQuantity\":10,\"perUserLimit\":1,\"validDays\":30}" \
    | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
echo "template-id=$TID"
echo "grant: $(curl -sf -X POST "$B/admin/coupon/grant?templateId=$TID&userIds=1" -H "X-Admin-Token: $ATOKEN" | head -c 160)"
echo "coupons: $(curl -sf "$B/user/coupons?status=1" -H "X-User-Token: $UTH" | head -c 200)"
echo "== done =="
