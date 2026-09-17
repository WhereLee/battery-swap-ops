# batch26 P2-13 heterogeneous device drill (non-Java device endpoint, stdlib Python)
#
# Purpose: prove the device protocol is OPEN, not "self-produced self-consumed" -- an independently
# implemented device process (Python, no third-party deps, no shared code with the platform) completes
# a full swap loop: heartbeat -> platform downlink OPEN_CELL -> DOOR_OPENED -> BATTERY_OUT/IN -> order done.
#
# Topology during the drill (script owns the whole lifecycle, restores at the end):
#   platform :8400  HTTP channel (mq off), downlink base -> http://127.0.0.1:8600 (the hetero device)
#   device   :8600  scripts/verify/batch26/hetero_device.py, cabinet SWAP-C-005
#   sim             stopped (it would otherwise drive the same cabinets and clash on bootId generation)
#
# Checks: heartbeat accepted / bad downlink signature 401 / command idempotency / TAKE loop /
#         RETURN loop (deposit refund, fee 0) / ledger monotonic + bootId ownership / device counters.

$ErrorActionPreference = "Continue"
$repo   = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$server = "http://127.0.0.1:8400/api"
$device = "http://127.0.0.1:8600"
$cab    = "SWAP-C-005"
$cabIdx = 5
$devLog = Join-Path $repo ".local\hetero-device.log"
$devErr = Join-Path $repo ".local\hetero-device.err.log"
$srvLog = Join-Path $repo ".local\server-hetero.out.log"
$srvErr = Join-Path $repo ".local\server-hetero.err.log"
$out    = Join-Path $PSScriptRoot "_c30_out.txt"
$secret = (Get-Content (Join-Path $repo ".local\dev-secret.txt") -Raw).Trim()

$script:pass = 0; $script:fail = 0
$script:lines = New-Object System.Collections.ArrayList
function Check([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { $script:pass = $script:pass + 1; [void]$script:lines.Add("PASS  $name  --  $detail") }
  else     { $script:fail = $script:fail + 1; [void]$script:lines.Add("FAIL  $name  --  $detail") }
}
function Hmac-Hex([string]$key, [string]$canonical) {
  $h = New-Object System.Security.Cryptography.HMACSHA256
  $h.Key = [System.Text.Encoding]::UTF8.GetBytes($key)
  (($h.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical))) | ForEach-Object { $_.ToString("x2") }) -join ""
}
function Read-Shared([string]$path) {
  if (-not (Test-Path $path)) { return "" }
  $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
  try { $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8); $t = $sr.ReadToEnd(); $sr.Close() } finally { $fs.Close() }
  return $t
}
function Wait-Order([string]$orderNo, [int]$expect, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    try {
      $r = Invoke-RestMethod "$server/user/order/$orderNo" -Headers @{ "X-User-Token" = $token } -TimeoutSec 5
      if ($r.data.status -eq $expect) { return $r.data }
    } catch { }
    Start-Sleep -Milliseconds 400
  }
  return $null
}

# ---------- 1. tear down the standing topology (sim + platform nodes + stale device port) ----------
foreach ($port in @(8500, 8400, 8401, 8600)) {
  $pids = @(netstat -ano | Select-String ":$port\s.*LISTENING" | ForEach-Object { ($_.Line -split '\s+')[-1] } | Sort-Object -Unique)
  foreach ($p in $pids) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
}
Start-Sleep 3

# ---------- 2. platform: HTTP channel, downlink pointed at the hetero device ----------
$env:SWAP_DEV_SECRET = $secret
$env:SWAP_ADMIN_TOKEN = (Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim()
$env:SWAP_PAY_SECRET = (Get-Content (Join-Path $repo ".local\pay-secret.txt") -Raw).Trim()
$javaw = "C:\Program Files\Common Files\Oracle\Java\javapath\javaw.exe"
Start-Process -FilePath $javaw -ArgumentList @(
  '-Xms512m','-Xmx512m','-jar', (Join-Path $repo "swap-server\target\swap-server-1.0.0.jar"),
  '--swap.dev.enabled=true','--swap.ratelimit.enabled=false','--swap.device.mq.enabled=false',
  "--swap.device.sim-base-url=$device"
) -RedirectStandardOutput $srvLog -RedirectStandardError $srvErr -WorkingDirectory $repo
$up = $false
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep 2
  try { if ((Invoke-RestMethod "$server/actuator/health" -TimeoutSec 3).status -eq "UP") { $up = $true; break } } catch { }
}
Check "setup.platform-up-http-channel" $up "platform :8400 UP with downlink base $device"
if (-not $up) { ($script:lines) | Set-Content $out -Encoding UTF8; exit 1 }

# ---------- 2b. reset leftovers to the seed baseline on BOTH sides (rerun hygiene) ----------
$devState = Join-Path $repo ".local\hetero-$cab.json"
if (Test-Path $devState) { Remove-Item $devState -Force }
$reset = Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 30
$rd = $reset.data
Check "setup.dev-reset-ok" ($reset.code -eq 0) ("leftover orders cancelled=" + $rd.ordersCancelled + " batteries homed=" + $rd.batteriesDetached + " cells occupied=" + $rd.cellsOccupied + "; device state file cleared (fresh seed)")

# ---------- 3. heterogeneous device (Python, stdlib only) ----------
if (Test-Path $devLog) { Remove-Item $devLog -Force }
$env:HETERO_DEVICE_SECRET = $secret
Start-Process -FilePath "python" -ArgumentList @(
  (Join-Path $repo "scripts\verify\batch26\hetero_device.py"),
  '--cabinet', $cab, '--listen', '8600', '--cabinet-index', "$cabIdx",
  '--platform', 'http://127.0.0.1:8400', '--state', (Join-Path $repo ".local\hetero-$cab.json")
) -RedirectStandardOutput $devLog -RedirectStandardError $devErr -WorkingDirectory $repo
$devUp = $false
for ($i = 0; $i -lt 20; $i++) {
  Start-Sleep 1
  try { if ((Invoke-RestMethod "$device/health" -TimeoutSec 2).status -eq "UP") { $devUp = $true; break } } catch { }
}
Check "setup.hetero-device-up" $devUp "python device listening on :8600 (independent implementation)"
if (-not $devUp) {
  Check "setup.hetero-device-stderr" $false ((Read-Shared $devErr) -replace "`r`n", " | ")
  ($script:lines) | Set-Content $out -Encoding UTF8; exit 1
}

# ---------- 4. heartbeat accepted -> platform marks the cabinet online ----------
$online = $false
for ($i = 0; $i -lt 15; $i++) {
  Start-Sleep 2
  try {
    $st = Invoke-RestMethod "$server/dev/device/cabinet?cabinetNo=$cab" -TimeoutSec 5
    if ($st.data.online -eq $true) { $online = $true; break }
  } catch { }
}
Check "heartbeat.accepted" $online "platform sees $cab online (HMAC heartbeat from Python device)"

$devStatus = (Invoke-RestMethod "$device/local/status" -TimeoutSec 5).data
$bootId = $devStatus.bootId
Check "device.bootid-exposed" ($bootId.Length -eq 32) "device bootId=$bootId (32 hex)"

# ---------- 5. negative: forged downlink signature must be rejected 401 ----------
$forged = "0" * 64
$body = @{ cabinetNo = $cab; action = "OPEN_CELL"; commandSeq = 777001; cellNo = 1 } | ConvertTo-Json
try {
  Invoke-RestMethod -Method Post "$device/cmd" -Headers @{ "X-Device-Sign" = $forged } `
    -ContentType "application/json" -Body $body -TimeoutSec 5 | Out-Null
  Check "downlink.forged-signature-401" $false "forged command was accepted (must be 401)"
} catch {
  $code = 0
  if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
  Check "downlink.forged-signature-401" ($code -eq 401) "forged signature -> HTTP $code"
}

# ---------- 6. positive: correctly signed command accepted, replay is idempotent ----------
$seq = 777002
$canon = "$cab|1|$seq"
$good = Hmac-Hex $secret $canon
$body2 = @{ cabinetNo = $cab; action = "OPEN_CELL"; commandSeq = $seq; cellNo = 1 } | ConvertTo-Json
$r1 = Invoke-RestMethod -Method Post "$device/cmd" -Headers @{ "X-Device-Sign" = $good } `
  -ContentType "application/json" -Body $body2 -TimeoutSec 5
Check "downlink.valid-signature-accepted" ($r1.code -eq 0) "first delivery: $($r1.msg)"
Start-Sleep 1
$r2 = Invoke-RestMethod -Method Post "$device/cmd" -Headers @{ "X-Device-Sign" = $good } `
  -ContentType "application/json" -Body $body2 -TimeoutSec 5
Check "downlink.replay-idempotent" ($r2.code -eq 0 -and $r2.msg -match "duplicate") "replay: $($r2.msg)"

# ---------- 7. TAKE loop driven end-to-end through the heterogeneous device ----------
$phone = "13800000008"
$login = Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" `
  -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5
$token = $login.data.token
$headers = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
$take = Invoke-RestMethod -Method Post "$server/user/order" -Headers $headers -ContentType "application/json" `
  -Body (@{ type = "TAKE"; cabinetNo = $cab } | ConvertTo-Json) -TimeoutSec 10
$takeNo = $take.data.orderNo
$opened = Wait-Order $takeNo 2 25
Check "take.order-opened-via-hetero" ($null -ne $opened) "order $takeNo reached OPENED (platform downlink -> device DOOR_OPENED uplink)"
if ($null -ne $opened) {
  Check "take.downlink-targeted-hetero-cabinet" ($opened.cabinetNo -eq $cab) "order cabinet=$($opened.cabinetNo) cell=$($opened.cellNo)"
  $cellNo = $opened.cellNo
  Invoke-RestMethod -Method Post "$device/local/remove" -ContentType "application/json" `
    -Body (@{ cellNo = $cellNo } | ConvertTo-Json) -TimeoutSec 5 | Out-Null
  $done = Wait-Order $takeNo 5 25
  Check "take.order-completed" ($null -ne $done) "BATTERY_OUT from python device completed the order"
  if ($null -ne $done) {
    Check "take.paytype-plan" ($done.payType -eq "PLAN") "payType=$($done.payType)"
    $held = $done.takeBatteryNo
    Check "take.battery-bound" ($held -ne $null -and $held.Length -gt 0) "rider holds $held"
  }
}

# ---------- 8. RETURN loop (insert battery -> deposit refund, fee 0) ----------
$retHeaders = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
$ret = Invoke-RestMethod -Method Post "$server/user/order" -Headers $retHeaders -ContentType "application/json" `
  -Body (@{ type = "RETURN"; cabinetNo = $cab } | ConvertTo-Json) -TimeoutSec 10
$retNo = $ret.data.orderNo
$retOpened = Wait-Order $retNo 2 25
Check "return.order-opened-via-hetero" ($null -ne $retOpened) "order $retNo reached OPENED"
if ($null -ne $retOpened) {
  Invoke-RestMethod -Method Post "$device/local/insert" -ContentType "application/json" `
    -Body (@{ cellNo = $retOpened.cellNo; batteryNo = $held; soc = 25 } | ConvertTo-Json) -TimeoutSec 5 | Out-Null
  $retDone = Wait-Order $retNo 5 25
  Check "return.order-completed" ($null -ne $retDone) "BATTERY_IN from python device completed the return"
  if ($null -ne $retDone) {
    Check "return.fee-zero" ($retDone.feeFen -eq 0) "feeFen=$($retDone.feeFen)"
    Check "return.battery-recorded" ($retDone.returnBatteryNo -eq $held) "returned=$($retDone.returnBatteryNo) held=$held"
  }
  $wallet = (Invoke-RestMethod "$server/user/wallet" -Headers @{ "X-User-Token" = $token } -TimeoutSec 5).data
  Check "return.deposit-refunded" ($wallet.depositFen -eq 0) "depositFen=$($wallet.depositFen) after return"
}

# ---------- 9. ledger + device counters ----------
$led = (Invoke-RestMethod "$server/dev/device/cabinet?cabinetNo=$cab" -TimeoutSec 5).data
Check "ledger.bootid-owned-by-hetero" ($led.lastBootId -eq $bootId) "platform lastBootId=$($led.lastBootId)"
Check "ledger.seq-advanced" ($led.lastEventSeq -ge 4) "lastEventSeq=$($led.lastEventSeq) (door/out/door/in at minimum)"

$after = (Invoke-RestMethod "$device/local/status" -TimeoutSec 5).data
$c = $after.counters
Check "device.counters-consistent" ($c.cmd_accepted -ge 3 -and $c.sent -ge 4 -and $c.bad_sign -ge 1) `
  ("cmd_accepted=$($c.cmd_accepted) cmd_duplicate=$($c.cmd_duplicate) sent=$($c.sent) bad_sign=$($c.bad_sign) retried=$($c.retried)")
Check "device.seq-monotonic-persisted" ($after.eventSeq -ge $led.lastEventSeq) "device eventSeq=$($after.eventSeq) >= platform ledger"

$devTxt = Read-Shared $devLog
Check "log.door-open-reported" ($devTxt -match "event queued DOOR_OPENED") "device reported DOOR_OPENED with commandSeq"
Check "log.battery-events-reported" (($devTxt -match "event queued BATTERY_OUT") -and ($devTxt -match "event queued BATTERY_IN")) "device reported both battery events"
Check "log.invalid-signature-401" ($devTxt -match "downlink signature INVALID") "device rejected the forged downlink"

# ---------- 10. summary + restore standing topology ----------
$total = $script:pass + $script:fail
$head = @(
  "batch26 P2-13 heterogeneous device drill (non-Java endpoint, stdlib Python)",
  ("run at   : " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss")),
  ("device   : scripts/verify/batch26/hetero_device.py cabinet=$cab downlink=:8600 bootId=$bootId"),
  ("platform : :8400 HTTP channel, sim-base-url=$device (sim stopped for the drill)"),
  ("result   : PASS " + $script:pass + " / " + $total)
)
($head + $script:lines) | Set-Content -Path $out -Encoding UTF8
$head | ForEach-Object { Write-Host $_ }
$script:lines | ForEach-Object { Write-Host $_ }

# stop the python device (netstat-based: no WMI dependency), then restore platform (MQ form) + sim
Write-Host "teardown: stopping device and platform ..."
$devPids = @(netstat -ano | Select-String ":8600\s.*LISTENING" | ForEach-Object { ($_.Line -split '\s+')[-1] } | Sort-Object -Unique)
foreach ($p in $devPids) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
$stopPids = @(netstat -ano | Select-String ":8400\s.*LISTENING" | ForEach-Object { ($_.Line -split '\s+')[-1] } | Sort-Object -Unique)
foreach ($p in $stopPids) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
Start-Sleep 3
Write-Host "restore: starting platform (mq mode) + sim ..."
# No pipe on purpose: piping a launcher that spawns a long-lived JVM keeps the pipeline
# open until the JVM exits (observed hang), so call them plainly.
powershell -ExecutionPolicy Bypass -File (Join-Path $repo ".local\start-server-mq.ps1")
powershell -ExecutionPolicy Bypass -File (Join-Path $repo ".local\start-sim-mq.ps1")
# verify restoration (bounded wait): platform :8400 + sim :8500 both UP
$restored = $false
for ($i = 0; $i -lt 45; $i++) {
  Start-Sleep 2
  $r1 = $false; $r2 = $false
  try { $r1 = (Invoke-RestMethod "$server/actuator/health" -TimeoutSec 3).status -eq "UP" } catch { }
  try { $r2 = (Invoke-RestMethod "http://127.0.0.1:8500/actuator/health" -TimeoutSec 3).status -eq "UP" } catch { }
  if ($r1 -and $r2) { $restored = $true; break }
}
Write-Host "restored: platform (mq mode) + sim (event-channel=mq)  [verified=$restored]"
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
