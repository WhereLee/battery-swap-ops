# S4-pre gate: dev data reset (idempotent) + E2E re-runnability smoke
# Prereq: server :8400 (dev enabled), sim :8500 up
# Evidence: reset twice (idempotent); fresh TAKE order -> sim battery out -> COMPLETED
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$out = Join-Path $PSScriptRoot "_c0_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S4-pre gate: dev reset ==" -Encoding UTF8

$r1 = (Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60).data
Log "reset#1 cabinets=$($r1.cabinets) cancelled=$($r1.ordersCancelled) occupied=$($r1.cellsOccupied) extras=$($r1.extrasParked)"
Check "reset#1 occupied = cabinets x full(6)" ($r1.cellsOccupied -eq ($r1.cabinets * 6))

$r2 = (Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60).data
Log "reset#2 cancelled=$($r2.ordersCancelled) occupied=$($r2.cellsOccupied) extras=$($r2.extrasParked)"
Check "reset#2 idempotent (no active orders cancelled)" ($r2.ordersCancelled -eq 0)
Check "reset#2 occupied stable" ($r2.cellsOccupied -eq $r1.cellsOccupied)

# ---- E2E smoke: TAKE -> battery out -> COMPLETED ----
function Wait-Status($orderNo, $expect, $timeoutSec = 15) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-RestMethod "$server/user/order/$orderNo" -Headers $H -TimeoutSec 5
        if ($r.data.status -eq $expect) { return $r.data }
        Start-Sleep -Milliseconds 300
    }
    return $null
}

$token = $null
$H = $null
foreach ($i in 1..5) {
    $phone = "138" + $i.ToString("00000000")
    $login = Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5
    $candidate = $login.data.token
    $headers = @{ "X-User-Token" = $candidate; "Idempotency-Key" = [guid]::NewGuid().ToString() }
    try {
        $take = Invoke-RestMethod -Method Post "$server/user/order" -Headers $headers -ContentType "application/json" -Body '{"type":"TAKE"}' -TimeoutSec 10
        $token = $candidate
        $H = @{ "X-User-Token" = $candidate }
        Log "smoke user=$phone orderNo=$($take.data.orderNo)"
        break
    } catch { Log "try user=$phone rejected: $($_.Exception.Message)" }
}
Check "smoke order created after reset" ($token -ne $null)

if ($token -ne $null) {
    $orderNo = $take.data.orderNo
    $opened = Wait-Status $orderNo 2
    Check "order opened (2)" ($opened -ne $null)
    if ($opened -ne $null) {
        Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($opened.cabinetNo)&cellNo=$($opened.cellNo)" -TimeoutSec 5 | Out-Null
        $done = Wait-Status $orderNo 5
        Check "order completed (5) - E2E re-runnable" ($done -ne $null)
    }
}

if ($script:fail -eq 0) { Log "GATE-DEV-RESET PASS"; exit 0 } else { Log "GATE-DEV-RESET FAIL checks=$($script:fail)"; exit 1 }
