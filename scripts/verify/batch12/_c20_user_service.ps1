# S7 WP-D gate: user service (report->work order / arrears loop / coupon lock-apply-release / messages)
# Prereq: MySQL, Redis, swap-sim :8500 running; swap-server NOT running (script boots it).
# Secrets: .local/dev-secret.txt, .local/admin-token.txt, .local/pay-secret.txt (gitignored).
# NOTE: English-only (PS 5.1 + BOM-less UTF-8). Brace vars before ? in URLs: "${var}?x=y".
$ErrorActionPreference = "Continue"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$out = Join-Path $PSScriptRoot "_c20_out.txt"
$script:fail = 0
$script:checks = 0
$REQUIRED_CHECKS = 15
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    $script:checks++
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S7 gate: user service ==" -Encoding UTF8

$jar = Join-Path $root "swap-server\target\swap-server-1.0.0.jar"
$env:SWAP_DEV_SECRET = (Get-Content (Join-Path $root ".local\dev-secret.txt") -Raw).Trim()
$env:SWAP_ADMIN_TOKEN = (Get-Content (Join-Path $root ".local\admin-token.txt") -Raw).Trim()
$env:SWAP_PAY_SECRET = (Get-Content (Join-Path $root ".local\pay-secret.txt") -Raw).Trim()
$ADMIN = @{ "X-Admin-Token" = $env:SWAP_ADMIN_TOKEN }
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
function Start-Server() {
    if (-not (Wait-PortFree 8400)) { Log "FATAL port 8400 occupied"; exit 1 }
    $javaArgs = @('-jar', $jar, '--server.port=8400', '--swap.dev.enabled=true',
        '--swap.billing.overdue-hours=0',
        '--management.endpoint.shutdown.enabled=true',
        '--management.endpoints.web.exposure.include=health,shutdown')
    Start-Process -FilePath java -ArgumentList $javaArgs -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $root ".local\_c20.out.log") `
        -RedirectStandardError (Join-Path $root ".local\_c20.err.log") | Out-Null
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        try { $r = Invoke-RestMethod "$server/actuator/health" -TimeoutSec 2; if ($r.status -eq "UP") { Log "server up"; return } } catch { }
        Start-Sleep -Milliseconds 500
    }
    Log "FATAL server not up in 90s"; exit 1
}
function Stop-Server() {
    try { Invoke-RestMethod -Method Post "$server/actuator/shutdown" -TimeoutSec 5 | Out-Null } catch { }
    if (-not (Wait-PortFree 8400 20)) {
        $listenPid = Get-ListenerPid 8400
        if ($listenPid -gt 0) { Stop-Process -Id $listenPid -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
    if (-not (Wait-PortFree 8400 15)) { Log "FATAL port 8400 not freed"; exit 1 }
    Log "server stopped"
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
    Log "FATAL sim not up"; exit 1
}
function Http($method, $url, $headers, $body) {
    try {
        $resp = if ($body) {
            Invoke-WebRequest -Method $method -Uri $url -Headers $headers `
                -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 15
        } else {
            Invoke-WebRequest -Method $method -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 15
        }
        return @{ status = [int]$resp.StatusCode; body = $resp.Content }
    } catch {
        $status = 0
        $errBody = ""
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $errBody = $reader.ReadToEnd()
            } catch { }
        }
        return @{ status = $status; body = $errBody }
    }
}
function Sql($q) {
    $out = & mysql -uroot -proot swap_ops -N -e $q 2>$null
    return ($out | Out-String).Trim()
}
function Login($phone) {
    $r = Http "POST" "$server/user/login" $null (@{ phone = $phone } | ConvertTo-Json)
    if ($r.status -ne 200) { return $null }
    return ($r.body | ConvertFrom-Json).data.token
}
function UH($token) { return @{ "X-User-Token" = $token } }
function Wait-Status($orderNo, $expect, $token, $timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Http "GET" "$server/user/order/$orderNo" (UH $token) $null
        if ($r.status -eq 200) {
            $data = ($r.body | ConvertFrom-Json).data
            if ($data.status -eq $expect) { return $data }
        }
        Start-Sleep -Milliseconds 300
    }
    return $null
}
function New-Order($type, $token, $couponId, $cabinet) {
    $payload = @{ type = $type }
    if ($couponId) { $payload.couponId = $couponId }
    if ($cabinet) { $payload.cabinetNo = $cabinet }
    $h = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
    try {
        $resp = Invoke-WebRequest -Method Post -Uri "$server/user/order" -Headers $h `
            -ContentType "application/json" -Body ($payload | ConvertTo-Json) -UseBasicParsing -TimeoutSec 15
        return @{ status = [int]$resp.StatusCode; body = $resp.Content }
    } catch {
        $status = 0
        $errBody = ""
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $errBody = $reader.ReadToEnd()
            } catch { }
        }
        return @{ status = $status; body = $errBody }
    }
}
function Mock-Pay($tradeNo, $result) {
    return & curl.exe -s -X POST "$server/pay/mock/notify/${tradeNo}?result=$result" -H "Content-Type: application/json" -d "{}"
}

Start-Server
try {
    # clean state: dev reset (device side + wallets/plans/coupons/arrears/messages), then align sim
    Http "POST" "$server/dev/device/reset" $null $null | Out-Null
    Log "dev reset done"
    Restart-Sim

    # ---------- A) user report -> work order ----------
    $t1 = Login "13800000001"
    $report = Http "POST" "$server/user/report" (UH $t1) (@{ cabinetNo = "SWAP-C-001"; cellNo = 3;
        type = "DEVICE_FAULT"; description = "door jammed" } | ConvertTo-Json)
    $woNo = ($report.body | ConvertFrom-Json).data.woNo
    Check "1 report-200" ($report.status -eq 200 -and $woNo)
    $again = Http "POST" "$server/user/report" (UH $t1) (@{ cabinetNo = "SWAP-C-001"; cellNo = 3;
        type = "DEVICE_FAULT"; description = "door jammed again" } | ConvertTo-Json)
    Check "2 report-dedupe" (($again.body | ConvertFrom-Json).data.woNo -eq $woNo)

    $page = (Http "GET" "$server/admin/work-order?status=1" $ADMIN $null).body | ConvertFrom-Json
    $wo = @($page.data.list | Where-Object { $_.woNo -eq $woNo })[0]
    $r1 = Http "POST" "$server/admin/work-order/$($wo.id)/triage?severity=HIGH&remark=t" $ADMIN @{}
    $r2 = Http "POST" "$server/admin/work-order/$($wo.id)/assign?handlerId=1&remark=a" $ADMIN @{}
    $r3 = Http "POST" "$server/admin/work-order/$($wo.id)/start?remark=s" $ADMIN @{}
    $r4 = Http "POST" "$server/admin/work-order/$($wo.id)/verify?remark=v" $ADMIN @{}
    $r5 = Http "POST" "$server/admin/work-order/$($wo.id)/close?remark=done" $ADMIN @{}
    Check "3 wo-lifecycle" ($r1.status -eq 200 -and $r2.status -eq 200 -and $r3.status -eq 200 -and $r4.status -eq 200 -and $r5.status -eq 200)
    $msgs = ((Http "GET" "$server/user/messages?unreadOnly=true" (UH $t1) $null).body | ConvertFrom-Json).data
    Check "4 wo-close-message" (@($msgs | Where-Object { $_.type -eq "WORK_ORDER" }).Count -ge 1)

    # ---------- B) arrears loop ----------
    $t2 = Login "13800000002"
    $take = New-Order "TAKE" $t2 $null
    $takeNo = ($take.body | ConvertFrom-Json).data.orderNo
    $takeOpened = Wait-Status $takeNo 2 $t2
    Http "POST" "$sim/sim/battery/out?cabinetNo=$($takeOpened.cabinetNo)&cellNo=$($takeOpened.cellNo)" $null "{}" | Out-Null
    $takeDone = Wait-Status $takeNo 5 $t2
    $held = $takeDone.takeBatteryNo
    $swap = New-Order "SWAP" $t2 $null
    $swapNo = ($swap.body | ConvertFrom-Json).data.orderNo
    $swapOpened = Wait-Status $swapNo 2 $t2
    Http "POST" "$sim/sim/battery/out?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)" $null "{}" | Out-Null
    Wait-Status $swapNo 3 $t2 | Out-Null
    Wait-Status $swapNo 4 $t2 15 | Out-Null
    # drain wallet to force overdue-fee shortfall
    $uid2 = Sql "SELECT id FROM swap_user WHERE phone='13800000002'"
    Sql "UPDATE wallet SET balance_fen=0, deposit_fen=0 WHERE user_id=$uid2" | Out-Null
    Http "POST" "$sim/sim/battery/in?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)&batteryNo=$held&soc=15" $null "{}" | Out-Null
    $swapDone = Wait-Status $swapNo 5 $t2
    $arrears = ((Http "GET" "$server/user/arrears" (UH $t2) $null).body | ConvertFrom-Json).data
    Check "5 arrears-created" ($swapDone -ne $null -and $arrears.totalOpenFen -eq 100)

    $blocked = New-Order "TAKE" $t2 $null
    Check "6 take-blocked-by-arrears" ($blocked.status -eq 400 -and $blocked.body.Contains([char]0x6B20))

    # settle: recharge 500 -> pay arrears
    $recharge = (Http "POST" "$server/user/wallet/recharge" (UH $t2) (@{ amountFen = 500 } | ConvertTo-Json)).body | ConvertFrom-Json
    $mock = Mock-Pay $recharge.data.tradeNo "SUCCESS"
    Check "7 recharge-ok" ($mock.Contains("SUCCESS"))
    $pay = Http "POST" "$server/user/arrears/$($arrears.records[0].id)/pay" (UH $t2) $null
    Check "8 arrears-settled" ($pay.status -eq 200 -and ($pay.body | ConvertFrom-Json).data.status -eq 2)
    $swap2 = New-Order "SWAP" $t2 $null
    Check "9 swap-allowed-after-settle" ($swap2.status -eq 200)

    # ---------- C) coupon lock/apply/release ----------
    # template + grant to user3 (plan removed -> balance path)
    $tmpl = (Http "POST" "$server/admin/coupon/template" $ADMIN (@{ name = "E2E-Coupon-$((Get-Date).ToString('HHmmss'))";
        valueFen = 100; minAmountFen = 100; totalQuantity = 100; perUserLimit = 2; validDays = 30 } | ConvertTo-Json)).body | ConvertFrom-Json
    $t3 = Login "13800000003"
    $uid3 = Sql "SELECT id FROM swap_user WHERE phone='13800000003'"
    Sql "DELETE FROM user_plan WHERE user_id=$uid3" | Out-Null
    $grant = (Http "POST" "$server/admin/coupon/grant?templateId=$($tmpl.data.id)&userIds=$uid3" $ADMIN @{}).body | ConvertFrom-Json
    Check "10 coupon-granted" ($grant.data.granted -ge 1)
    $coupons = ((Http "GET" "$server/user/coupons?status=1" (UH $t3) $null).body | ConvertFrom-Json).data
    $couponId = @($coupons)[0].id

    # stuck-door path: lock coupon then release via send-failure compensation (deterministic)
    Http "POST" "$sim/sim/fault?cabinetNo=SWAP-C-002&doorStuck=true" $null "{}" | Out-Null
    $stuck = New-Order "TAKE" $t3 $couponId "SWAP-C-002"
    Log "stuck-order status=$($stuck.status)"
    $afterStuck = ((Http "GET" "$server/user/coupons?status=1" (UH $t3) $null).body | ConvertFrom-Json).data
    Check "11 coupon-released-on-send-fail" (@($afterStuck | Where-Object { $_.id -eq $couponId }).Count -eq 1)

    # unstick -> normal TAKE with coupon -> discount applied + coupon USED
    Http "POST" "$sim/sim/fault?cabinetNo=SWAP-C-002&doorStuck=false" $null "{}" | Out-Null
    $take3 = New-Order "TAKE" $t3 $couponId "SWAP-C-002"
    $take3No = ($take3.body | ConvertFrom-Json).data.orderNo
    $take3Opened = Wait-Status $take3No 2 $t3
    Http "POST" "$sim/sim/battery/out?cabinetNo=$($take3Opened.cabinetNo)&cellNo=$($take3Opened.cellNo)" $null "{}" | Out-Null
    $take3Done = Wait-Status $take3No 5 $t3
    Check "12 coupon-discount-applied" ($take3Done -ne $null -and $take3Done.feeFen -eq 200)
    $used = ((Http "GET" "$server/user/coupons?status=3" (UH $t3) $null).body | ConvertFrom-Json).data
    Check "13 coupon-used" (@($used | Where-Object { $_.id -eq $couponId }).Count -eq 1)
    $detail = ((Http "GET" "$server/admin/order/$take3No" $ADMIN $null).body | ConvertFrom-Json).data
    $types = @($detail.payments | ForEach-Object { $_.paymentType })
    Check "14 coupon-deduct-payment-record" ($types -contains "COUPON_DEDUCT")

    # user2 settle message (station inbox chain)
    $msgs2 = ((Http "GET" "$server/user/messages" (UH $t2) $null).body | ConvertFrom-Json).data
    Check "15 arrears-settle-message" (@($msgs2 | Where-Object { $_.type -eq "ARREARS" }).Count -ge 1)
} finally {
    Stop-Server
}

if ($script:fail -eq 0 -and $script:checks -ge $REQUIRED_CHECKS) {
    Log "GATE-C20 PASS (checks=$($script:checks)/$REQUIRED_CHECKS)"
    exit 0
} else {
    if ($script:checks -lt $REQUIRED_CHECKS) { Log "FATAL checks executed $($script:checks) < required $REQUIRED_CHECKS" }
    Log "GATE-C20 FAIL fail=$($script:fail) checks=$($script:checks)"
    exit 1
}
