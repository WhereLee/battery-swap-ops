# S3.8 gate: token-bucket rate limit (WP2) - exact permits under burst, 429 + Retry-After, refill
# Prereq: server :8400; admin token in .local/admin-token.txt; limiter "user-login" = 5 permits / 5s per IP
# Evidence: 20 concurrent logins -> exactly 5 allowed + 15 rejected(429); stats match; refill allows again.
$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.Net.Http
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c2_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S3.8 gate: token bucket ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
function Get-Stat($name) {
    $stats = (Invoke-RestMethod "$server/admin/ratelimit/stats" -Headers $admin -TimeoutSec 5).data
    $prop = $stats.PSObject.Properties[$name]
    if ($prop -eq $null) { return 0 }
    return [int64]$prop.Value
}

# bucket may carry state from previous runs: wait full refill (capacity 5, window 5s)
Log "waiting 6s for token bucket refill"
Start-Sleep -Seconds 6
$allowedBefore = Get-Stat "allowed:user-login"
$deniedBefore = Get-Stat "denied:user-login"

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(20)
$body = '{"phone":"13800000001"}'
$tasks = @()
1..20 | ForEach-Object {
    $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, "application/json")
    $tasks += $client.PostAsync("$server/user/login", $content)
}
[System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks) | Out-Null
$client.Dispose()

$ok = 0
$rejected = 0
$retryAfter = $null
foreach ($task in $tasks) {
    $response = $task.Result
    $code = [int]$response.StatusCode
    if ($code -eq 200) { $ok++ }
    elseif ($code -eq 429) {
        $rejected++
        if ($retryAfter -eq $null) { $retryAfter = [string]$response.Headers.RetryAfter }
    }
}
Log "burst result: allowed=$ok rejected(429)=$rejected retryAfter=$retryAfter (requests=20)"
Check "requests fired (20)" (@($tasks).Count -eq 20)
Check "exactly permits(5) allowed" ($ok -eq 5)
Check "exactly 15 rejected with 429" ($rejected -eq 15)
Check "Retry-After header present" ($retryAfter -ne $null -and $retryAfter -ne "")

$allowedAfter = Get-Stat "allowed:user-login"
$deniedAfter = Get-Stat "denied:user-login"
Log "stats delta: allowed=+$($allowedAfter - $allowedBefore) denied=+$($deniedAfter - $deniedBefore)"
Check "stats allowed delta = 5" (($allowedAfter - $allowedBefore) -eq 5)
Check "stats denied delta = 15" (($deniedAfter - $deniedBefore) -eq 15)

Log "waiting 6s for refill"
Start-Sleep -Seconds 6
try {
    $login = Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body $body -TimeoutSec 5
    Check "refill allows next request" ($login.code -eq 0)
} catch { Check "refill allows next request" $false }

if ($script:fail -eq 0) { Log "GATE-RATELIMIT PASS"; exit 0 } else { Log "GATE-RATELIMIT FAIL checks=$($script:fail)"; exit 1 }
