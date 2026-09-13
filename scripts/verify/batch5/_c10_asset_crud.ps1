# S4.5 gate: asset registration CRUD (station/cabinet/cell/battery) + reference guards + pool rebuild
# Prereq: server :8400; admin token in .local/admin-token.txt
# Evidence: create station->cabinet(3 cells)->battery in cell; delete guards; cell shrink; cleanup; pool consistent
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c10_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S4.5 gate: asset CRUD ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$ts = Get-Date -Format "HHmmss"
$stationNo = "ST-E2E-$ts"
$cabinetNo = "SWAP-E2E-$ts"
$batteryNo = "BAT-E2E-$ts"

function Post-Json($url, $obj) {
    return Invoke-RestMethod -Method Post $url -Headers $admin -ContentType "application/json" -Body ($obj | ConvertTo-Json) -TimeoutSec 10
}
function Get-Cells($cabNo) {
    return @((Invoke-RestMethod "$server/admin/cell?page=1&limit=50&cabinetNo=$cabNo" -Headers $admin -TimeoutSec 5).data.list)
}

# ---- station ----
$station = (Post-Json "$server/admin/station" @{ stationNo = $stationNo; name = "E2E Station"; address = "test road 1" }).data
Log "station created id=$($station.id) no=$($station.stationNo)"
Check "station active" ($station.status -eq 1)

# ---- cabinet with 3 cells ----
$cab = (Post-Json "$server/admin/cabinet" @{
    cabinetNo = $cabinetNo; stationId = $station.id; cellCount = 3;
    secret = "aabbccddeeff00112233445566778899"
}).data
Log "cabinet created id=$($cab.id) no=$($cab.cabinetNo)"
Check "secret masked in response" ($cab.secret -eq $null)
$cells = Get-Cells $cabinetNo
Check "3 cells auto-created" ($cells.Count -eq 3)

# ---- grow to 5 cells ----
Post-Json "$server/admin/cabinet/$($cab.id)" @{ cellCount = 5 } | Out-Null
$cells = Get-Cells $cabinetNo
Check "grow cabinet to 5 cells" ($cells.Count -eq 5)

# ---- battery placed into first cell ----
$targetCell = $cells[4]  # tail cell: shrink guard needs occupied cell beyond target count
$bat = (Post-Json "$server/admin/battery" @{ batteryNo = $batteryNo; model = "48V24Ah"; soc = 100; cellId = $targetCell.id }).data
Log "battery created no=$($bat.batteryNo) status=$($bat.status) cellId=$($bat.cellId)"
Check "battery FULL in cell" ($bat.status -eq 2 -and $bat.cellId -eq $targetCell.id)
$cells = Get-Cells $cabinetNo
$cellNow = @($cells | Where-Object { $_.id -eq $targetCell.id })[0]
Check "cell occupied by battery" ($cellNow.batteryId -eq $bat.id)

# ---- guards ----
$guarded = $false
try { Invoke-RestMethod -Method Delete "$server/admin/cabinet/$($cab.id)" -Headers $admin -TimeoutSec 5 | Out-Null } catch { $guarded = $true }
Check "delete cabinet refused (battery inside)" $guarded

$guarded = $false
try { Invoke-RestMethod -Method Delete "$server/admin/battery/$batteryNo" -Headers $admin -TimeoutSec 5 | Out-Null } catch { $guarded = $true }
Check "delete in-cell battery refused" $guarded

$guarded = $false
try { Invoke-RestMethod -Method Delete "$server/admin/station/$($station.id)" -Headers $admin -TimeoutSec 5 | Out-Null } catch { $guarded = $true }
Check "delete station refused (cabinet attached)" $guarded

$guarded = $false
try { Post-Json "$server/admin/cabinet/$($cab.id)" @{ cellCount = 2 } | Out-Null } catch { $guarded = $true }
Check "shrink refused (occupied cell beyond)" $guarded

# ---- cleanup: park battery -> delete -> shrink -> delete cabinet -> delete station ----
Post-Json "$server/admin/battery/$batteryNo" @{ park = $true } | Out-Null
$parked = (Invoke-RestMethod "$server/admin/battery?page=1&limit=10&batteryNo=$batteryNo" -Headers $admin -TimeoutSec 5).data.list
Check "battery parked (cellId null)" (@($parked)[0].cellId -eq $null)

Invoke-RestMethod -Method Delete "$server/admin/battery/$batteryNo" -Headers $admin -TimeoutSec 5 | Out-Null
Post-Json "$server/admin/cabinet/$($cab.id)" @{ cellCount = 3 } | Out-Null
Check "shrink cabinet to 3 cells after battery removed" ((Get-Cells $cabinetNo).Count -eq 3)
Invoke-RestMethod -Method Delete "$server/admin/cabinet/$($cab.id)" -Headers $admin -TimeoutSec 5 | Out-Null
Invoke-RestMethod -Method Delete "$server/admin/station/$($station.id)" -Headers $admin -TimeoutSec 5 | Out-Null

$adminCabs = @((Invoke-RestMethod "$server/admin/cabinet?page=1&limit=100&cabinetNo=$cabinetNo" -Headers $admin -TimeoutSec 5).data.list)
Check "cabinet cleaned up" ($adminCabs.Count -eq 0)

# ---- pool still consistent for existing user flow ----
$token = (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body '{"phone":"13800000001"}' -TimeoutSec 5).data.token
$stations = @((Invoke-RestMethod "$server/user/stations" -Headers @{ "X-User-Token" = $token } -TimeoutSec 5).data)
Check "user stations endpoint still healthy" ($stations.Count -ge 1)

if ($script:fail -eq 0) { Log "GATE-ASSET-CRUD PASS"; exit 0 } else { Log "GATE-ASSET-CRUD FAIL checks=$($script:fail)"; exit 1 }
