# S4.1 gate: battery health (auditable cycle counters + SOH scan alarm + reconcile)
# Prereq: server fast mode (battery scan 3s), sim up, admin token + user login
# Evidence: TAKE/RETURN -> swaps/cycle_count +1 with cycle logs; low SOH -> alarm; SOH restored -> auto close;
#           reconcile battery-counters invariant passes
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c12_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S4.1 gate: battery health ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$token = (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body '{"phone":"13800000001"}' -TimeoutSec 5).data.token
$H = @{ "X-User-Token" = $token }

function Wait-Status($orderNo, $expect, $timeoutSec = 15) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-RestMethod "$server/user/order/$orderNo" -Headers $H -TimeoutSec 5
        if ($r.data.status -eq $expect) { return $r.data }
        Start-Sleep -Milliseconds 300
    }
    return $null
}
function New-Order($type) {
    $h = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
    return Invoke-RestMethod -Method Post "$server/user/order" -Headers $h -ContentType "application/json" -Body (@{ type = $type } | ConvertTo-Json) -TimeoutSec 10
}

# clean device state for deterministic flow
Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60 | Out-Null

# ---- TAKE: battery out -> swaps+1 + OUT log ----
$take = New-Order "TAKE"
$takeOpened = Wait-Status $take.data.orderNo 2
Check "TAKE opened" ($takeOpened -ne $null)
$batteryNo = $takeOpened.takeBatteryNo
Log "TAKE battery=$batteryNo"
Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($takeOpened.cabinetNo)&cellNo=$($takeOpened.cellNo)" -TimeoutSec 5 | Out-Null
Check "TAKE completed" ((Wait-Status $take.data.orderNo 5) -ne $null)

$health = (Invoke-RestMethod "$server/admin/battery/$batteryNo/health" -Headers $admin -TimeoutSec 5).data
Log "health after OUT: swaps=$($health.swaps) cycles=$($health.cycleCount) logs=$($health.recentCycles.Count)"
Check "swaps incremented" ([int]$health.swaps -ge 1)
Check "OUT log recorded" (@($health.recentCycles | Where-Object { $_.action -eq "OUT" }).Count -ge 1)

# ---- RETURN: battery in -> cycle_count+1 + IN log ----
$ret = New-Order "RETURN"
$retOpened = Wait-Status $ret.data.orderNo 2
Check "RETURN opened" ($retOpened -ne $null)
Invoke-RestMethod -Method Post "$sim/sim/battery/in?cabinetNo=$($retOpened.cabinetNo)&cellNo=$($retOpened.cellNo)&batteryNo=$batteryNo&soc=20" -TimeoutSec 5 | Out-Null
Check "RETURN completed" ((Wait-Status $ret.data.orderNo 5) -ne $null)

$health = (Invoke-RestMethod "$server/admin/battery/$batteryNo/health" -Headers $admin -TimeoutSec 5).data
Log "health after IN: swaps=$($health.swaps) cycles=$($health.cycleCount) level=$($health.healthLevel)"
Check "cycle_count incremented" ([int]$health.cycleCount -ge 1)
Check "IN log recorded" (@($health.recentCycles | Where-Object { $_.action -eq "IN" }).Count -ge 1)

# ---- SOH scan: lower SOH -> alarm; restore -> auto close ----
Invoke-RestMethod -Method Post "$server/admin/battery/$batteryNo" -Headers $admin -ContentType "application/json" -Body (@{ soh = 50 } | ConvertTo-Json) -TimeoutSec 5 | Out-Null
$alarmed = $false
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
    $alarms = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=300" -Headers $admin -TimeoutSec 5).data
    if (@($alarms | Where-Object { $_.alarmType -eq "BATTERY_HEALTH_LOW" -and $_.deviceNo -eq $batteryNo }).Count -ge 1) { $alarmed = $true; break }
    Start-Sleep -Seconds 1
}
Check "BATTERY_HEALTH_LOW alarm raised" $alarmed

Invoke-RestMethod -Method Post "$server/admin/battery/$batteryNo" -Headers $admin -ContentType "application/json" -Body (@{ soh = 100 } | ConvertTo-Json) -TimeoutSec 5 | Out-Null
$recovered = $false
$deadline = (Get-Date).AddSeconds(20)
while ((Get-Date) -lt $deadline) {
    $alarms = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=300" -Headers $admin -TimeoutSec 5).data
    if (@($alarms | Where-Object { $_.alarmType -eq "BATTERY_HEALTH_LOW" -and $_.deviceNo -eq $batteryNo }).Count -eq 0) { $recovered = $true; break }
    Start-Sleep -Seconds 1
}
Check "alarm auto-recovered after SOH restored" $recovered

# ---- reconcile: battery-counters invariant must be clean ----
$report = (Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $admin -TimeoutSec 60).data
$counterCheck = @($report.checks | Where-Object { $_.name -eq "battery-counters" })[0]
Log "reconcile battery-counters violations=$($counterCheck.violations)"
Check "battery counters match cycle logs (no violations)" ([int]$counterCheck.violations -eq 0)

if ($script:fail -eq 0) { Log "GATE-BATTERY-HEALTH PASS"; exit 0 } else { Log "GATE-BATTERY-HEALTH FAIL checks=$($script:fail)"; exit 1 }
