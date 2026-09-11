# S4.5 gate: dashboard overview metrics + aggregate cache
# Prereq: server :8400; admin token in .local/admin-token.txt
# Evidence: rates in [0,1] with consistent counts; two reads -> same generatedAt (cache);
#           cache stats rebuild:dashboard:overview == 1
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c8_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S4.5 gate: dashboard ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }

$before = ((Invoke-RestMethod "$server/admin/cache/stats" -Headers $admin -TimeoutSec 5).data.PSObject.Properties['rebuild:dashboard:overview']).Value
if ($before -eq $null) { $before = 0 }

$o1 = (Invoke-RestMethod "$server/admin/dashboard/overview" -Headers $admin -TimeoutSec 10).data
Log "overview#1 full=$($o1.fullBatteries)/$($o1.totalBatteries) rate=$($o1.fullBatteryRate) stations=$($o1.availableStations)/$($o1.totalStations) turnover=$($o1.turnoverRate) generatedAt=$($o1.generatedAt)"
Check "fullBatteries <= totalBatteries" ([int64]$o1.fullBatteries -le [int64]$o1.totalBatteries)
Check "fullBatteryRate in [0,1]" ([double]$o1.fullBatteryRate -ge 0 -and [double]$o1.fullBatteryRate -le 1)
Check "stationAvailabilityRate in [0,1]" ([double]$o1.stationAvailabilityRate -ge 0 -and [double]$o1.stationAvailabilityRate -le 1)
Check "turnoverRate >= 0" ([double]$o1.turnoverRate -ge 0)

Start-Sleep -Seconds 2
$o2 = (Invoke-RestMethod "$server/admin/dashboard/overview" -Headers $admin -TimeoutSec 10).data
Check "second read served from cache (same generatedAt)" ($o1.generatedAt -eq $o2.generatedAt)

$after = ((Invoke-RestMethod "$server/admin/cache/stats" -Headers $admin -TimeoutSec 5).data.PSObject.Properties['rebuild:dashboard:overview']).Value
if ($after -eq $null) { $after = 0 }
Log "dashboard rebuild count before=$before after=$after"
Check "exactly one rebuild across two reads" (([int64]$after - [int64]$before) -eq 1)

if ($script:fail -eq 0) { Log "GATE-DASHBOARD PASS"; exit 0 } else { Log "GATE-DASHBOARD FAIL checks=$($script:fail)"; exit 1 }
