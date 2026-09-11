# S3.8 gate: outbox relay (WP6) - MQ outage persistence, dedup, resume
# Prereq: server :8400 (dev enabled), MQ broker+proxy running, MySQL CLI in PATH
# Evidence: MQ down -> alarm event persisted in outbox (NEW, attempts>0); MQ up -> SENT;
#           duplicate alarm raise within dedup window -> exactly one outbox row.
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c4_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
function SqlScalar($sql) {
    $result = cmd /c "mysql -uroot -proot swap_ops -N -e ""$sql"" 2>nul"
    if ($result -eq $null -or $result -eq "") { return 0 }
    return [int64]$result
}
function Sign-Event($cabinetNo, $canonical, $secret) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    return (-join ($hash | ForEach-Object { $_.ToString("x2") }))
}
function Send-Fault($cabinetNo, $bootId, $seq, $secret) {
    $canonical = "$cabinetNo|CABINET_FAULT|||$bootId|$seq"
    $sign = Sign-Event $cabinetNo $canonical $secret
    $body = @{ cabinetNo = $cabinetNo; eventType = "CABINET_FAULT"; bootId = $bootId; eventSeq = $seq } | ConvertTo-Json
    Invoke-RestMethod -Method Post "$server/device/event" -Headers @{ "X-Device-No" = $cabinetNo; "X-Device-Sign" = $sign } -ContentType "application/json" -Body $body -TimeoutSec 5 | Out-Null
}
Set-Content -Path $out -Value "== S3.8 gate: outbox relay ==" -Encoding UTF8

$secret = (Get-Content (Join-Path $local "dev-secret.txt") -Raw).Trim()
$cabinet = "SWAP-C-008"
$startMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

# --- 1) MQ down: stop proxy + broker, raise alarm twice (dedup), event must persist ---
$mqPids = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "BrokerStartup|ProxyStartup" } | Select-Object -ExpandProperty ProcessId
Log "stopping MQ broker/proxy pids=$($mqPids -join ',')"
foreach ($procId in $mqPids) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 3

$gen = "boot-c4-" + (Get-Date -Format "HHmmss")
Send-Fault $cabinet $gen 7001 $secret
Send-Fault $cabinet $gen 7002 $secret   # dedup window: same (type, deviceNo) -> same alarm
Start-Sleep -Seconds 12                    # relay cycles (5s) with failing publishes

$pending = SqlScalar "SELECT COUNT(*) FROM outbox_event WHERE event_type='ALARM' AND status='NEW' AND create_time >= $startMs"
$attempts = SqlScalar "SELECT IFNULL(MAX(attempts),0) FROM outbox_event WHERE event_type='ALARM' AND create_time >= $startMs"
Log "outbox after outage: pending(NEW)=$pending maxAttempts=$attempts"
Check "event persisted while MQ down (NEW row)" ($pending -ge 1)
Check "relay retried at least once (attempts>0)" ($attempts -ge 1)

$total = SqlScalar "SELECT COUNT(*) FROM outbox_event WHERE event_type='ALARM' AND create_time >= $startMs"
Check "dedup: two raises -> one outbox row" ($total -eq 1)

# --- 2) MQ up: relay must deliver and mark SENT ---
Log "restarting MQ broker+proxy"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$local\start-broker-proxy.bat`"" -WindowStyle Hidden
$mqUp = $false
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    $broker = Get-NetTCPConnection -State Listen -LocalPort 10911 -ErrorAction SilentlyContinue
    $proxy = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
    if ($broker -and $proxy) { $mqUp = $true; break }
    Start-Sleep -Seconds 3
}
Check "MQ broker+proxy back UP" $mqUp

$sent = 0
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    $sent = SqlScalar "SELECT COUNT(*) FROM outbox_event WHERE event_type='ALARM' AND status='SENT' AND create_time >= $startMs"
    if ($sent -ge 1) { break }
    Start-Sleep -Seconds 5
}
$dead = SqlScalar "SELECT COUNT(*) FROM outbox_event WHERE event_type='ALARM' AND status='DEAD' AND create_time >= $startMs"
Log "outbox after recovery: SENT=$sent DEAD=$dead"
Check "event delivered after MQ recovery (SENT)" ($sent -ge 1)
Check "no dead letter for this event" ($dead -eq 0)

if ($script:fail -eq 0) { Log "GATE-OUTBOX PASS"; exit 0 } else { Log "GATE-OUTBOX FAIL checks=$($script:fail)"; exit 1 }
