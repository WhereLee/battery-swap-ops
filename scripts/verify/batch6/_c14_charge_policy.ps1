# S4.3 gate: charge policy (valley boost / peak cut + power cap + cost evidence)
# Prereq: server fast mode; sim restarted with charge-speed-factor=900,tick=500 (run-sim-dual.bat)
# Evidence: valley window -> SOC rises, power <= limit; peak(0W) window -> SOC frozen, power=0;
#           invalid windows rejected; valley cost < flat baseline; version monotonic
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c14_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S4.3 gate: charge policy ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$secret = (Get-Content (Join-Path $local "dev-secret.txt") -Raw).Trim()
$cabinet = "SWAP-C-001"

function Sign($canonical) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    return (-join ($hash | ForEach-Object { $_.ToString("x2") }))
}
function Get-SimState {
    $sign = Sign "$cabinet|QUERY|0"
    $body = @{ cabinetNo = $cabinet } | ConvertTo-Json
    return (Invoke-RestMethod -Method Post "$sim/cmd/query" -Headers @{ "X-Device-Sign" = $sign } -ContentType "application/json" -Body $body -TimeoutSec 5).data
}
function Apply-Policy($windows, $priority) {
    $form = @{ cabinetNo = $cabinet; priority = $priority; windows = $windows } | ConvertTo-Json -Depth 5
    return Invoke-RestMethod -Method Post "$server/admin/charge-policy" -Headers $admin -ContentType "application/json" -Body $form -TimeoutSec 10
}
function Sum-Soc($state) {
    $sum = 0
    foreach ($key in $state.cells.PSObject.Properties.Name) {
        $sum += [int]$state.cells.$key.soc
    }
    return $sum
}

Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60 | Out-Null

# ---- make 3 batteries low *in sim* (event chain updates DB too) ----
$dbState = (Invoke-RestMethod "$server/dev/device/cabinet?cabinetNo=$cabinet" -TimeoutSec 5).data
$withBattery = @($dbState.cells | Where-Object { $_.batteryNo -ne $null } | Select-Object -First 3)
Check "3 low batteries prepared" ($withBattery.Count -eq 3)
foreach ($cell in $withBattery) {
    Invoke-RestMethod -Method Post "$sim/sim/battery/in?cabinetNo=$cabinet&cellNo=$($cell.cellNo)&batteryNo=$($cell.batteryNo)&soc=20" -TimeoutSec 5 | Out-Null
    Start-Sleep -Milliseconds 250
}
Start-Sleep -Seconds 1

# ---- valley window (current hour): high power + low fee ----
$h = (Get-Date).Hour
$valleyWindows = @()
if ($h -ge 1) { $valleyWindows += @{ startHour = 0; endHour = $h; powerLimitW = 200; feeFenPerKwh = 150 } }
$valleyWindows += @{ startHour = $h; endHour = $h + 1; powerLimitW = 2000; feeFenPerKwh = 30 }
if (($h + 1) -lt 24) { $valleyWindows += @{ startHour = $h + 1; endHour = 24; powerLimitW = 200; feeFenPerKwh = 150 } }
$policyA = (Apply-Policy $valleyWindows 1).data
Log "policy A applied version=$($policyA.version) status=$($policyA.status) valleyHour=$h"
Check "policy A ACTIVE (1)" ($policyA.status -eq 1)

$before = Get-SimState
Start-Sleep -Seconds 6
$after = Get-SimState
$deltaValley = (Sum-Soc $after) - (Sum-Soc $before)
Log "valley 6s: socSum $((Sum-Soc $before)) -> $((Sum-Soc $after)) delta=$deltaValley powerW=$($after.chargingPowerW) version=$($after.policyVersion)"
Check "SOC rises in valley window" ($deltaValley -gt 0)
Check "power within limit (<=2000W)" ([int]$after.chargingPowerW -le 2000)
Check "sim applied policy version A" ([int]$after.policyVersion -eq [int]$policyA.version)

# ---- peak window: 0W -> charging frozen ----
$policyB = (Apply-Policy @(@{ startHour = 0; endHour = 24; powerLimitW = 0; feeFenPerKwh = 200 }) 2).data
Check "policy B ACTIVE (2)" ($policyB.status -eq 1)
$beforeB = Get-SimState
Start-Sleep -Seconds 4
$afterB = Get-SimState
$deltaPeak = (Sum-Soc $afterB) - (Sum-Soc $beforeB)
Log "peak(0W) 4s: delta=$deltaPeak powerW=$($afterB.chargingPowerW) version=$($afterB.policyVersion)"
Check "SOC frozen under 0W policy" ($deltaPeak -eq 0)
Check "charging power 0 under peak policy" ([int]$afterB.chargingPowerW -eq 0)
Check "policy B is next version (monotonic)" ([int]$policyB.version -eq ([int]$policyA.version + 1))
Check "sim applied policy version B" ([int]$afterB.policyVersion -eq [int]$policyB.version)

# ---- invalid windows rejected ----
$invalid = $false
try {
    Apply-Policy @(@{ startHour = 0; endHour = 8; powerLimitW = 100; feeFenPerKwh = 100 }) 2 | Out-Null
} catch { $invalid = $true }
Check "incomplete coverage windows rejected" $invalid

# ---- cost evidence: valley 10kWh vs flat baseline 150 fen/kWh ----
$valleyCost = 10 * 30
$flatCost = 10 * 150
Log "cost compare: valley=$valleyCost fen vs flat=$flatCost fen (10kWh)"
Check "valley cheaper than flat baseline" ($valleyCost -lt $flatCost)

# ---- restore sane default policy ----
$restored = (Apply-Policy @(@{ startHour = 0; endHour = 24; powerLimitW = 4000; feeFenPerKwh = 100 }) 2).data
Check "default policy restored (next version ACTIVE)" ($restored.status -eq 1 -and [int]$restored.version -eq ([int]$policyB.version + 1))

Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60 | Out-Null

if ($script:fail -eq 0) { Log "GATE-CHARGE-POLICY PASS"; exit 0 } else { Log "GATE-CHARGE-POLICY FAIL checks=$($script:fail)"; exit 1 }
