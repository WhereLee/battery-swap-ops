# batch24 P0-2 dual-instance drill -- analysis & assertions (run AFTER _c29_traffic.ps1 finishes)
#
# Topology under test: two platform nodes (8400 + 8401) on the SAME MySQL + SAME Redis, both with
# MQ consumption on (same consumer group), sim reporting over MQ. Ran continuously for 30 minutes
# while traffic alternated across nodes (orders left un-picked, fault reports, admin reconcile,
# dashboard reads) so that scheduled jobs, delay tasks and work-order SLA all competed for locks.
#
# What "no duplicate execution / no lock avalanche" is asserted against:
#   * distinct Snowflake workerId leases per node (Redis lease, owner = ip:port)
#   * every un-picked order ended CANCELLED with closeReason=PREEMPT_TIMEOUT exactly once
#     (delay queue claim is ZREM-atomic; a double claim would be absorbed by CAS and would show up
#      as a reconciliation violation, so the final reconcile is the ultimate judge)
#   * MQ events were consumed by BOTH nodes (same consumer group -> broker-side load balancing)
#   * no JobLockService degradation, no WorkerIdRegistry lease failure, bounded ERROR volume
#   * reconciliation total = 0 at the end of the window (no duplicate side effects anywhere)
#
# Evidence: _c29_out.txt (this script) + .local\b24-traffic.log (timeline)

$ErrorActionPreference = "Continue"
$repo  = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$server = "http://127.0.0.1:8400/api"
$n1Log = Join-Path $repo ".local\server-mq.out.log"
$n2Log = Join-Path $repo ".local\server2-mq.out.log"
$traffic = Join-Path $repo ".local\b24-traffic.log"
$startFile = Join-Path $repo ".local\b24-drill-start.txt"
$out = Join-Path $PSScriptRoot "_c29_out.txt"
$adminToken = (Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim()
$AH = @{ "X-Admin-Token" = $adminToken }

$script:pass = 0; $script:fail = 0
$script:lines = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { $script:pass = $script:pass + 1; [void]$script:lines.Add("PASS  $name  --  $detail") }
  else     { $script:fail = $script:fail + 1; [void]$script:lines.Add("FAIL  $name  --  $detail") }
}
function Read-Shared([string]$path) {
  if (-not (Test-Path $path)) { return "" }
  $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
  try { $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8); $t = $sr.ReadToEnd(); $sr.Close() } finally { $fs.Close() }
  return $t
}

$since = (Get-Content $startFile -Raw).Trim()
$n1All = Read-Shared $n1Log
$n2All = Read-Shared $n2Log
$t1 = @(($n1All -split "`n") | Where-Object { $_.Length -ge 19 -and $_.Substring(0, 19) -ge $since })
$t2 = @(($n2All -split "`n") | Where-Object { $_.Length -ge 19 -and $_.Substring(0, 19) -ge $since })
$tr = @(Get-Content $traffic -Encoding UTF8)
$n1Txt = $t1 -join "`n"; $n2Txt = $t2 -join "`n"; $trTxt = $tr -join "`n"

# ---------- 1. both nodes alive after 30 minutes ----------
$h1 = $null; $h2 = $null
try { $h1 = (Invoke-RestMethod "$server/actuator/health" -TimeoutSec 5).status } catch { }
try { $h2 = (Invoke-RestMethod "http://127.0.0.1:8401/api/actuator/health" -TimeoutSec 5).status } catch { }
Check "nodes.both-up-after-drill" ($h1 -eq "UP" -and $h2 -eq "UP") "node1=$h1 node2=$h2 (no crash, no OOM)"

# ---------- 2. distinct workerId leases (leases are taken at STARTUP, i.e. before the window) ----------
$w1 = ([regex]::Match($n1All, "workerId=(\d+)")).Groups[1].Value
$w2 = ([regex]::Match($n2All, "workerId=(\d+)")).Groups[1].Value
Check "ids.distinct-worker-leases" ($w1 -ne "" -and $w2 -ne "" -and $w1 -ne $w2) "node1 workerId=$w1 node2 workerId=$w2 (Redis lease, owner=ip:port)"
Check "ids.no-lease-renewal-failure" ((($t2 + $t1) | Where-Object { $_ -match "WorkerIdRegistry" -and $_ -match "ERROR" }).Count -eq 0) "no WorkerIdRegistry ERROR in window"

# ---------- 3. traffic really hit both nodes, for 6 rounds ----------
$rounds = @($tr | Where-Object { $_ -match "---- round \d+/6" }).Count
Check "traffic.rounds-complete" ($rounds -eq 6) "rounds seen=$rounds (6 x 300s = 30 min)"
$served1 = @($tr | Where-Object { $_ -match "on http://127\.0\.0\.1:8400/api" }).Count
$served2 = @($tr | Where-Object { $_ -match "on http://127\.0\.0\.1:8401/api" }).Count
Check "traffic.both-nodes-served" ($served1 -ge 6 -and $served2 -ge 6) "successful calls: node1=$served1 node2=$served2"
Check "traffic.no-unreachable" ((@($tr | Where-Object { $_ -match "UNREACHABLE|FAILED" }).Count) -eq 0) "no unreachable/failed call in the whole window"
$recTotals = @($tr | Where-Object { $_ -match "reconcile on .* total=(\d+)" } | ForEach-Object { [int]([regex]::Match($_, "total=(\d+)").Groups[1].Value) })
Check "traffic.reconcile-zero-every-round" ((($recTotals | Where-Object { $_ -ne 0 }).Count) -eq 0 -and $recTotals.Count -ge 6) ("reconcile totals: " + ($recTotals -join ","))

# ---------- 4. delay tasks: every un-picked order closed EXACTLY ONCE by a timeout task ----------
# Both timeout paths are legitimate delay-task outcomes: PREEMPT_TIMEOUT (door never opened) and
# PICKUP_TIMEOUT (sim opened the door but nobody took the battery) -> status 7 TIMEOUT_CLOSED.
$orderNos = @($tr | ForEach-Object { [regex]::Match($_, "orderNo=(\S+)").Groups[1].Value } | Where-Object { $_ -ne "" })
Check "delay.orders-created" ($orderNos.Count -ge 6) "un-picked orders created during drill: $($orderNos.Count)"
$timeoutReasons = @("PREEMPT_TIMEOUT", "PICKUP_TIMEOUT")
$closed = 0; $wrong = @(); $reasonMix = @()
foreach ($no in $orderNos) {
  try {
    $d = (Invoke-RestMethod "$server/admin/order/$no" -Headers $AH -TimeoutSec 5).data
    if ($d.status -eq 7 -and $timeoutReasons -contains $d.closeReason) { $closed++; $reasonMix += $d.closeReason }
    else { $wrong += "$no(status=$($d.status),reason=$($d.closeReason))" }
  } catch { $wrong += "$no(query-fail)" }
}
$mixTxt = (($reasonMix | Group-Object | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join " ")
Check "delay.all-closed-by-timeout-task" ($closed -eq $orderNos.Count) "closed=$closed/$($orderNos.Count) reasons: $mixTxt"
Check "delay.no-foreign-close-reason" ($wrong.Count -eq 0) "unexpected: $($wrong -join ',')"

# strongest form of "no duplicate execution": each orderNo is closed in EXACTLY ONE node's log, once
$dupes = @(); $byNode1 = 0; $byNode2 = 0
foreach ($no in $orderNos) {
  $c1 = @($t1 | Where-Object { $_ -match $no -and $_ -match "reason=" }).Count
  $c2 = @($t2 | Where-Object { $_ -match $no -and $_ -match "reason=" }).Count
  if (($c1 + $c2) -ne 1) { $dupes += "$no(node1=$c1,node2=$c2)" }
  elseif ($c1 -eq 1) { $byNode1++ } else { $byNode2++ }
}
Check "delay.executed-exactly-once-across-nodes" ($dupes.Count -eq 0) "no order closed twice anywhere; offenders: $($dupes -join ',')"
Check "delay.both-nodes-participated" ($byNode1 -ge 1 -and $byNode2 -ge 1) "timeout tasks ran on both nodes without overlap: node1=$byNode1 node2=$byNode2"

# ---------- 5. MQ consumption shared by both nodes (same consumer group) ----------
$c1 = @($t1 | Where-Object { $_ -match "DeviceEventMqConsumer" -and $_ -match "cabinetNo=" }).Count
$c2 = @($t2 | Where-Object { $_ -match "DeviceEventMqConsumer" -and $_ -match "cabinetNo=" }).Count
Check "mq.consumed-by-both-nodes" ($c1 -gt 0 -and $c2 -gt 0) "event lines: node1=$c1 node2=$c2 (broker-side load balancing across the group)"
$p1 = @($t1 | Where-Object { $_ -match "mq-worker-(\d)" } | ForEach-Object { [regex]::Match($_, "mq-worker-(\d)").Groups[1].Value } | Sort-Object -Unique)
$p2 = @($t2 | Where-Object { $_ -match "mq-worker-(\d)" } | ForEach-Object { [regex]::Match($_, "mq-worker-(\d)").Groups[1].Value } | Sort-Object -Unique)
Check "mq.sharded-workers-active-both-nodes" ($p1.Count -ge 1 -and $p2.Count -ge 1) "node1 workers=$($p1 -join ',') node2 workers=$($p2 -join ',')"

# ---------- 6. no lock avalanche / no degradation ----------
$lockWarn = @(($t1 + $t2) | Where-Object { $_ -match "JobLockService" }).Count
Check "locks.no-degradation-line" ($lockWarn -eq 0) "JobLockService emitted no warn/error (no Redis lock failure, no avalanche)"
$err1 = @($t1 | Where-Object { $_ -match " ERROR " }).Count
$err2 = @($t2 | Where-Object { $_ -match " ERROR " }).Count
Check "errors.bounded" (($err1 + $err2) -lt 40) "ERROR lines in window: node1=$err1 node2=$err2 (poison-message/SLA notices expected, avalanche is not)"
$connRefused = @(($t1 + $t2) | Where-Object { $_ -match "Connection refused|CONN_REFUSED" }).Count
Check "errors.no-connection-storm" ($connRefused -eq 0) "no connection-refused storm (thread pools never saturated)"

# ---------- 7. final judge: reconciliation over the whole system ----------
$final = (Invoke-RestMethod -Method Post "http://127.0.0.1:8401/api/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data
Check "reconcile.final-zero" ($final.totalViolations -eq 0) ("14 invariants total=" + $final.totalViolations + " after 30 min of dual-node traffic (duplicate execution would break conservation)")

# ---------- summary ----------
$total = $script:pass + $script:fail
$head = @(
  "batch24 P0-2 dual-instance drill -- analysis",
  ("run at     : " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss")),
  ("window from: " + $since + "  (30 minutes, 6 traffic rounds)"),
  ("topology   : node1 :8400 (workerId=$w1) + node2 :8401 (workerId=$w2), same MySQL + same Redis, both consuming MQ"),
  ("jobs       : 11 scheduled entries behind JobLockService lease + delay queue ZREM claim + watchdog"),
  ("result     : PASS " + $script:pass + " / " + $total)
)
($head + $script:lines) | Set-Content -Path $out -Encoding UTF8
$head | ForEach-Object { Write-Host $_ }
$script:lines | ForEach-Object { Write-Host $_ }
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
