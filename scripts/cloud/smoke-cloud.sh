#!/usr/bin/env bash
# Cloud smoke test (runs on server against 127.0.0.1; secrets stay local to the box).
set -e
BASE=http://127.0.0.1:8400/api
ADMIN_TOKEN=$(grep '^SWAP_ADMIN_TOKEN=' /opt/swap/config/swap.env | cut -d= -f2)

echo "=== login ==="
LOGIN=$(curl -sf -X POST "$BASE/user/login" -H 'Content-Type: application/json' -d '{"phone":"13800000001"}')
echo "$LOGIN" | head -c 120; echo
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then echo "LOGIN-FAIL"; exit 1; fi
echo "token-length=${#TOKEN}"

echo "=== plans ==="
curl -sf "$BASE/user/plans" -H "X-User-Token: $TOKEN" | head -c 200; echo

echo "=== stations (full/empty counts from Redis pool) ==="
curl -sf "$BASE/user/stations" -H "X-User-Token: $TOKEN" | head -c 400; echo

echo "=== wallet ==="
curl -sf "$BASE/user/wallet" -H "X-User-Token: $TOKEN" | head -c 300; echo

echo "=== admin cabinets (first 2) ==="
curl -sf "$BASE/admin/cabinet?page=1&limit=2" -H "X-Admin-Token: $ADMIN_TOKEN" | head -c 500; echo

echo "=== cabinet online check (DB last_heartbeat_time within 60s) ==="
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' /opt/swap/config/swap.env | cut -d= -f2)
mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e \
  "SELECT COUNT(*) AS online_recent FROM cabinet WHERE last_heartbeat_time > (UNIX_TIMESTAMP()*1000 - 60000)" 2>/dev/null
echo "=== admin ops rebuild-alloc endpoint ==="
curl -sf -X POST "$BASE/admin/ops/rebuild-alloc" -H "X-Admin-Token: $ADMIN_TOKEN"; echo
echo "SMOKE-DONE"
