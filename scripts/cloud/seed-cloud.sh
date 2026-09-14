#!/usr/bin/env bash
# One-shot dev seed on cloud (idempotent; runs ~70s then SIGTERM). Requires /opt/swap/config/swap.env.
set -e
sudo systemctl stop swap-server swap-sim 2>/dev/null || true
sleep 2
cd /opt/swap
set -a
. /opt/swap/config/swap.env
set +a
timeout 75 java -Xms256m -Xmx768m -jar /opt/swap/swap-server-1.0.0.jar \
    --swap.dev.enabled=true --swap.dev.cabinets=10 > /opt/swap/logs/seed.out.log 2>&1 || true
DB_PASS=$(grep '^SPRING_DATASOURCE_PASSWORD=' /opt/swap/config/swap.env | cut -d= -f2)
echo "seed-cabinets=$(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*) FROM cabinet' 2>/dev/null)"
echo "seed-users=$(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*) FROM swap_user' 2>/dev/null)"
echo "seed-batteries=$(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e 'SELECT COUNT(*) FROM battery' 2>/dev/null)"
sudo systemctl start swap-server
sleep 15
sudo systemctl start swap-sim
sleep 12
echo "server=$(systemctl is-active swap-server) sim=$(systemctl is-active swap-sim)"
echo "server-health=$(curl -sf http://127.0.0.1:8400/api/actuator/health || echo FAIL)"
echo "sim-health=$(curl -sf http://127.0.0.1:8500/actuator/health || echo FAIL)"
