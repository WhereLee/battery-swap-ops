# S7 WP-C gate: channel recon T+1 (export with anomalies -> import -> 4 diff types -> handle -> rebuild keeps HANDLED)
# Prereq: MySQL, Redis running; swap-server NOT running (script boots it; sim/MQ not needed).
# Secrets: .local/dev-secret.txt, .local/admin-token.txt, .local/pay-secret.txt (gitignored).
# NOTE: keep this file English-only (PS 5.1 + BOM-less UTF-8: CJK literals corrupt parsing).
# PS trap: "$var?query=..." parses the var as {var?query} -> always brace: "${var}?query=..."
$ErrorActionPreference = "Continue"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$out = Join-Path $PSScriptRoot "_c19_out.txt"
$script:fail = 0
$script:checks = 0
$REQUIRED_CHECKS = 10
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    $script:checks++
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S7 gate: channel recon ==" -Encoding UTF8

$jar = Join-Path $root "swap-server\target\swap-server-1.0.0.jar"
$env:SWAP_DEV_SECRET = (Get-Content (Join-Path $root ".local\dev-secret.txt") -Raw).Trim()
$env:SWAP_ADMIN_TOKEN = (Get-Content (Join-Path $root ".local\admin-token.txt") -Raw).Trim()
$env:SWAP_PAY_SECRET = (Get-Content (Join-Path $root ".local\pay-secret.txt") -Raw).Trim()
$ADMIN = @{ "X-Admin-Token" = $env:SWAP_ADMIN_TOKEN }
$server = "http://127.0.0.1:8400/api"
$date = (Get-Date).ToString("yyyy-MM-dd")

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
        '--management.endpoint.shutdown.enabled=true',
        '--management.endpoints.web.exposure.include=health,shutdown')
    Start-Process -FilePath java -ArgumentList $javaArgs -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $root ".local\_c19.out.log") `
        -RedirectStandardError (Join-Path $root ".local\_c19.err.log") | Out-Null
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
function Import-Bill($csv) {
    $payload = @{ csv = $csv } | ConvertTo-Json
    return Http "POST" "$server/admin/recon/import?date=$date" $ADMIN $payload
}
function Get-Export($anomaly) {
    $url = "$server/pay/mock/bill/export?date=$date"
    if ($anomaly) { $url = "$url&anomaly=$anomaly" }
    return (Http "GET" $url $null $null).body
}
function Get-Report() {
    return ((Http "GET" "$server/admin/recon/report?date=$date" $ADMIN $null).body | ConvertFrom-Json).data
}
function Get-OpenDiffs() {
    return @(((Http "GET" "$server/admin/recon/diff?date=$date&status=OPEN" $ADMIN $null).body | ConvertFrom-Json).data)
}

Start-Server
try {
    # prepare platform payments for today: one SUCCESS + one CLOSED (fresh user avoids 3/min recharge limit)
    $phone = "1380000000" + (Get-Random -Minimum 2 -Maximum 9)
    Log "pay user $phone"
    $login = (Http "POST" "$server/user/login" $null (@{ phone = $phone } | ConvertTo-Json)).body | ConvertFrom-Json
    $u = @{ "X-User-Token" = $login.data.token }
    $r1 = (Http "POST" "$server/user/wallet/recharge" $u (@{ amountFen = 1000 } | ConvertTo-Json)).body | ConvertFrom-Json
    $trade1 = $r1.data.tradeNo
    $r2 = (Http "POST" "$server/user/wallet/recharge" $u (@{ amountFen = 500 } | ConvertTo-Json)).body | ConvertFrom-Json
    $trade2 = $r2.data.tradeNo
    $n1 = & curl.exe -s -X POST "$server/pay/mock/notify/${trade1}?result=SUCCESS" -H "Content-Type: application/json" -d "{}"
    $n2 = & curl.exe -s -X POST "$server/pay/mock/notify/${trade2}?result=FAIL" -H "Content-Type: application/json" -d "{}"
    Log "notify1=$($n1.Length)bytes notify2=$($n2.Length)bytes"

    # 1) clean bill -> zero diff
    $r = Import-Bill (Get-Export $null)
    Check "1 clean-import-200" ($r.status -eq 200)
    $report = Get-Report
    Check "2 clean-zero-open" ($report.openDiffs -eq 0)

    # 2) anomaly missing -> PLATFORM_ONLY
    Import-Bill (Get-Export "missing") | Out-Null
    $report = Get-Report
    Check "3 missing->platform-only" ($report.byType.PLATFORM_ONLY -ge 1)

    # 3) anomaly extra -> CHANNEL_ONLY (RFAKE row is always a fresh OPEN diff key)
    Import-Bill (Get-Export "extra") | Out-Null
    $report = Get-Report
    Check "4 extra->channel-only" ($report.byType.CHANNEL_ONLY -ge 1)

    # 4) handle one fresh OPEN diff (do it now: later imports rebuild/delete OPEN rows)
    $openDiffs = Get-OpenDiffs
    Log "open diffs after extra=$($openDiffs.Count)"
    $first = $openDiffs[0]
    $h = Http "POST" "$server/admin/recon/diff/$($first.id)/handle?status=HANDLED&remark=checked" $ADMIN @{}
    Check "5 handle-200" ($h.status -eq 200)

    # 5) anomaly amount -> AMOUNT_MISMATCH
    Import-Bill (Get-Export "amount") | Out-Null
    $report = Get-Report
    Check "6 amount->amount-mismatch" ($report.byType.AMOUNT_MISMATCH -ge 1)

    # 6) anomaly status -> STATUS_MISMATCH
    Import-Bill (Get-Export "status") | Out-Null
    $report = Get-Report
    Check "7 status->status-mismatch" ($report.byType.STATUS_MISMATCH -ge 1)

    # 7) alarm chain (dedup window tolerant: any CHANNEL_RECON_DIFF alarm row, open or handled)
    $openAlarms = @(((Http "GET" "$server/admin/alarm?handled=0&limit=200" $ADMIN $null).body | ConvertFrom-Json).data)
    $doneAlarms = @(((Http "GET" "$server/admin/alarm?handled=1&limit=200" $ADMIN $null).body | ConvertFrom-Json).data)
    $hit = @($openAlarms | Where-Object { $_.alarmType -eq "CHANNEL_RECON_DIFF" }).Count -ge 1
    if (-not $hit) { $hit = @($doneAlarms | Where-Object { $_.alarmType -eq "CHANNEL_RECON_DIFF" }).Count -ge 1 }
    Check "8 recon-diff-alarm" $hit

    # 8) re-import clean -> OPEN cleared, HANDLED retained
    Import-Bill (Get-Export $null) | Out-Null
    $report = Get-Report
    $handled = @(((Http "GET" "$server/admin/recon/diff?date=$date&status=HANDLED" $ADMIN $null).body | ConvertFrom-Json).data)
    Check "9 reimport-open-cleared" ($report.openDiffs -eq 0)
    Check "10 handled-retained" ($handled.Count -ge 1)
} finally {
    Stop-Server
}

if ($script:fail -eq 0 -and $script:checks -ge $REQUIRED_CHECKS) {
    Log "GATE-C19 PASS (checks=$($script:checks)/$REQUIRED_CHECKS)"
    exit 0
} else {
    if ($script:checks -lt $REQUIRED_CHECKS) { Log "FATAL checks executed $($script:checks) < required $REQUIRED_CHECKS" }
    Log "GATE-C19 FAIL fail=$($script:fail) checks=$($script:checks)"
    exit 1
}
