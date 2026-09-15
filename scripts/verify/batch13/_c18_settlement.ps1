# S7 WP-B gate: agent settlement (plan-times basis / cash split / refund reversal / arrears settle line / statement flow / report)
# Prereq: MySQL, Redis, swap-sim :8500 running; swap-server NOT running (script boots it).
# Secrets: .local/dev-secret.txt, .local/admin-token.txt, .local/pay-secret.txt (gitignored).
# NOTE: English-only (PS 5.1 + BOM-less UTF-8). Brace vars before ? in URLs: "${var}?x=y".
$ErrorActionPreference = "Continue"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$out = Join-Path $PSScriptRoot "_c18_out.txt"
$script:fail = 0
$script:checks = 0
$REQUIRED_CHECKS = 12
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    $script:checks++
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S7 gate: agent settlement ==" -Encoding UTF8

$jar = Join-Path $root "swap-server\target\swap-server-1.0.0.jar"
$env:SWAP_DEV_SECRET = (Get-Content (Join-Path $root ".local\dev-secret.txt") -Raw).Trim()
$env:SWAP_ADMIN_TOKEN = (Get-Content (Join-Path $root ".local\admin-token.txt") -Raw).Trim()
$env:SWAP_PAY_SECRET = (Get-Content (Join-Path $root ".local\pay-secret.txt") -Raw).Trim()
$ADMIN = @{ "X-Admin-Token" = $env:SWAP_ADMIN_TOKEN }
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$AGENT_CABINET = "SWAP-C-009"

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
        -RedirectStandardOutput (Join-Path $root ".local\_c18.out.log") `
        -RedirectStandardError (Join-Path $root ".local\_c18.err.log") | Out-Null
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
    $out = Sql-Raw $q
    return ($out | Out-String).Trim()
}
function Sql-Raw($q) {
    return & mysql -uroot -proot swap_ops -N -e $q 2>$null
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
function New-Order($type, $token, $cabinet) {
    $payload = @{ type = $type }
    if ($cabinet) { $payload.cabinetNo = $cabinet }
    $h = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
    return Http "POST" "$server/user/order" $h ($payload | ConvertTo-Json)
}
function Ledger($orderNo) {
    $r = Http "GET" "$server/admin/settlement/ledger?orderNo=$orderNo" $ADMIN $null
    return @(($r.body | ConvertFrom-Json).data)
}

Start-Server
try {
    Http "POST" "$server/dev/device/reset" $null $null | Out-Null
    Log "dev reset done"
    Restart-Sim
    $uid1 = Sql "SELECT id FROM swap_user WHERE phone='13800000001'"

    # ---------- A) plan-times basis (agent cabinet, plan deduction) ----------
    $t1 = Login "13800000001"
    $take1 = New-Order "TAKE" $t1 $AGENT_CABINET
    $take1No = ($take1.body | ConvertFrom-Json).data.orderNo
    $o1 = Wait-Status $take1No 2 $t1
    Http "POST" "$sim/sim/battery/out?cabinetNo=$($o1.cabinetNo)&cellNo=$($o1.cellNo)" $null "{}" | Out-Null
    $d1 = Wait-Status $take1No 5 $t1
    $l1 = Ledger $take1No
    $planLine = @($l1 | Where-Object { $_.eventType -eq "ORDER" })[0]
    Check "1 plan-times-basis-300" ($d1 -ne $null -and $planLine.baseType -eq "PLAN_TIMES" -and $planLine.baseAmountFen -eq 300)
    Check "2 plan-times-split-180-120" ($planLine.agentShareFen -eq 180 -and $planLine.platformShareFen -eq 120)

    # ---------- B) cash split + refund reversal ----------
    $t2 = Login "13800000002"
    $uid2 = Sql "SELECT id FROM swap_user WHERE phone='13800000002'"
    Sql "DELETE FROM user_plan WHERE user_id=$uid2" | Out-Null
    $take2 = New-Order "TAKE" $t2 $AGENT_CABINET
    $take2No = ($take2.body | ConvertFrom-Json).data.orderNo
    $o2 = Wait-Status $take2No 2 $t2
    Http "POST" "$sim/sim/battery/out?cabinetNo=$($o2.cabinetNo)&cellNo=$($o2.cellNo)" $null "{}" | Out-Null
    $d2 = Wait-Status $take2No 5 $t2
    $cashLine = @((Ledger $take2No) | Where-Object { $_.eventType -eq "ORDER" })[0]
    Check "3 cash-basis-300" ($d2 -ne $null -and $cashLine.baseType -eq "CASH" -and $cashLine.baseAmountFen -eq 300)
    $reversal = Http "POST" "$server/admin/refund/$take2No/reversal?amountFen=100" $ADMIN "{}"
    $revLine = @((Ledger $take2No) | Where-Object { $_.eventType -eq "REFUND_REVERSAL" })[0]
    Check "4 reversal-refund-ok" ($reversal.status -eq 200)
    Check "5 reversal-negative-line" ($revLine.baseAmountFen -eq -100 -and $revLine.agentShareFen -eq -60 -and $revLine.platformShareFen -eq -40)

    # ---------- C) arrears settle line ----------
    $t3 = Login "13800000003"
    $uid3 = Sql "SELECT id FROM swap_user WHERE phone='13800000003'"
    Sql "DELETE FROM user_plan WHERE user_id=$uid3" | Out-Null
    $take3 = New-Order "TAKE" $t3 $AGENT_CABINET
    $take3No = ($take3.body | ConvertFrom-Json).data.orderNo
    $o3 = Wait-Status $take3No 2 $t3
    Http "POST" "$sim/sim/battery/out?cabinetNo=$($o3.cabinetNo)&cellNo=$($o3.cellNo)" $null "{}" | Out-Null
    $held3 = (Wait-Status $take3No 5 $t3).takeBatteryNo
    $swap3 = New-Order "SWAP" $t3 $AGENT_CABINET
    $swap3No = ($swap3.body | ConvertFrom-Json).data.orderNo
    $s3o = Wait-Status $swap3No 2 $t3
    Http "POST" "$sim/sim/battery/out?cabinetNo=$($s3o.cabinetNo)&cellNo=$($s3o.cellNo)" $null "{}" | Out-Null
    Wait-Status $swap3No 3 $t3 | Out-Null
    Wait-Status $swap3No 4 $t3 15 | Out-Null
    Sql "UPDATE wallet SET balance_fen=0, deposit_fen=0 WHERE user_id=$uid3" | Out-Null
    Http "POST" "$sim/sim/battery/in?cabinetNo=$($s3o.cabinetNo)&cellNo=$($s3o.cellNo)&batteryNo=$held3&soc=15" $null "{}" | Out-Null
    $d3 = Wait-Status $swap3No 5 $t3
    $arrears3 = ((Http "GET" "$server/user/arrears" (UH $t3) $null).body | ConvertFrom-Json).data
    $recharge = (Http "POST" "$server/user/wallet/recharge" (UH $t3) (@{ amountFen = 500 } | ConvertTo-Json)).body | ConvertFrom-Json
    & curl.exe -s -X POST "$server/pay/mock/notify/$($recharge.data.tradeNo)?result=SUCCESS" -H "Content-Type: application/json" -d "{}" | Out-Null
    $pay = Http "POST" "$server/user/arrears/$($arrears3.records[0].id)/pay" (UH $t3) $null
    $arrLine = @((Ledger $swap3No) | Where-Object { $_.eventType -eq "ARREARS_SETTLE" })[0]
    Check "6 arrears-settle-line-400" ($d3 -ne $null -and $pay.status -eq 200 -and $arrLine.baseAmountFen -eq 400)

    # ---------- D) statement flow + report ----------
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $agents = ((Http "GET" "$server/admin/agent" $ADMIN $null).body | ConvertFrom-Json).data
    $ag1 = @($agents | Where-Object { $_.agentNo -eq "AG001" })[0]
    $gen = Http "POST" "$server/admin/settlement/generate?agentId=$($ag1.id)&periodStart=$($now - 3600000)&periodEnd=$($now + 3600000)" $ADMIN "{}"
    Check "7 statement-generate-200" ($gen.status -eq 200)
    $stmt = ($gen.body | ConvertFrom-Json).data
    $detail = ((Http "GET" "$server/admin/settlement/$($stmt.id)" $ADMIN $null).body | ConvertFrom-Json).data
    $lineBase = 0; $lineAgent = 0; $linePlatform = 0
    foreach ($ln in @($detail.lines)) { $lineBase += $ln.baseAmountFen; $lineAgent += $ln.agentShareFen; $linePlatform += $ln.platformShareFen }
    Check "8 statement-sums-consistent" ($detail.statement.baseAmountFen -eq $lineBase -and $detail.statement.agentAmountFen -eq $lineAgent -and $detail.statement.platformAmountFen -eq $linePlatform -and $lineBase -gt 0)
    $gen2 = Http "POST" "$server/admin/settlement/generate?agentId=$($ag1.id)&periodStart=$($now - 3600000)&periodEnd=$($now + 3600000)" $ADMIN "{}"
    Check "9 statement-duplicate-rejected" ($gen2.status -eq 400)
    $con = Http "POST" "$server/admin/settlement/$($stmt.id)/confirm" $ADMIN "{}"
    $paid = Http "POST" "$server/admin/settlement/$($stmt.id)/paid" $ADMIN "{}"
    $paidAgain = Http "POST" "$server/admin/settlement/$($stmt.id)/paid" $ADMIN "{}"
    Check "10 statement-confirm-paid" ($con.status -eq 200 -and ($con.body | ConvertFrom-Json).data.status -eq 2 -and $paid.status -eq 200 -and ($paid.body | ConvertFrom-Json).data.status -eq 3)
    Check "11 statement-paid-immutable" ($paidAgain.status -eq 400)
    $report = ((Http "GET" "$server/admin/settlement/report?from=$($now - 3600000)&to=$($now + 3600000)" $ADMIN $null).body | ConvertFrom-Json).data
    $bucket = @($report.agents | Where-Object { $_.agentNo -eq "AG001" })[0]
    Check "12 report-agent-bucket" ($bucket -ne $null -and $bucket.agentAmountFen -gt 0 -and $bucket.lines -ge 3)
} finally {
    Stop-Server
}

if ($script:fail -eq 0 -and $script:checks -ge $REQUIRED_CHECKS) {
    Log "GATE-C18 PASS (checks=$($script:checks)/$REQUIRED_CHECKS)"
    exit 0
} else {
    if ($script:checks -lt $REQUIRED_CHECKS) { Log "FATAL checks executed $($script:checks) < required $REQUIRED_CHECKS" }
    Log "GATE-C18 FAIL fail=$($script:fail) checks=$($script:checks)"
    exit 1
}
