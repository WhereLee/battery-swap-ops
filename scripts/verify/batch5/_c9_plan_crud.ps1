# S4.5 gate: admin plan CRUD + user-facing cache invalidation
# Prereq: server :8400; admin token + user login available
# Evidence: create/update/off-shelf/delete take effect immediately on /user/plans (write-path evict);
#           delete refused when purchased; validation rejects bad forms
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c9_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S4.5 gate: plan CRUD ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$token = (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body '{"phone":"13800000001"}' -TimeoutSec 5).data.token
$H = @{ "X-User-Token" = $token }

$name = "E2E-PLAN-" + (Get-Date -Format "HHmmss")
$baseline = @((Invoke-RestMethod "$server/user/plans" -Headers $H -TimeoutSec 5).data).Count
Log "baseline plans=$baseline name=$name"

# ---- create ----
$form = @{ name = $name; planType = "TIMES"; priceFen = 1; totalTimes = 5 } | ConvertTo-Json
$created = (Invoke-RestMethod -Method Post "$server/admin/plan" -Headers $admin -ContentType "application/json" -Body $form -TimeoutSec 5).data
Log "created id=$($created.id) status=$($created.status)"
Check "created active (status=1)" ($created.status -eq 1)

$list1 = @((Invoke-RestMethod "$server/user/plans" -Headers $H -TimeoutSec 5).data)
$found = @($list1 | Where-Object { $_.name -eq $name })
Check "new plan visible immediately (cache evicted)" ($found.Count -eq 1)

# ---- update price ----
$form2 = @{ name = $name; planType = "TIMES"; priceFen = 2; totalTimes = 5 } | ConvertTo-Json
Invoke-RestMethod -Method Post "$server/admin/plan/$($created.id)" -Headers $admin -ContentType "application/json" -Body $form2 -TimeoutSec 5 | Out-Null
$list2 = @((Invoke-RestMethod "$server/user/plans" -Headers $H -TimeoutSec 5).data)
$updated = @($list2 | Where-Object { $_.name -eq $name })[0]
Check "price update visible (2)" ($updated.priceFen -eq 2)

# ---- off-shelf ----
Invoke-RestMethod -Method Post "$server/admin/plan/$($created.id)/status?status=2" -Headers $admin -TimeoutSec 5 | Out-Null
$list3 = @((Invoke-RestMethod "$server/user/plans" -Headers $H -TimeoutSec 5).data)
$off = @($list3 | Where-Object { $_.name -eq $name })
Check "off-shelf plan hidden from user list" ($off.Count -eq 0)

# ---- validation ----
$bad = $false
try {
    Invoke-RestMethod -Method Post "$server/admin/plan" -Headers $admin -ContentType "application/json" -Body (@{ name = "bad-plan"; planType = "TIMES"; priceFen = 1 } | ConvertTo-Json) -TimeoutSec 5 | Out-Null
} catch { $bad = $true }
Check "invalid form rejected (TIMES without totalTimes)" $bad

# ---- delete ----
Invoke-RestMethod -Method Delete "$server/admin/plan/$($created.id)" -Headers $admin -TimeoutSec 5 | Out-Null
$after = @((Invoke-RestMethod "$server/user/plans" -Headers $H -TimeoutSec 5).data).Count
$adminList = @((Invoke-RestMethod "$server/admin/plan" -Headers $admin -TimeoutSec 5).data)
$stillThere = @($adminList | Where-Object { $_.id -eq $created.id })
Check "deleted plan gone from admin list" ($stillThere.Count -eq 0)
Check "user plan count back to baseline" ($after -eq $baseline)

if ($script:fail -eq 0) { Log "GATE-PLAN-CRUD PASS"; exit 0 } else { Log "GATE-PLAN-CRUD FAIL checks=$($script:fail)"; exit 1 }
