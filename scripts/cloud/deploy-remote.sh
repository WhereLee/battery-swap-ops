#!/usr/bin/env bash
# battery-swap-ops cloud deployment (phase 1: HTTP channel, no MQ on cloud)
# Invoked by deploy.ps1 via ssh as ubuntu (sudo nopasswd). Secrets generated server-side,
# stored only in /opt/swap/config/swap.env (600). Idempotent.
set -euo pipefail

DEPLOY_TMP=/tmp/swapdeploy
APP_DIR=/opt/swap
CONFIG_DIR=/opt/swap/config
# redis requirepass supplied via scp'd file (never in git); see deploy.ps1
REDIS_PASS=$(cat "$DEPLOY_TMP/redis-pass.txt")

echo "[swap-deploy] step1: dirs"
sudo mkdir -p "$APP_DIR" "$CONFIG_DIR" "$APP_DIR/logs"
sudo cp "$DEPLOY_TMP/swap-server-1.0.0.jar" "$APP_DIR/"
sudo cp "$DEPLOY_TMP/swap-sim-1.0.0.jar" "$APP_DIR/"

if [ ! -f "$CONFIG_DIR/swap.env" ]; then
  echo "[swap-deploy] step2: generate env (secrets server-side only)"
  DB_PASS=$(openssl rand -hex 16)
  DEV_SECRET=$(openssl rand -hex 32)
  ADMIN_TOKEN=$(openssl rand -hex 32)
  PAY_SECRET=$(openssl rand -hex 32)
  sudo tee "$CONFIG_DIR/swap.env" > /dev/null <<EOF
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/swap_ops?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=swap_app
SPRING_DATASOURCE_PASSWORD=$DB_PASS
SPRING_DATA_REDIS_PASSWORD=$REDIS_PASS
SWAP_DEV_SECRET=$DEV_SECRET
SWAP_ADMIN_TOKEN=$ADMIN_TOKEN
SWAP_PAY_SECRET=$PAY_SECRET
SWAP_DEVICE_MQ_ENABLED=false
SWAP_SIM_MQ_ENABLED=false
SWAP_SIM_BASE_URL=http://127.0.0.1:8500
EOF
  sudo chmod 600 "$CONFIG_DIR/swap.env"
  sudo chown ubuntu:ubuntu "$CONFIG_DIR/swap.env"

  echo "[swap-deploy] step3: database + app user"
  sudo mysql <<EOF
CREATE DATABASE IF NOT EXISTS swap_ops CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'swap_app'@'localhost' IDENTIFIED BY '$DB_PASS';
ALTER USER 'swap_app'@'localhost' IDENTIFIED BY '$DB_PASS';
GRANT ALL PRIVILEGES ON swap_ops.* TO 'swap_app'@'localhost';
FLUSH PRIVILEGES;
EOF
  echo "[swap-deploy] step4: apply migrations"
  for f in "$DEPLOY_TMP"/db/*.sql; do
    echo "  applying $(basename "$f")"
    mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops < "$f"
  done

  echo "[swap-deploy] step4b: one-shot dev seed (10 cabinets; dev endpoints only during this boot)"
  set -a; . "$CONFIG_DIR/swap.env"; set +a
  cd "$APP_DIR"
  nohup java -Xms256m -Xmx768m -jar "$APP_DIR/swap-server-1.0.0.jar" \
      --swap.dev.enabled=true --swap.dev.cabinets=10 > "$APP_DIR/logs/seed.out.log" 2>&1 &
  SEED_PID=$!
  for i in $(seq 1 12); do
    sleep 5
    if mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -e "SELECT COUNT(*) AS c FROM cabinet" 2>/dev/null | grep -qE '^\s*[1-9]'; then
      break
    fi
  done
  sleep 5
  kill "$SEED_PID" 2>/dev/null || true
  sleep 5
  echo "  seeded cabinets=$(mysql -uswap_app -p"$DB_PASS" -h127.0.0.1 swap_ops -N -e "SELECT COUNT(*) FROM cabinet" 2>/dev/null)"
else
  echo "[swap-deploy] env exists, skip secrets/db init (idempotent rerun)"
fi

echo "[swap-deploy] step5: systemd units"
sudo cp "$DEPLOY_TMP/swap-server.service" /etc/systemd/system/
sudo cp "$DEPLOY_TMP/swap-sim.service" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now swap-server
sleep 8
sudo systemctl enable --now swap-sim

echo "[swap-deploy] step6: verify"
sleep 12
sudo systemctl is-active swap-server swap-sim
curl -sf http://127.0.0.1:8400/api/actuator/health || echo "SERVER-HEALTH-FAIL"
curl -sf http://127.0.0.1:8500/actuator/health || echo "SIM-HEALTH-FAIL"
echo "[swap-deploy] done"
