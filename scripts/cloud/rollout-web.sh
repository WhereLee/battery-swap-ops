#!/usr/bin/env bash
# S8 batch31 cloud rollout: snapshot -> db/16+17 -> new server jar -> swap-web dist -> nginx -> smoke
#
# Runs on the server, driven by scripts/cloud/deploy-web.ps1 (files land in /tmp/swapdeploy).
# Secrets are read from /opt/swap/config/swap.env and never echoed.
# ufw is left untouched: public exposure of 80/8400 stays the user's decision.
set -e
ENV_FILE=/opt/swap/config/swap.env
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
B=http://127.0.0.1:8400/api
WEB_ROOT=/opt/swap/web

echo "== step0: snapshot before schema change =="
sudo /opt/swap/config/backup.sh | tail -1

echo "== step1: apply db/16 + db/17 (idempotent DDL) =="
for f in 16-data-scope.sql 17-work-order-scope.sql; do
    mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < "/tmp/swapdeploy/$f"
    echo "applied $f"
done
echo "admin_user.data_scope present: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='swap_ops' AND table_name='admin_user' AND column_name='data_scope'")"
echo "work_order.station_id present: $(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='swap_ops' AND table_name='work_order' AND column_name='station_id'")"

echo "== step2: deploy server jar =="
sudo systemctl stop swap-server
sudo cp /tmp/swapdeploy/swap-server-1.0.0.jar /opt/swap/swap-server-1.0.0.jar
sudo chown ubuntu:ubuntu /opt/swap/swap-server-1.0.0.jar
sudo systemctl start swap-server
sleep 15
echo "server=$(systemctl is-active swap-server) health=$(curl -sf $B/actuator/health || echo FAIL)"

echo "== step3: publish swap-web dist =="
sudo mkdir -p "$WEB_ROOT"
sudo rm -rf "${WEB_ROOT:?}/assets"
sudo cp -r /tmp/swapdeploy/web/. "$WEB_ROOT/"
sudo chown -R www-data:www-data "$WEB_ROOT"
echo "published files: $(find $WEB_ROOT -type f | wc -l) | index sha256: $(sha256sum $WEB_ROOT/index.html | cut -c1-16)"

echo "== step4: nginx site =="
sudo cp /tmp/swapdeploy/nginx-swap.conf /etc/nginx/sites-available/swap-web
sudo ln -sf /etc/nginx/sites-available/swap-web /etc/nginx/sites-enabled/swap-web
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl enable nginx >/dev/null 2>&1 || true
sudo systemctl restart nginx
echo "nginx=$(systemctl is-active nginx)"
echo "ufw (unchanged): $(sudo ufw status | head -3 | tr '\n' ' ')"

echo "== step5: smoke through nginx (same origin as the browser) =="
echo "index: $(curl -sf -o /dev/null -w '%{http_code} %{content_type}' http://127.0.0.1/)"
echo "spa-deep-link: $(curl -sf -o /dev/null -w '%{http_code}' http://127.0.0.1/work-orders/1)"
echo "asset: $(curl -sf -o /dev/null -w '%{http_code}' "http://127.0.0.1$(grep -o '/assets/index-[A-Za-z0-9_-]*\.js' $WEB_ROOT/index.html | head -1)")"
echo "api-through-proxy: $(curl -sf http://127.0.0.1/api/actuator/health)"

ADMIN_PASS=$(grep '^SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
ATOKEN=$(curl -sf -X POST http://127.0.0.1/api/admin/auth/login -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASS\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "login-through-proxy token-len=${#ATOKEN}"
for EP in "/api/admin/auth/me" "/api/admin/view/dashboard" "/api/admin/view/work-order?page=1&limit=5" \
          "/api/admin/view/order?page=1&limit=5" "/api/admin/view/cabinet?page=1&limit=5" \
          "/api/admin/view/settlement?page=1&limit=5"; do
    CODE=$(curl -sf -o /tmp/swapdeploy/probe.json -w '%{http_code}' "http://127.0.0.1$EP" -H "X-Admin-Token: $ATOKEN")
    echo "probe $EP -> $CODE $(head -c 90 /tmp/swapdeploy/probe.json)"
done
echo "== done =="
