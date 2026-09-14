# S5 gate: OVERDUE exit paths (business-audit fix regression, batch8)
#   A) OVERDUE SWAP completes on battery return (TAKEN/OVERDUE dual-state acceptance + overdue fee)
#   B) OVERDUE beyond overdue-max-hours -> EXCEPTION + ORDER_OVERDUE alarm (sweep, manual handoff)
# Prereq: MySQL, Redis, swap-sim :8500 running; swap-server NOT running (this script boots two phases itself)
# Note: secrets are read from .local (gitignored), never printed.
param([switch]$PhaseBOnly)
$ErrorActionPreference = "Continue"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$out = Join-Path $PSScriptRoot "_c16_out.txt"
$script:fail = 0
$script:checks = 0
$REQUIRED_CHECKS = 16
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    $script:checks++
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S5 gate: OVERDUE exit paths ==" -Encoding UTF8

$jar = Join-Path $root "swap-server\target\swap-server-1.0.0.jar"
$env:SWAP_DEV_SECRET = (Get-Content (Join-Path $root ".local\dev-secret.txt") -Raw).Trim()
$env:SWAP_ADMIN_TOKEN = (Get-Content (Join-Path $root ".local\admin-token.txt") -Raw).Trim()
$env:SWAP_PAY_SECRET = (Get-Content (Join-Path $root ".local\pay-secret.txt") -Raw).Trim()

$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"

function Get-ListenerPid([int]$port) {
    $line = netstat -ano | Select-String ":$port .*LISTENING" | Select-Object -First 1
    if ($line) { return [int](($line.ToString().Trim() -split '\s+')[-1]) }
    return 0
}
function Wait-PortFree([int]$port, [int]$timeoutSec = 30) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((Get-ListenerPid $port) -eq 0) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}
function Wait-PortUp([int]$port, [int]$timeoutSec = 45) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((Get-ListenerPid $port) -ne 0) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}
function Start-Server([string[]]$extraArgs, [string]$tag) {
    if (-not (Wait-PortFree 8400)) { Log "FATAL port 8400 still occupied"; exit 1 }
    # delay poll 2000ms: keeps TAKEN state observable before OVERDUE fires (gate timing determinism)
    $javaArgs = @('-jar', $jar, '--server.port=8400', '--swap.dev.enabled=true',
        '--swap.delay.poll-interval-ms=2000', '--swap.billing.overdue-hours=0',
        '--management.endpoint.shutdown.enabled=true',
        '--management.endpoints.web.exposure.include=health,shutdown') + $extraArgs
    $logFile = Join-Path $root ".local\_c16_$tag.out.log"
    $p = Start-Process -FilePath java -ArgumentList $javaArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $root ".local\_c16_$tag.err.log")
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        try { $r = Invoke-RestMethod "$server/actuator/health" -TimeoutSec 2; if ($r.status -eq "UP") { Log "server up (tag=$tag)"; return } } catch { }
        Start-Sleep -Milliseconds 500
    }
    Log "FATAL server not up in 90s (tag=$tag)"; exit 1
}
function Stop-Server() {
    try { Invoke-RestMethod -Method Post "$server/actuator/shutdown" -TimeoutSec 5 | Out-Null } catch { }
    if (-not (Wait-PortFree 8400 20)) {
        $listenPid = Get-ListenerPid 8400
        if ($listenPid -gt 0) { Stop-Process -Id $listenPid -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
    if (-not (Wait-PortFree 8400 15)) { Log "FATAL port 8400 not freed"; exit 1 }
    Log "server stopped (port 8400 free)"
}
function Restart-Sim() {
    $listenPid = Get-ListenerPid 8500
    if ($listenPid -gt 0) { Stop-Process -Id $listenPid -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 2 }
    & (Join-Path $root ".local\run-sim.bat")
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        try { $r = Invoke-RestMethod "$sim/actuator/health" -TimeoutSec 2; if ($r.status -eq "UP") { Log "sim up"; return } } catch { }
        Start-Sleep -Milliseconds 500
    }
    Log "FATAL sim not up in 45s"; exit 1
}
function Login($phone) {
    $r = Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5
    return $r.data.token
}
function Wait-Status($orderNo, $expect, $token, $timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-RestMethod "$server/user/order/$orderNo" -Headers @{ "X-User-Token" = $token } -TimeoutSec 5
        if ($r.data.status -eq $expect) { return $r.data }
        Start-Sleep -Milliseconds 300
    }
    return $null
}
function New-Order($type, $token) {
    $h = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
    return Invoke-RestMethod -Method Post "$server/user/order" -Headers $h -ContentType "application/json" -Body (@{ type = $type } | ConvertTo-Json) -TimeoutSec 10
}

# ---- phase A: OVERDUE return completes the order ----
if (-not $PhaseBOnly) {
Restart-Sim
Start-Server @() "A"
$resetOk = $false
try {
    try { Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 15 | Out-Null; Log "dev reset done (phase A)"; $resetOk = $true }
    catch { Log "EXC reset-A: $($_.Exception.Message)"; $script:fail++ }
    try { $t1 = Login "13800000001"; Log "login-A ok" } catch { Log "EXC login-A: $($_.Exception.Message)"; $t1 = $null; $script:fail++ }
    if (-not $resetOk) { Log "FATAL phase A aborted: dev reset failed (dirty state)"; $script:fail++ }
    else {
    $wallet0 = (Invoke-RestMethod "$server/user/wallet" -Headers @{ "X-User-Token" = $t1 } -TimeoutSec 5).data
    Log "u1 initial balance=$($wallet0.balanceFen) deposit=$($wallet0.depositFen) planRemain=$($wallet0.activePlan.remainingTimes)"

    $take = New-Order "TAKE" $t1
    $takeNo = $take.data.orderNo
    $takeOpened = Wait-Status $takeNo 2 $t1
    Check "A1 TAKE opened" ($takeOpened -ne $null)
    Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($takeOpened.cabinetNo)&cellNo=$($takeOpened.cellNo)" -TimeoutSec 5 | Out-Null
    $takeDone = Wait-Status $takeNo 5 $t1
    Check "A2 TAKE completed" ($takeDone -ne $null)
    $held = $takeDone.takeBatteryNo

    $swap = New-Order "SWAP" $t1
    $swapNo = $swap.data.orderNo
    $swapOpened = Wait-Status $swapNo 2 $t1
    Check "A3 SWAP opened" ($swapOpened -ne $null)
    Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)" -TimeoutSec 5 | Out-Null
    Check "A4 SWAP taken" ((Wait-Status $swapNo 3 $t1) -ne $null)
    Check "A5 SWAP overdue (overdue-hours=0)" ((Wait-Status $swapNo 4 $t1 15) -ne $null)
    Invoke-RestMethod -Method Post "$sim/sim/battery/in?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)&batteryNo=$held&soc=15" -TimeoutSec 5 | Out-Null
    $swapDone = Wait-Status $swapNo 5 $t1
    Check "A6 OVERDUE SWAP completed on return" ($swapDone -ne $null)
    Check "A7 returned held battery" ($swapDone -ne $null -and $swapDone.returnBatteryNo -eq $held)
    Check "A8 overdue fee charged (>=100 fen)" ($swapDone -ne $null -and $swapDone.feeFen -ge 100)
    $wallet1 = (Invoke-RestMethod "$server/user/wallet" -Headers @{ "X-User-Token" = $t1 } -TimeoutSec 5).data
    Log "u1 after balance=$($wallet1.balanceFen) deposit=$($wallet1.depositFen) planRemain=$($wallet1.activePlan.remainingTimes)"
    }
} catch {
    Log "EXC phase-A flow: $($_.Exception.Message)"; $script:fail++
} finally {
    Stop-Server
}
}

# ---- phase B: OVERDUE beyond overdue-max-hours -> EXCEPTION + alarm ----
# sweep 8s: OVERDUE window observable (sweep 与 delay 同秒触发时窗口会小于 300ms 轮询，放宽扫把保证 B5 确定性)
$resetOk = $false
try {
    Start-Server @('--swap.billing.overdue-max-hours=0', '--swap.order.sweep-interval-ms=8000') "B"
} catch { Log "EXC start-server-B: $($_.Exception.Message)"; throw }
try {
    try { Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 15 | Out-Null; Log "dev reset done (phase B)"; $resetOk = $true }
    catch { Log "EXC reset-B: $($_.Exception.Message)"; $script:fail++ }
    try { Restart-Sim } catch { Log "EXC restart-sim-B: $($_.Exception.Message)"; $script:fail++ }
    try { $t2 = Login "13800000002"; Log "login-B ok" } catch { Log "EXC login-B: $($_.Exception.Message)"; $t2 = $null; $script:fail++ }
    if (-not $resetOk) { Log "FATAL phase B aborted: dev reset failed (dirty state)"; $script:fail++ }
    else {

    $take2 = New-Order "TAKE" $t2
    $take2No = $take2.data.orderNo
    $take2Opened = Wait-Status $take2No 2 $t2
    Check "B1 TAKE opened" ($take2Opened -ne $null)
    Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($take2Opened.cabinetNo)&cellNo=$($take2Opened.cellNo)" -TimeoutSec 5 | Out-Null
    Check "B2 TAKE completed" ((Wait-Status $take2No 5 $t2) -ne $null)

    $swap2 = New-Order "SWAP" $t2
    $swap2No = $swap2.data.orderNo
    $swap2Opened = Wait-Status $swap2No 2 $t2
    Check "B3 SWAP opened" ($swap2Opened -ne $null)
    Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($swap2Opened.cabinetNo)&cellNo=$($swap2Opened.cellNo)" -TimeoutSec 5 | Out-Null
    Check "B4 SWAP taken" ((Wait-Status $swap2No 3 $t2) -ne $null)
    Check "B5 SWAP overdue" ((Wait-Status $swap2No 4 $t2 15) -ne $null)
    $exc = Wait-Status $swap2No 8 $t2 20
    Check "B6 OVERDUE swept to EXCEPTION" ($exc -ne $null)
    Check "B7 closeReason=OVERDUE_UNRESOLVED" ($exc -ne $null -and $exc.closeReason -eq "OVERDUE_UNRESOLVED")
    $alarms = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=100" -Headers @{ "X-Admin-Token" = $env:SWAP_ADMIN_TOKEN } -TimeoutSec 5).data
    $hit = @($alarms | Where-Object { $_.alarmType -eq "ORDER_OVERDUE" -and $_.deviceNo -eq $swap2No }).Count -gt 0
    Check "B8 ORDER_OVERDUE alarm raised for swap order" $hit
    }
} catch {
    Log "EXC phase-B flow: $($_.Exception.Message)"; $script:fail++
} finally {
    Stop-Server
}

$required = if ($PhaseBOnly) { 8 } else { $REQUIRED_CHECKS }
if ($script:fail -eq 0 -and $script:checks -ge $required) {
    Log "GATE-C16 PASS (checks=$($script:checks)/$required)"
    exit 0
} else {
    if ($script:checks -lt $required) { Log "FATAL checks executed $($script:checks) < required $required (silent-skip guard)" }
    Log "GATE-C16 FAIL fail=$($script:fail) checks=$($script:checks)"
    exit 1
}
