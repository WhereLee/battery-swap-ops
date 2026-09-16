# batch20 P1-6: observability — prometheus endpoint auth + business metrics + traceId wired into log pattern
# Prereq: server :8400 (load profile) up; admin token at .local/admin-token.txt
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_c24_out.txt"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
Set-Content -Path $out -Value "== batch20: P1-6 metrics ==" -Encoding UTF8

# 1) health stays public
try { $hc = (Invoke-WebRequest "$server/actuator/health" -UseBasicParsing -TimeoutSec 5).StatusCode } catch { $hc = -1 }
Log "health -> $hc"
Check "health public (200)" ($hc -eq 200)

# 2) prometheus without token -> 401 (admin-chain protected)
$noToken = 0
try { $noToken = (Invoke-WebRequest "$server/actuator/prometheus" -UseBasicParsing -TimeoutSec 5).StatusCode }
catch { if ($_.Exception.Response) { $noToken = [int]$_.Exception.Response.StatusCode } }
Log "prometheus without token -> $noToken"
Check "prometheus protected (401 without token)" ($noToken -eq 401)

# 3) prometheus with admin token -> 200 + business metrics present
$body = ""
try { $body = (Invoke-WebRequest "$server/actuator/prometheus" -Headers @{"X-Admin-Token" = $adminToken} -UseBasicParsing -TimeoutSec 15).Content } catch { Log "prometheus err: $($_.Exception.Message)" }
Log "prometheus body bytes: $($body.Length)"
$names = @("swap_outbox_backlog", "swap_outbox_dead", "swap_alarm_unhandled", "swap_delay_backlog",
    "swap_reconcile_violations", "swap_alloc_available")
$hit = 0
foreach ($n in $names) {
    if ($body -match ("(?m)^" + $n)) { $hit++; Log "metric found: $n @ " + (($body -split "`n" | Where-Object { $_ -match ("^" + $n) } | Select-Object -First 1).Trim()) }
    else { Log "metric MISSING: $n" }
}
Check "6 business metrics exposed" ($hit -ge 6)
Check "jvm metric exposed" ($body -match "jvm_memory_used_bytes")
Check "hikari metric exposed" ($body -match "hikaricp_connections")
Check "http server metric exposed" ($body -match "http_server_requests")

# 4) traceId wired into log pattern: send request with explicit X-Trace-Id then grep the log text
$trace = "b20trace" + (Get-Date -Format "HHmmss")
try {
    Invoke-RestMethod -Method Post "$server/user/login" -Headers @{"X-Trace-Id" = $trace} `
        -ContentType "application/json" -Body (@{ phone = "13800000002" } | ConvertTo-Json) -TimeoutSec 5 | Out-Null
} catch { Log "login err: $($_.Exception.Message)" }
Start-Sleep -Seconds 2
$logHit = (Select-String -Path (Join-Path $repo ".local\server-load.out.log") -Pattern $trace -SimpleMatch | Measure-Object).Count
Log "traceId hits in log: $logHit (trace=$trace)"
Check "traceId appears in log text (pattern wired)" ($logHit -ge 1)

if ($script:fail -eq 0) { Log "BATCH20-METRICS PASS"; exit 0 } else { Log "BATCH20-METRICS FAIL checks=$($script:fail)"; exit 1 }
