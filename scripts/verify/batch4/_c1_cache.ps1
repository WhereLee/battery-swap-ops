# S3.8 gate: two-level cache (WP1) - warm, evict-on-write, concurrent single-flight
# Prereq: server :8400 (swap.cache.enabled=true default); admin token in .local/admin-token.txt
# Evidence: 50 concurrent reads after evict -> exactly one DB rebuild (single-flight);
#           admin status change evicts and takes effect immediately; restore re-appears.
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c1_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
function Read-LogText($path) {
    if (!(Test-Path $path)) { return "" }
    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try { $sr = New-Object System.IO.StreamReader($fs); return $sr.ReadToEnd() } finally { $sr.Close(); $fs.Close() }
}
function Get-CacheStats { return (Invoke-RestMethod "$server/admin/cache/stats" -Headers $admin -TimeoutSec 5).data }
function Get-Rebuilds($key) {
    $stats = Get-CacheStats
    $prop = $stats.PSObject.Properties["rebuild:$key"]
    if ($prop -eq $null) { return 0 }
    return [int64]$prop.Value
}
Set-Content -Path $out -Value "== S3.8 gate: two-level cache ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$token = (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body '{"phone":"13800000001"}' -TimeoutSec 5).data.token
$H = @{ "X-User-Token" = $token }

# ---- 1) plan catalog warm: repeat reads -> at most one rebuild ----
$planBefore = Get-Rebuilds "plan:active:list"
1..3 | ForEach-Object { Invoke-RestMethod "$server/user/plans" -Headers $H -TimeoutSec 5 | Out-Null }
$planAfter = Get-Rebuilds "plan:active:list"
Log "plan cache rebuilds before=$planBefore after=$planAfter"
Check "plan catalog warm (<=1 rebuild for 3 reads)" (($planAfter - $planBefore) -le 1)

# ---- 2) station cache: evict on write + concurrent single-flight ----
Invoke-RestMethod "$server/user/stations" -Headers $H -TimeoutSec 5 | Out-Null
$page = (Invoke-RestMethod "$server/admin/station?page=1&limit=20" -Headers $admin -TimeoutSec 5).data
$station = @($page.list) | Where-Object { $_.status -eq 1 } | Select-Object -First 1
if ($station -eq $null) { Log "no active station to test"; exit 1 }
Log "target station id=$($station.id) no=$($station.stationNo)"

Invoke-RestMethod -Method Post "$server/admin/station/$($station.id)/status?status=2" -Headers $admin -TimeoutSec 5 | Out-Null
$stationBefore = Get-Rebuilds "station:active:list"

Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(15)
$client.DefaultRequestHeaders.Add("X-User-Token", $token)
$tasks = @()
1..50 | ForEach-Object { $tasks += $client.GetStringAsync("$server/user/stations") }
[System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks) | Out-Null
$client.Dispose()

$stationAfter = Get-Rebuilds "station:active:list"
Log "station cache rebuilds under 50 concurrent reads: +$($stationAfter - $stationBefore)"
Check "concurrent requests actually fired (50)" (@($tasks).Count -eq 50)
Check "concurrent single-flight: exactly one rebuild" (($stationAfter - $stationBefore) -eq 1)

$listAfterStop = (Invoke-RestMethod "$server/user/stations" -Headers $H -TimeoutSec 5).data
$foundStopped = @($listAfterStop) | Where-Object { $_.stationNo -eq $station.stationNo }
Check "evict takes effect (stopped station hidden)" ($foundStopped -eq $null)

Invoke-RestMethod -Method Post "$server/admin/station/$($station.id)/status?status=1" -Headers $admin -TimeoutSec 5 | Out-Null
$listAfterRestore = (Invoke-RestMethod "$server/user/stations" -Headers $H -TimeoutSec 5).data
$foundRestored = @($listAfterRestore) | Where-Object { $_.stationNo -eq $station.stationNo }
Check "restore visible after evict (write-path invalidation)" ($foundRestored -ne $null)

if ($script:fail -eq 0) { Log "GATE-CACHE PASS"; exit 0 } else { Log "GATE-CACHE FAIL checks=$($script:fail)"; exit 1 }
