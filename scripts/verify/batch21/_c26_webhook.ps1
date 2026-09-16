# batch21 P1-11: alarm outbound webhook end-to-end (HMAC signed), plus "receiver down" isolation proof.
# Prereq: server :8400 (load profile) started WITH SWAP_ALARM_WEBHOOK_URL/SECRET from .local files;
#         sim :8500 controllable (run-sim.bat); python available; redis-cli at F:\Redis
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_c26_out.txt"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$eventsFile = Join-Path $PSScriptRoot "_c26_events.jsonl"
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$secret = ((Get-Content (Join-Path $repo ".local\webhook-secret.txt") -Raw).Trim())
$AH = @{ "X-Admin-Token" = $adminToken }
$port = 8490
$script:fail = 0
$script:receiver = $null
$script:baselineLines = 0

function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
function HealthRaw { try { return [int](Invoke-WebRequest "$server/actuator/health" -TimeoutSec 5 -UseBasicParsing).StatusCode } catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return "CONN_REFUSED" } } }

function StopReceiver {
    if ($script:receiver -and -not $script:receiver.HasExited) { Stop-Process -Id $script:receiver.Id -Force -ErrorAction SilentlyContinue }
    try {
        $own = (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).OwningProcess
        if ($own) { Stop-Process -Id $own -Force -ErrorAction SilentlyContinue }
    } catch { }
    Start-Sleep -Milliseconds 500
}
function StartReceiver {
    StopReceiver
    $py = (Get-Command python -ErrorAction SilentlyContinue).Source
    if (-not $py) { $py = (Get-Command py -ErrorAction SilentlyContinue).Source }
    if (-not $py) { Log "python not found"; return $false }
    $script:receiver = Start-Process -FilePath $py -ArgumentList (Join-Path $PSScriptRoot "_c26_mock_receiver.py"), "$port", $eventsFile, $secret -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 2
    if ($script:receiver.HasExited) { Log "receiver exited early"; return $false }
    return $true
}
function StopSim {
    $procs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'swap-sim' -and $_.Name -match '^java' }
    foreach ($p in @($procs)) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    Log "sim stopped (pids: $(@($procs | ForEach-Object { $_.ProcessId }) -join ','))"
}
function StartSim {
    $devSecret = ((Get-Content (Join-Path $repo ".local\dev-secret.txt") -Raw).Trim())
    $env:SWAP_DEV_SECRET = $devSecret
    $javaw = (Get-Command javaw -ErrorAction SilentlyContinue).Source
    if (-not $javaw) { $javaw = ((Get-Command java -ErrorAction SilentlyContinue).Source) -replace 'java\.exe$', 'javaw.exe' }
    Start-Process -FilePath $javaw -ArgumentList "-jar", (Join-Path $repo "swap-sim\target\swap-sim-1.0.0.jar"), "--swap.sim.dev-enabled=true" -WindowStyle Hidden
    Log "sim started"
}
function WaitEvent($kind, $timeoutSec) {
    $start = Get-Date
    while (((Get-Date) - $start).TotalSeconds -lt $timeoutSec) {
        if (Test-Path $eventsFile) {
            $all = @(Get-Content $eventsFile -Encoding UTF8)
            if ($all.Count -gt $script:baselineLines) {
                $slice = $all[$script:baselineLines..($all.Count - 1)]
                foreach ($ln in $slice) {
                    try { $o = $ln | ConvertFrom-Json } catch { continue }
                    if ($o.event -eq $kind -and $o.body -match '"alarmType":"(BATCH_OFFLINE|OFFLINE)"') { return $o }
                }
            }
        }
        Start-Sleep -Seconds 3
    }
    return $null
}
function OfflineAlarms { try { $items = @(@((Invoke-RestMethod "$server/admin/alarm" -Headers $AH -TimeoutSec 10).data) | Where-Object { $_.alarmType -in @("OFFLINE", "BATCH_OFFLINE") }); return $items } catch { return @() } }

Set-Content -Path $out -Value "== batch21: P1-11 webhook ==" -Encoding UTF8

# 0) preflight: receiver up + events baseline
Check "server healthy before scenario" ((HealthRaw) -eq 200)
$up = StartReceiver
Check "mock receiver started on :$port" $up
if (Test-Path $eventsFile) { $script:baselineLines = @(Get-Content $eventsFile -Encoding UTF8).Count } else { [IO.File]::WriteAllBytes($eventsFile, [byte[]]@()); $script:baselineLines = 0 }
Log "events baseline lines: $($script:baselineLines)"

# 1) cleanup leftovers so a fresh raise is guaranteed (unhandled offline-family alarms + dedup keys)
foreach ($a in OfflineAlarms) {
    try { Invoke-RestMethod -Method Post "$server/admin/alarm/$($a.id)/handle" -Headers $AH -TimeoutSec 10 | Out-Null; Log "cleaned leftover alarm id=$($a.id) $($a.alarmType) $($a.deviceNo)" } catch { }
}
$keys = @(& "F:\Redis\redis-cli.exe" --scan --pattern "swap:alarm:dedup:*" 2>$null)
foreach ($k in $keys) { if ($k) { & "F:\Redis\redis-cli.exe" DEL $k 2>$null | Out-Null } }
Log "dedup keys cleared: $($keys.Count)"

# 2) inject: stop sim -> batch offline -> RAISED webhook delivered + signature verified
StopSim
$raised = WaitEvent "RAISED" 180
Check "RAISED webhook delivered after sim outage" ($null -ne $raised)
if ($raised) {
    Log "RAISED ts=$($raised.ts) sign_ok=$($raised.sign_ok) body_len=$($raised.body.Length)"
    Check "RAISED HMAC signature verified by receiver (byte-level)" ($raised.sign_ok -eq $true)
    Check "RAISED body carries traceId" ($raised.body -match '"traceId"')
}

# 3) recover: start sim -> heartbeat back -> RECOVERED webhook delivered
StartSim
$recovered = WaitEvent "RECOVERED" 180
Check "RECOVERED webhook delivered after sim recovery" ($null -ne $recovered)
if ($recovered) {
    Log "RECOVERED ts=$($recovered.ts) sign_ok=$($recovered.sign_ok)"
    Check "RECOVERED HMAC signature verified by receiver" ($recovered.sign_ok -eq $true)
}

# 3.5) precondition for isolation round: offline-family list must be fully cleared first
# (otherwise the isolation check could trivially pass on leftovers)
$preClear = $false; $start = Get-Date
while (((Get-Date) - $start).TotalSeconds -lt 240) {
    if (@(OfflineAlarms).Count -eq 0) { $preClear = $true; break }
    Start-Sleep -Seconds 5
}
Check "offline-family list empty before isolation round" $preClear

# 4) isolation: receiver down must not affect alarm pipeline
StopReceiver
Log "receiver stopped (isolation round)"
StopSim
$found = $false; $start = Get-Date
while (((Get-Date) - $start).TotalSeconds -lt 240) {
    if (@(OfflineAlarms).Count -gt 0) { $found = $true; break }
    Start-Sleep -Seconds 5
}
Check "new alarm stored while webhook receiver down" $found
Check "health stays 200 while webhook receiver down" ((HealthRaw) -eq 200)
$loginOk = $false
try { $loginOk = [bool](Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body '{"phone":"13800000002"}' -TimeoutSec 5).data.token } catch { }
Check "business path alive while webhook receiver down" $loginOk
StartSim
Start-Sleep -Seconds 35   # let stale heartbeat keys (TTL 30s) expire so EXISTS means NEW heartbeat
$hb = $false
foreach ($i in 1..60) {
    if ((& "F:\Redis\redis-cli.exe" EXISTS "swap:online:SWAP-C-001" 2>$null) -eq "1") { $hb = $true; break }
    Start-Sleep -Seconds 2
}
Check "sim heartbeat back after restart" $hb
$cleared = $false; $start = Get-Date
while (((Get-Date) - $start).TotalSeconds -lt 240) {
    if (@(OfflineAlarms).Count -eq 0) { $cleared = $true; break }
    Start-Sleep -Seconds 5
}
Check "alarm auto-recovered while webhook receiver down" $cleared

# 5) evidence: notifier failure attempts present in server log
$logHit = (Select-String -Path (Join-Path $repo ".local\server-load.out.log") -Pattern "webhook" -SimpleMatch | Measure-Object).Count
Log "webhook mentions in server log: $logHit"
Check "notifier activity present in server log" ($logHit -ge 1)

if ($script:fail -eq 0) { Log "BATCH21-WEBHOOK PASS"; exit 0 } else { Log "BATCH21-WEBHOOK FAIL checks=$($script:fail)"; exit 1 }
