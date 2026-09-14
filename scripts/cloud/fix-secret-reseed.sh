#!/usr/bin/env bash
# Fix SWAP_DEV_SECRET length (must be exactly 32 hex), re-seed, restart services, verify.
set -e
ENV_FILE=/opt/swap/config/swap.env
NEW_SECRET=$(openssl rand -hex 16)   # 32 hex chars
sudo sed -i "s/^SWAP_DEV_SECRET=.*/SWAP_DEV_SECRET=$NEW_SECRET/" "$ENV_FILE"
echo "secret-len=$(sudo grep '^SWAP_DEV_SECRET=' $ENV_FILE | cut -d= -f2 | wc -c) (expect 33 incl newline)"

sudo systemctl stop swap-server swap-sim 2>/dev/null || true
sleep 2
cd /opt/swap
set -a
. "$ENV_FILE"
set +a
timeout 70 java -Xms256m -Xmx768m -jar /opt/swap/swap-server-1.0.0.jar \
    --swap.dev.enabled=true --swap.dev.cabinets=10 > /opt/swap/logs/seed.out.log 2>&1 || true
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)
echo "cabinets=$(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*) FROM cabinet' 2>/dev/null)"
echo "users=$(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*) FROM swap_user' 2>/dev/null)"
echo "batteries=$(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*) FROM battery' 2>/dev/null)"
echo "seed-errors=$(grep -c 'Application run failed' /opt/swap/logs/seed.out.log || true)"

sudo systemctl start swap-server
sleep 15
sudo systemctl start swap-sim
sleep 12
echo "server=$(systemctl is-active swap-server) sim=$(systemctl is-active swap-sim)"
echo "server-health=$(curl -sf http://127.0.0.1:8400/api/actuator/health || echo FAIL)"
echo "sim-health=$(curl -sf http://127.0.0.1:8500/actuator/health || echo FAIL)"
