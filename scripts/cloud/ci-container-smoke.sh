#!/usr/bin/env bash
# CI-only container smoke test (batch37): compose middleware -> migrations -> image -> health -> API.
#
# Why this exists: the README promises "docker compose up -d gives you MySQL+Redis, and the db/
# migrations run on first boot", but the local dev machine has no Docker, so that path was never
# exercised (P0-4 recorded it as an unverified claim). This script runs the exact promise on a
# Linux runner: it starts the documented compose file, waits for both healthchecks, builds the
# image, runs it with host networking against that middleware, waits for the platform's own
# health endpoint, then logs in and calls a BFF view endpoint.
set -euo pipefail

COMPOSE="docker compose -f docker-compose.middleware.yml"
IMAGE="swap-server:ci"
NAME="swap-ci"

cleanup() {
    echo "--- cleanup ---"
    docker logs "$NAME" 2>/dev/null | tail -40 || true
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    $COMPOSE logs --tail 30 || true
    $COMPOSE down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== step1: middleware (documented compose path, migrations auto-run from ./db) =="
$COMPOSE up -d --wait
docker compose -f docker-compose.middleware.yml ps

echo "== step2: schema check (db/*.sql ran via initdb) =="
TABLES=$(docker exec swap-mysql mysql -uroot -proot -N -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='swap_ops'")
echo "tables in swap_ops: $TABLES"
[ "$TABLES" -ge 20 ] || { echo "FATAL expected the schema to be created from ./db"; exit 1; }

echo "== step3: build image =="
docker build -t "$IMAGE" -f Dockerfile .
docker images "$IMAGE"

echo "== step4: run the platform against the compose middleware =="
docker run -d --name "$NAME" --network host \
    -e SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/swap_ops?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true' \
    -e SPRING_DATASOURCE_USERNAME=root \
    -e SPRING_DATASOURCE_PASSWORD=root \
    -e SPRING_DATA_REDIS_HOST=127.0.0.1 \
    -e SPRING_DATA_REDIS_PORT=6379 \
    -e SWAP_DEVICE_MQ_ENABLED=false \
    -e SWAP_DEV_SECRET=00001111222233334444555566667777 \
    -e SWAP_ADMIN_TOKEN=fedcba9876543210fedcba9876543210 \
    -e SWAP_PAY_SECRET=0123456789abcdef0123456789abcdef \
    -e SWAP_DEV_ENABLED=true \
    -e SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD=container-smoke-pass-24hex0 \
    "$IMAGE"

echo "== step5: wait for the platform health endpoint =="
for i in $(seq 1 60); do
    if docker exec "$NAME" curl -fs http://127.0.0.1:8400/api/actuator/health >/dev/null 2>&1; then
        echo "health UP after ${i} polls"
        break
    fi
    if [ "$i" = "60" ]; then echo "FATAL platform did not become healthy"; exit 1; fi
    sleep 2
done

echo "== step6: docker healthcheck status (what an operator would look at) =="
docker inspect --format '{{.State.Health.Status}}' "$NAME"

echo "== step7: real request through the container =="
TOKEN=$(curl -s -X POST http://127.0.0.1:8400/api/admin/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"container-smoke-pass-24hex0"}' \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "FATAL admin login failed"; exit 1; }
echo "admin login ok (token length ${#TOKEN})"
ME=$(curl -s http://127.0.0.1:8400/api/admin/auth/me -H "X-Admin-Token: $TOKEN")
echo "auth/me -> ${ME:0:120}"
DASH=$(curl -s -o /tmp/dash.json -w '%{http_code}' http://127.0.0.1:8400/api/admin/view/dashboard -H "X-Admin-Token: $TOKEN")
echo "view/dashboard -> $DASH $(head -c 100 /tmp/dash.json)"
[ "$DASH" = "200" ] || { echo "FATAL dashboard view failed"; exit 1; }

echo "== CONTAINER-SMOKE PASS =="
