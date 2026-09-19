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
for _ in $(seq 1 40); do
    if curl -sf $B/actuator/health >/dev/null 2>&1; then break; fi
    sleep 2
done
echo "server=$(systemctl is-active swap-server) health=$(curl -sf $B/actuator/health || echo FAIL)"

echo "== step3: publish swap-web dist =="
sudo mkdir -p "$WEB_ROOT"
sudo rm -rf "${WEB_ROOT:?}/assets"
sudo cp -r /tmp/swapdeploy/web/. "$WEB_ROOT/"
sudo chown -R www-data:www-data "$WEB_ROOT"
# Count as root: /opt/swap/web is www-data-owned and ubuntu cannot traverse it (the first
# deploy reported "published files: 1" for that reason - a wrong number, not a wrong copy).
echo "published files: $(sudo find $WEB_ROOT -type f | wc -l) | index sha256: $(sudo sha256sum $WEB_ROOT/index.html | cut -c1-16)"

echo "== step4: nginx site =="
sudo cp /tmp/swapdeploy/nginx-swap.conf /etc/nginx/sites-available/swap-web
sudo ln -sf /etc/nginx/sites-available/swap-web /etc/nginx/sites-enabled/swap-web
# Debian's default site also claims `listen 80 default_server`, which collides with ours.
# Archive it instead of deleting (workspace red line: archive over delete) so the change
# stays reversible with a single mv back.
if [ -e /etc/nginx/sites-enabled/default ]; then
    sudo mv /etc/nginx/sites-enabled/default /etc/nginx/sites-available/default.pre-swap-web.bak
    echo "archived default site -> /etc/nginx/sites-available/default.pre-swap-web.bak"
fi
sudo nginx -t
sudo systemctl enable nginx >/dev/null 2>&1 || true
sudo systemctl restart nginx
echo "nginx=$(systemctl is-active nginx)"
echo "ufw (unchanged): $(sudo ufw status | head -3 | tr '\n' ' ')"

echo "== step4b: rate limiter must trust the local proxy (batch33) =="
# Once nginx fronts /api, EVERY request reaches the app from 127.0.0.1. Without a trusted
# proxy entry the IP-dimension limiter (admin-login / user-login, 5 per 5s) would put all
# users in one bucket, and with the pre-batch33 code the spoofable XFF decided the bucket.
# nginx appends the real peer with $proxy_add_x_forwarded_for, so trusting 127.0.0.1 makes
# the resolver read the RIGHTMOST address, which is the only one nginx vouches for.
if grep -q '^SWAP_RATELIMIT_TRUSTED_PROXIES=' "$ENV_FILE"; then
    echo "trusted-proxies already configured: $(grep '^SWAP_RATELIMIT_TRUSTED_PROXIES=' "$ENV_FILE")"
else
    echo 'SWAP_RATELIMIT_TRUSTED_PROXIES=127.0.0.1' | sudo tee -a "$ENV_FILE" >/dev/null
    echo "appended SWAP_RATELIMIT_TRUSTED_PROXIES=127.0.0.1 to swap.env"
    sudo systemctl restart swap-server
fi

# Wait for readiness by polling, never by a fixed sleep: the app needs ~12s to boot and a
# too-short sleep turns into a false "health=FAIL" + a wall of 502s downstream (learned the
# hard way on the first deploy of this script).
wait_health() {
    for _ in $(seq 1 40); do
        if curl -sf "$B/actuator/health" >/dev/null 2>&1; then return 0; fi
        sleep 2
    done
    return 1
}
if wait_health; then
    echo "server=$(systemctl is-active swap-server) health=$(curl -sf $B/actuator/health)"
else
    echo "FATAL swap-server did not become healthy within 80s; last log lines:"
    sudo journalctl -u swap-server -n 20 --no-pager | tail -20
    exit 1
fi
echo "limiter probe: see probe-web.sh (P09) - XFF spoofing itself cannot be probed from this host"

echo "== step5: smoke through nginx (same origin as the browser) =="
bash /tmp/swapdeploy/probe-web.sh
echo "== done =="
