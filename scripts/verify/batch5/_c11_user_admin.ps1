# S4.5 gate: admin user management (list + enable/disable, immediate effect on issued tokens)
# Prereq: server :8400; admin token in .local/admin-token.txt
# Evidence: disable -> login rejected AND existing token rejected (401); enable -> login works again
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c11_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S4.5 gate: user admin ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$phone = "13800000025"

$page = (Invoke-RestMethod "$server/admin/user?page=1&limit=10&phone=$phone" -Headers $admin -TimeoutSec 5).data
$target = @($page.list)[0]
Check "admin user list finds target" ($target -ne $null -and $target.phone -eq $phone)
$userId = $target.id
Log "target userId=$userId status=$($target.status)"

# ensure enabled baseline
if ($target.status -ne 1) {
    Invoke-RestMethod -Method Post "$server/admin/user/$userId/status?status=1" -Headers $admin -TimeoutSec 5 | Out-Null
}
$token = (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5).data.token
Check "baseline login ok" ($token -ne $null)

# invalid status rejected
$bad = $false
try { Invoke-RestMethod -Method Post "$server/admin/user/$userId/status?status=3" -Headers $admin -TimeoutSec 5 | Out-Null } catch { $bad = $true }
Check "invalid status rejected" $bad

# disable -> login fails; issued token rejected on next request
Invoke-RestMethod -Method Post "$server/admin/user/$userId/status?status=2" -Headers $admin -TimeoutSec 5 | Out-Null
$loginRejected = $false
try {
    Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5 | Out-Null
} catch { $loginRejected = $true }
Check "disabled user cannot login" $loginRejected

$tokenRejected = $false
try {
    Invoke-RestMethod "$server/user/wallet" -Headers @{ "X-User-Token" = $token } -TimeoutSec 5 | Out-Null
} catch {
    $tokenRejected = ($_.Exception.Response.StatusCode.value__ -eq 401)
}
Check "issued token rejected immediately (401)" $tokenRejected

# enable -> login works
Invoke-RestMethod -Method Post "$server/admin/user/$userId/status?status=1" -Headers $admin -TimeoutSec 5 | Out-Null
$token2 = (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5).data.token
Check "re-enabled login ok" ($token2 -ne $null)

if ($script:fail -eq 0) { Log "GATE-USER-ADMIN PASS"; exit 0 } else { Log "GATE-USER-ADMIN FAIL checks=$($script:fail)"; exit 1 }
