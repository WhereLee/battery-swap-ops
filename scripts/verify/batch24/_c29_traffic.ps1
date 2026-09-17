# batch24 P0-2 dual-instance traffic generator (default 30 min: 6 rounds x 300 s)
#
# Alternates requests across node1 (8400) and node2 (8401) so BOTH nodes serve traffic while
# scheduled jobs (reconcile / order sweep / outbox relay / offline scan / watchdog), delay tasks
# (preempt timeout) and work-order SLA compete for the same Redis job locks and ZSET claims.
#
# Each round:
#   1) health on both nodes
#   2) rider login + TAKE order left un-picked  -> preempt timeout cancellation (delay-task load)
#   3) rider fault report                       -> work order (SLA scan load)
#   4) admin reconcile run + dashboard read on the OTHER node (job-lock competition + read parity)
#
# Output: .local\b24-traffic.log (timeline evidence, consumed by _c29_dual_instance.ps1)
# Usage : powershell -ExecutionPolicy Bypass -File _c29_traffic.ps1 [-Rounds 6] [-IntervalSec 300]

param([int]$Rounds = 6, [int]$IntervalSec = 300)

$ErrorActionPreference = "Continue"
# PSScriptRoot = <repo>\scripts\verify\batch24  -> three levels up is the repo root
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$nodes = @("http://127.0.0.1:8400/api", "http://127.0.0.1:8401/api")
$adminToken = (Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($adminToken)) { throw "admin token missing (needed for reconcile/dashboard traffic)" }
$AH = @{ "X-Admin-Token" = $adminToken }
$logFile = Join-Path $repo ".local\b24-traffic.log"
Set-Content -Path $logFile -Encoding UTF8 -Value ("== dual-instance traffic start " +
  (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + " rounds=$Rounds interval=${IntervalSec}s nodes=8400,8401 ==")

function Log($m) {
  $l = "$(Get-Date -Format 'HH:mm:ss') $m"
  Write-Host $l
  Add-Content -Path $logFile -Value $l -Encoding UTF8
}

for ($i = 1; $i -le $Rounds; $i++) {
  $primary = $nodes[($i - 1) % 2]
  $other   = $nodes[$i % 2]
  $phone   = "138000000{0:d2}" -f ($i + 1)   # seeded riders 02..07 (deposit paid, plan x5)
  Log "---- round $i/$Rounds primary=$primary other=$other rider=$phone ----"

  # 1) health on both nodes
  foreach ($n in $nodes) {
    try {
      $h = (Invoke-RestMethod "$n/actuator/health" -TimeoutSec 5).status
      Log "health $n = $h"
    } catch {
      Log "health $n UNREACHABLE $($_.Exception.Message)"
    }
  }

  # 2) rider login + TAKE order (left un-picked on purpose: preempt TTL cancels it via delay task)
  $token = $null
  try {
    $login = Invoke-RestMethod -Method Post "$primary/user/login" -ContentType "application/json" `
      -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5
    $token = $login.data.token
    Log "rider login ok on $primary phone=$phone"
  } catch {
    Log "rider login FAILED on $primary : $($_.Exception.Message)"
  }
  if ($token) {
    try {
      $headers = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
      $order = Invoke-RestMethod -Method Post "$primary/user/order" -Headers $headers `
        -ContentType "application/json" -Body (@{ type = "TAKE" } | ConvertTo-Json) -TimeoutSec 10
      Log "order created on $primary orderNo=$($order.data.orderNo) status=$($order.data.status) (un-picked -> delay task must cancel it once)"
    } catch {
      Log "order create FAILED on $primary : $($_.Exception.Message)"
    }

    # 3) rider fault report -> work order (SLA scan load)
    try {
      $H = @{ "X-User-Token" = $token }
      $report = Invoke-RestMethod -Method Post "$primary/user/report" -Headers $H -ContentType "application/json" `
        -Body (@{ cabinetNo = "SWAP-C-001"; type = "OTHER"; description = "dual-instance drill round $i" } | ConvertTo-Json) -TimeoutSec 5
      Log "fault report on $primary -> $($report.data | ConvertTo-Json -Compress)"
    } catch {
      Log "fault report FAILED on $primary : $($_.Exception.Message)"
    }
  }

  # 4) admin reconcile + dashboard on the OTHER node (job-lock competition, read parity)
  try {
    $rec = (Invoke-RestMethod -Method Post "$other/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data
    Log "reconcile on $other total=$($rec.totalViolations) durationMs=$($rec.durationMs)"
  } catch {
    Log "reconcile FAILED on $other : $($_.Exception.Message)"
  }
  try {
    $dash = (Invoke-RestMethod "$other/admin/dashboard/overview" -Headers $AH -TimeoutSec 10).data
    Log "dashboard on $other -> $($dash | ConvertTo-Json -Compress -Depth 2)"
  } catch {
    Log "dashboard FAILED on $other : $($_.Exception.Message)"
  }

  if ($i -lt $Rounds) {
    Log "sleeping ${IntervalSec}s until next round"
    Start-Sleep -Seconds $IntervalSec
  }
}

Log ("== dual-instance traffic done " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + " ==")
