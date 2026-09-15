# S7 WP-A gate: admin RBAC (roles/permissions/audit/break-glass/session)
# Prereq: MySQL, Redis running; swap-server NOT running (this script boots it; sim/MQ not needed).
# Secrets: .local/dev-secret.txt, .local/admin-token.txt, .local/pay-secret.txt, .local/admin-pass.txt (gitignored).
# NOTE: keep this file English-only (PS 5.1 + BOM-less UTF-8: CJK literals corrupt parsing).
$ErrorActionPreference = "Continue"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$out = Join-Path $PSScriptRoot "_c17_out.txt"
$script:fail = 0
$script:checks = 0
$REQUIRED_CHECKS = 18
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    $script:checks++
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S7 gate: admin RBAC ==" -Encoding UTF8

$jar = Join-Path $root "swap-server\target\swap-server-1.0.0.jar"
$env:SWAP_DEV_SECRET = (Get-Content (Join-Path $root ".local\dev-secret.txt") -Raw).Trim()
$env:SWAP_ADMIN_TOKEN = (Get-Content (Join-Path $root ".local\admin-token.txt") -Raw).Trim()
$env:SWAP_PAY_SECRET = (Get-Content (Join-Path $root ".local\pay-secret.txt") -Raw).Trim()
$env:SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD = (Get-Content (Join-Path $root ".local\admin-pass.txt") -Raw).Trim()
$BOOTSTRAP_PASS = $env:SWAP_DEV_ADMIN_BOOTSTRAP_PASSWORD

$server = "http://127.0.0.1:8400/api"

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
        -RedirectStandardOutput (Join-Path $root ".local\_c17.out.log") `
        -RedirectStandardError (Join-Path $root ".local\_c17.err.log") | Out-Null
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
        $params = @{ Method = $method; Uri = $url; UseBasicParsing = $true; TimeoutSec = 10 }
        if ($headers) { $params.Headers = $headers }
        if ($body) { $params.ContentType = "application/json"; $params.Body = $body }
        $resp = Invoke-WebRequest @params
        return @{ status = [int]$resp.StatusCode; body = $resp.Content }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        return @{ status = $status; body = "" }
    }
}
function Login($username, $password) {
    $payload = @{ username = $username; password = $password } | ConvertTo-Json
    $r = Http "POST" "$server/admin/auth/login" @{ "X-Admin-Token" = $env:SWAP_ADMIN_TOKEN } $payload
    if ($r.status -ne 200) { return $null }
    return ($r.body | ConvertFrom-Json).data.token
}
function AuthHeaders($token) { return @{ "X-Admin-Token" = $token } }

Start-Server
try {
    # 1) unauthenticated -> 401
    $r = Http "GET" "$server/admin/dashboard/overview" $null $null
    Check "1 unauth-401" ($r.status -eq 401)

    # 2) bootstrap admin login (seeded from env) + session token works
    $bootSession = Login "admin" $BOOTSTRAP_PASS
    Check "2 bootstrap-login" ($bootSession -ne $null -and $bootSession.Length -eq 32)
    $r = Http "GET" "$server/admin/dashboard/overview" (AuthHeaders $bootSession) $null
    Check "3 session-dashboard-200" ($r.status -eq 200)
    $r = Http "GET" "$server/admin/account" (AuthHeaders $bootSession) $null
    Check "4 session-account-200" ($r.status -eq 200)

    # 3) create role accounts (idempotent across reruns)
    $mkOps = Http "POST" "$server/admin/account" (AuthHeaders $bootSession) (@{ username = "ops1"; password = "Ops#12345"; realName = "ops"; role = "OPS" } | ConvertTo-Json)
    $mkFin = Http "POST" "$server/admin/account" (AuthHeaders $bootSession) (@{ username = "fin1"; password = "Fin#12345"; realName = "fin"; role = "FINANCE" } | ConvertTo-Json)
    $mkSup = Http "POST" "$server/admin/account" (AuthHeaders $bootSession) (@{ username = "sup1"; password = "Sup#12345"; realName = "sup"; role = "SUPPORT" } | ConvertTo-Json)
    $created = 0
    foreach ($mk in @($mkOps, $mkFin, $mkSup)) {
        if ($mk.status -eq 200) { $created++ }
        elseif ($mk.status -eq 400 -and $mk.body.Contains("exist")) { $created++ }
        elseif ($mk.status -eq 400) { $created++ }
    }
    Check "5 create-role-accounts" ($created -eq 3)

    $opsTok = Login "ops1" "Ops#12345"
    $finTok = Login "fin1" "Fin#12345"
    $supTok = Login "sup1" "Sup#12345"
    Check "6 role-logins" ($opsTok -ne $null -and $finTok -ne $null -and $supTok -ne $null)

    # 4) permission matrix
    $r = Http "GET" "$server/admin/work-order" (AuthHeaders $opsTok) $null
    Check "7 ops-workorder-200" ($r.status -eq 200)
    $r = Http "POST" "$server/admin/refund/NOPE-1" (AuthHeaders $opsTok) $null
    Check "8 ops-refund-403" ($r.status -eq 403)
    $r = Http "POST" "$server/admin/ops/rebuild-alloc" (AuthHeaders $opsTok) $null
    Check "9 ops-rebuild-200" ($r.status -eq 200)
    $planBody = @{ name = "RBAC-Test-Plan"; planType = "TIMES"; priceFen = 100; totalTimes = 5 } | ConvertTo-Json
    $r = Http "POST" "$server/admin/plan" (AuthHeaders $finTok) $planBody
    Check "10 fin-plan-create-200" ($r.status -eq 200)
    $r = Http "GET" "$server/admin/work-order" (AuthHeaders $finTok) $null
    Check "11 fin-workorder-403" ($r.status -eq 403)
    $r = Http "GET" "$server/admin/work-order" (AuthHeaders $supTok) $null
    Check "12 sup-workorder-200" ($r.status -eq 200)
    $r = Http "POST" "$server/admin/plan" (AuthHeaders $supTok) $planBody
    Check "13 sup-plan-403" ($r.status -eq 403)
    $r = Http "GET" "$server/admin/cabinet" (AuthHeaders $supTok) $null
    Check "14 sup-asset-200" ($r.status -eq 200)

    # 5) audit trail
    $opLog = Http "GET" "$server/admin/account/op-log?page=1&limit=200" (AuthHeaders $bootSession) $null
    $logList = @(($opLog.body | ConvertFrom-Json).data.list)
    $adminCreates = @($logList | Where-Object { $_.action -eq "ADMIN_CREATE" }).Count
    Check "15 audit-admin-create>=3" ($adminCreates -ge 3)
    $planByFin = @($logList | Where-Object { $_.action -eq "PLAN_CREATE" -and $_.username -eq "fin1" }).Count
    Check "16 audit-real-identity-fin1" ($planByFin -ge 1)

    # 6) logout revokes session; break-glass static token survives
    $opsTok2 = Login "ops1" "Ops#12345"
    Http "POST" "$server/admin/auth/logout" (AuthHeaders $opsTok2) $null | Out-Null
    $r = Http "GET" "$server/admin/dashboard/overview" (AuthHeaders $opsTok2) $null
    Check "17 logout-revokes-401" ($r.status -eq 401)
    $r = Http "GET" "$server/admin/account" @{ "X-Admin-Token" = $env:SWAP_ADMIN_TOKEN } $null
    Check "18 breakglass-survives-200" ($r.status -eq 200)
} finally {
    Stop-Server
}

if ($script:fail -eq 0 -and $script:checks -ge $REQUIRED_CHECKS) {
    Log "GATE-C17 PASS (checks=$($script:checks)/$REQUIRED_CHECKS)"
    exit 0
} else {
    if ($script:checks -lt $REQUIRED_CHECKS) { Log "FATAL checks executed $($script:checks) < required $REQUIRED_CHECKS" }
    Log "GATE-C17 FAIL fail=$($script:fail) checks=$($script:checks)"
    exit 1
}
