#!/usr/bin/env bash
# diagnose cloud deploy env injection + seed failure (read-only, masks values)
echo "=== env file (keys only) ==="
sudo awk -F= '{print $1}' /opt/swap/config/swap.env
echo "=== env file owner/perm ==="
sudo ls -la /opt/swap/config/swap.env
echo "=== server unit env keys (masked) ==="
SRV_PID=$(systemctl show -p MainPID --value swap-server)
if [ "$SRV_PID" != "0" ]; then
  sudo cat "/proc/$SRV_PID/environ" 2>/dev/null | tr '\0' '\n' | grep -E 'SWAP_DEV_SECRET|SWAP_ADMIN|SPRING_DATA|SPRING_DATASOURCE' | cut -d= -f1
else
  echo "swap-server not running"
fi
echo "=== seed log: startup + seeder lines ==="
grep -nE "Started SwapServerApplication|ERROR|Exception|seed|种子|cabinet" /opt/swap/logs/seed.out.log | head -20
echo "=== seed log: redis-related causes ==="
grep -nE "Caused by" /opt/swap/logs/seed.out.log | head -6
echo "=== redis ping with env pass ==="
RP=$(grep '^SPRING_DATA_REDIS_PASSWORD=' /opt/swap/config/swap.env | cut -d= -f2)
redis-cli -a "$RP" --no-auth-warning ping 2>&1 | tail -1
