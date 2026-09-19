#!/usr/bin/env bash
# Post-deploy verification for the S8 console (batch31) - runs ON the server.
# Checks the same-origin path the browser uses: nginx -> static dist + /api reverse proxy.
# Reads credentials from swap.env; never prints them.
BASE=http://127.0.0.1
API=$BASE/api
ENV_FILE=/opt/swap/config/swap.env
PASS=0
FAIL=0

ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL  $1"; }
check(){ if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1 (got $2 want $3)"; fi; }

echo "=== cloud console probe ==="
echo "services: swap-server=$(systemctl is-active swap-server) swap-sim=$(systemctl is-active swap-sim) nginx=$(systemctl is-active nginx)"

HEALTH=$(curl -s -o /dev/null -w '%{http_code}' $API/actuator/health)
check "P01 /api/actuator/health through nginx" "$HEALTH" "200"

INDEX=$(curl -s -o /dev/null -w '%{http_code}' $BASE/)
check "P02 index.html" "$INDEX" "200"

DEEP=$(curl -s -o /dev/null -w '%{http_code}' $BASE/work-orders/1)
check "P03 SPA deep link (history fallback)" "$DEEP" "200"

ASSET=$(grep -o '/assets/index-[A-Za-z0-9_-]*\.js' /opt/swap/web/index.html | head -1)
ASSET_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$ASSET")
check "P04 hashed asset $ASSET" "$ASSET_CODE" "200"

CACHE=$(curl -s -D - -o /dev/null "$BASE$ASSET" | grep -i '^cache-control' | tr -d '\r')
echo "INFO  asset cache header: $CACHE"
NOSNIFF=$(curl -s -D - -o /dev/null "$BASE/" | grep -ci 'x-content-type-options')
if [ "$NOSNIFF" -ge 1 ]; then ok "P05 security header present"; else bad "P05 security header missing"; fi

ADMIN_PASS=$(grep '^SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
TOKEN=$(curl -s -X POST $API/admin/auth/login -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then ok "P06 admin login through the proxy (token len ${#TOKEN})"; else bad "P06 admin login failed"; fi

for EP in "/admin/auth/me" "/admin/view/dashboard" "/admin/view/work-order?page=1&limit=3" \
          "/admin/view/order?page=1&limit=3" "/admin/view/cabinet?page=1&limit=3" \
          "/admin/view/settlement?page=1&limit=3" "/admin/view/alarm?page=1&limit=3" \
          "/admin/view/agent-action?page=1&limit=3"; do
    CODE=$(curl -s -o /tmp/swap-probe.json -w '%{http_code}' "$API$EP" -H "X-Admin-Token: $TOKEN")
    BODY=$(head -c 90 /tmp/swap-probe.json)
    check "P07 $EP" "$CODE" "200"
    echo "      body: $BODY"
done

# Unauthenticated access must still be rejected through the proxy (no accidental whitelisting).
UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' "$API/admin/view/dashboard")
check "P08 unauthenticated view call is rejected" "$UNAUTH" "401"

# Batch33: the limiter must still be alive behind the proxy.
#
# What CANNOT be tested from this host: the XFF-spoofing path. This probe runs ON the
# server, so nginx sees the real client as 127.0.0.1 - which is itself in the trusted
# proxy list, so a forged left-hand XFF would be selected by the resolver (an inherent
# property of any XFF chain when the caller is a trusted hop, not a hole for a remote
# attacker: a remote client's own address is untrusted and is returned instead).
# The anti-spoofing property is covered by:
#   - ClientIpResolverTest / RateLimitAspectTest (unit, 20 forged XFF -> 1 bucket)
#   - scripts/verify/batch33/_c36_xff.ps1 (live, trusted-proxies empty: pure no-proxy shape)
# Here we only prove the throttle is alive through nginx (all local callers share the
# 127.0.0.1 bucket), i.e. the trusted-proxy config did not silently disable it.
CODES=""
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    C=$(curl -s -o /dev/null -w '%{http_code}' -X POST $API/admin/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"username":"admin","password":"wrong-on-purpose"}')
    CODES="$CODES $C"
done
echo "INFO  no-XFF burst:$CODES"
if echo "$CODES" | grep -q 429; then ok "P09 login throttle is alive behind nginx"; else bad "P09 login throttle inactive behind nginx"; fi

rm -f /tmp/swap-probe.json
echo ""
echo "=== cloud console probe summary: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ] && echo "GATE-CLOUD PASS" || { echo "GATE-CLOUD FAIL"; exit 1; }
