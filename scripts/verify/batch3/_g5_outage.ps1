# S3 gate script: outage / retry / overdue / recharge idempotency (S3.2/S3.3/S3.4/S3.6)
# Prereq (fast-mode test server):
#   server :8400 started with:
#     --swap.device.heartbeat-timeout-seconds=5
#     --swap.alarm.offline-scan-interval-ms=2000
#     --swap.billing.overdue-hours=0
#   sim :8500 dev-enabled=true; admin token in .local/admin-token.txt; pay secret in .local/pay-secret.txt
# Evidence: recharge callback idempotent (balance +once), SWAP order -> OVERDUE + ORDER_OVERDUE alarm,
#           sim down -> BATCH_OFFLINE (10 cabinets), sim up -> alarm auto-recovered.
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_g5_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S3 gate: outage/retry/overdue/recharge ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$paySecret = (Get-Content (Join-Path $local "pay-secret.txt") -Raw).Trim()

function Sign-Pay($tradeNo, $result) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($paySecret)
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$tradeNo|$result"))
    return (-join ($hash | ForEach-Object { $_.ToString("x2") }))
}

# ---- 0) pick a clean seeded user (no active order / no held battery) ----
$phones = 25..2 | ForEach-Object { "138" + $_.ToString("00000000") }
$token = $null
$H = $null
$phone = $null
$pendingTake = $null
foreach ($p in $phones) {
    try {
        $login = Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $p } | ConvertTo-Json) -TimeoutSec 5
        $candidate = $login.data.token
        $h1 = @{ "X-User-Token" = $candidate; "Idempotency-Key" = [guid]::NewGuid().ToString() }
        $t = Invoke-RestMethod -Method Post "$server/user/order" -Headers $h1 -ContentType "application/json" -Body (@{ type = "TAKE" } | ConvertTo-Json) -TimeoutSec 10
        $phone = $p; $token = $candidate; $H = @{ "X-User-Token" = $candidate }; $pendingTake = $t
        Log "selected clean user phone=$p (TAKE created orderNo=$($t.data.orderNo))"
        break
    } catch { Log "skip phone=$p cause=$($_.Exception.Message)" }
}
if ($phone -eq $null) { Log "no usable seeded user found"; exit 1 }
$wallet0 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data.balanceFen
$recharge = (Invoke-RestMethod -Method Post "$server/user/wallet/recharge" -Headers $H -ContentType "application/json" -Body (@{ amountFen = 20000 } | ConvertTo-Json) -TimeoutSec 5).data
$tradeNo = $recharge.tradeNo
Log "recharge created tradeNo=$tradeNo amountFen=$($recharge.amountFen) balanceBefore=$wallet0"
$sign = Sign-Pay $tradeNo "SUCCESS"
$cbBody = @{ tradeNo = $tradeNo; result = "SUCCESS"; sign = $sign } | ConvertTo-Json
$cb1 = (Invoke-RestMethod -Method Post "$server/pay/callback" -ContentType "application/json" -Body $cbBody -TimeoutSec 5).data
$wallet1 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data.balanceFen
Check "callback 1 SUCCESS" ($cb1.status -eq "SUCCESS")
Check "balance credited once (+20000)" ($wallet1 -eq ($wallet0 + 20000))
$cb2 = (Invoke-RestMethod -Method Post "$server/pay/callback" -ContentType "application/json" -Body $cbBody -TimeoutSec 5).data
$wallet2 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data.balanceFen
Check "callback 2 idempotent (no double credit)" ($cb2.status -eq "SUCCESS" -and $wallet2 -eq $wallet1)
$badSign = Sign-Pay $tradeNo "FAIL"
try {
    Invoke-RestMethod -Method Post "$server/pay/callback" -ContentType "application/json" -Body (@{ tradeNo = $tradeNo; result = "SUCCESS"; sign = $badSign } | ConvertTo-Json) -TimeoutSec 5 | Out-Null
    Check "tampered sign rejected" $false
} catch { Check "tampered sign rejected" ($_.Exception.Response.StatusCode.value__ -ge 400) }

# ---- 2) SWAP order -> TAKEN -> OVERDUE (overdue-hours=0 fast mode) + alarm ----
function Wait-Status($orderNo, $expect, $timeoutSec = 15) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-RestMethod "$server/user/order/$orderNo" -Headers $H -TimeoutSec 5
        if ($r.data.status -eq $expect) { return $r.data }
        Start-Sleep -Milliseconds 400
    }
    return $null
}
function New-Order($type) {
    $headers = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
    return Invoke-RestMethod -Method Post "$server/user/order" -Headers $headers -ContentType "application/json" -Body (@{ type = $type } | ConvertTo-Json) -TimeoutSec 10
}

$take = $pendingTake
$takeNo = $take.data.orderNo
$takeOpened = Wait-Status $takeNo 2
Check "TAKE opened" ($takeOpened -ne $null)
Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($takeOpened.cabinetNo)&cellNo=$($takeOpened.cellNo)" -TimeoutSec 5 | Out-Null
Check "TAKE completed" ((Wait-Status $takeNo 5) -ne $null)

$swap = New-Order "SWAP"
$swapNo = $swap.data.orderNo
$swapOpened = Wait-Status $swapNo 2
Check "SWAP opened" ($swapOpened -ne $null)
Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)" -TimeoutSec 5 | Out-Null
# fast mode: TAKEN 窗口可能短于轮询间隔（overdue-hours=0），接受 TAKEN 或已 OVERDUE
$taken = $null
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline) {
    $r = Invoke-RestMethod "$server/user/order/$swapNo" -Headers $H -TimeoutSec 5
    if ($r.data.status -eq 3 -or $r.data.status -eq 4) { $taken = $r.data; break }
    Start-Sleep -Milliseconds 300
}
Check "SWAP taken (TAKEN or already OVERDUE)" ($taken -ne $null)
$overdue = Wait-Status $swapNo 4 15
Check "overdue migrated (OVERDUE)" ($overdue -ne $null)
Start-Sleep -Seconds 2
$alarms = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=200" -Headers $admin -TimeoutSec 5).data
$overdueAlarm = $alarms | Where-Object { $_.alarmType -eq "ORDER_OVERDUE" -and $_.deviceNo -eq $swapNo }
Check "ORDER_OVERDUE alarm raised" ($overdueAlarm -ne $null)

# ---- 3) sim outage -> BATCH_OFFLINE; recovery -> auto close ---- 
# wait until any stale BATCH alarm from previous runs auto-recovers
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
    $stale = (Get-Unhandled) | Where-Object { $_.alarmType -eq "BATCH_OFFLINE" }
    if ($stale -eq $null) { break }
    Start-Sleep -Seconds 2
}
$simPids = Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'javaw?.exe' -and $_.CommandLine -match 'swap-sim-1.0.0.jar' } | Select-Object -ExpandProperty ProcessId
Log "stopping sim pids=$($simPids -join ',') (outage simulation)"
foreach ($procId in $simPids) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 12
$alarmsDown = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=200" -Headers $admin -TimeoutSec 5).data
$batch = $alarmsDown | Where-Object { $_.alarmType -eq "BATCH_OFFLINE" }
Check "BATCH_OFFLINE raised while sim down" ($batch -ne $null)
Log "restarting sim (dual channel launcher)"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$local\run-sim-dual.bat`"" -WindowStyle Hidden
Start-Sleep -Seconds 25
$alarmsUp = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=200" -Headers $admin -TimeoutSec 5).data
$batchOpen = $alarmsUp | Where-Object { $_.alarmType -eq "BATCH_OFFLINE" }
Check "BATCH_OFFLINE auto-recovered after sim up" ($batchOpen -eq $null)

if ($script:fail -eq 0) { Log "GATE-5 PASS"; exit 0 } else { Log "GATE-5 FAIL checks=$($script:fail)"; exit 1 }
