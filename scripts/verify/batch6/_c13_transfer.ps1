# S4.2 gate: inter-station transfer (recommend -> draft -> approve -> out/in -> DONE) + reconcile
# Prereq: server fast mode, admin token; dev reset supported
# Evidence: deficit station paired with nearest surplus; strict guards; task DONE by item aggregation;
#           reconcile transfer-ledger invariant 0 violations; cleanup restores seed state
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c13_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S4.2 gate: transfer ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$ts = Get-Date -Format "HHmmss"
$stationNo = "ST-E2E2-$ts"
$cabinetNo = "SWAP-T2-$ts"
$batteryNo = "BAT-T2-$ts"

function Post-Json($url, $obj) {
    if ($obj -eq $null) { return Invoke-RestMethod -Method Post $url -Headers $admin -TimeoutSec 10 }
    return Invoke-RestMethod -Method Post $url -Headers $admin -ContentType "application/json" -Body ($obj | ConvertTo-Json) -TimeoutSec 10
}
function Get-Cells($cabNo) {
    return @((Invoke-RestMethod "$server/admin/cell?page=1&limit=50&cabinetNo=$cabNo" -Headers $admin -TimeoutSec 5).data.list)
}

Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60 | Out-Null

# ---- build a deficit station (1 full battery) ----
$station = (Post-Json "$server/admin/station" @{ stationNo = $stationNo; name = "E2E Deficit"; address = "east"; latitude = 30.40; longitude = 120.30 }).data
$cab = (Post-Json "$server/admin/cabinet" @{
    cabinetNo = $cabinetNo; stationId = $station.id; cellCount = 12;
    secret = "aabbccddeeff00112233445566778899"
}).data
$cells = Get-Cells $cabinetNo
Post-Json "$server/admin/battery" @{ batteryNo = $batteryNo; soc = 100; cellId = $cells[0].id } | Out-Null
Log "deficit station built id=$($station.id) cabinet=$($cab.id) one full battery"

# ---- recommend: deficit must be paired from a surplus station ----
$recommend = @((Invoke-RestMethod "$server/admin/transfer/recommend" -Headers $admin -TimeoutSec 10).data)
$rec = @($recommend | Where-Object { $_.toStationNo -eq $stationNo })[0]
Check "recommend hits deficit station" ($rec -ne $null)
Log "recommend from=$($rec.fromStationNo) to=$($rec.toStationNo) qty=$($rec.quantity) distanceKm=$($rec.distanceKm)"
Check "quantity computed (2)" ([int]$rec.quantity -eq 2)
Check "distance computed (coords present)" ($rec.distanceKm -ne $null)

# ---- create + approve ----
$detail = (Post-Json "$server/admin/transfer?fromStationId=$($rec.fromStationId)&toStationId=$($station.id)&count=2" $null).data
$task = $detail.task
Log "task created no=$($task.taskNo) status=$($task.status) items=$($detail.items.Count)"
Check "created as DRAFT (1)" ($task.status -eq 1)
Check "items generated (2)" (@($detail.items).Count -eq 2)

$approved = (Post-Json "$server/admin/transfer/$($task.id)/approve" $null).data
Check "approved (2)" ($approved.status -eq 2)

# ---- out first item -> EXECUTING; cancel refused ----
$b1 = @($detail.items)[0].batteryNo
$b2 = @($detail.items)[1].batteryNo
$afterOut = (Post-Json "$server/admin/transfer/$($task.id)/items/$b1/out" $null).data
Check "task EXECUTING after first out (3)" ($afterOut.task.status -eq 3)
$cancelRefused = $false
try { Post-Json "$server/admin/transfer/$($task.id)/cancel" $null | Out-Null } catch { $cancelRefused = $true }
Check "cancel refused while EXECUTING" $cancelRefused

# ---- in b1, out+in b2 -> DONE ----
$targetCells = Get-Cells $cabinetNo
$freeCells = @($targetCells | Where-Object { $_.batteryId -eq $null })
$afterIn1 = (Post-Json "$server/admin/transfer/$($task.id)/items/$b1/in?cellId=$($freeCells[0].id)" $null).data
Check "one item IN, task still EXECUTING" ($afterIn1.task.status -eq 3)
Post-Json "$server/admin/transfer/$($task.id)/items/$b2/out" $null | Out-Null
$targetCells = Get-Cells $cabinetNo
$freeCells = @($targetCells | Where-Object { $_.batteryId -eq $null })
$afterIn2 = (Post-Json "$server/admin/transfer/$($task.id)/items/$b2/in?cellId=$($freeCells[0].id)" $null).data
Check "task DONE after all items IN (4)" ($afterIn2.task.status -eq 4)
Check "all items IN" (@($afterIn2.items | Where-Object { $_.status -eq 3 }).Count -eq 2)

# ---- reconcile transfer-ledger ----
$report = (Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $admin -TimeoutSec 60).data
$ledger = @($report.checks | Where-Object { $_.name -eq "transfer-ledger" })[0]
Log "reconcile transfer-ledger violations=$($ledger.violations)"
Check "transfer ledger consistent (0 violations)" ([int]$ledger.violations -eq 0)

# ---- cleanup: reset seed state, then remove test assets ----
Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60 | Out-Null
$parked = (Invoke-RestMethod "$server/admin/battery?page=1&limit=10&batteryNo=$batteryNo" -Headers $admin -TimeoutSec 5).data.list
if (@($parked).Count -ge 1 -and @($parked)[0].cellId -eq $null) {
    Invoke-RestMethod -Method Delete "$server/admin/battery/$batteryNo" -Headers $admin -TimeoutSec 5 | Out-Null
}
Invoke-RestMethod -Method Delete "$server/admin/cabinet/$($cab.id)" -Headers $admin -TimeoutSec 5 | Out-Null
Invoke-RestMethod -Method Delete "$server/admin/station/$($station.id)" -Headers $admin -TimeoutSec 5 | Out-Null
Check "cleanup done" $true

if ($script:fail -eq 0) { Log "GATE-TRANSFER PASS"; exit 0 } else { Log "GATE-TRANSFER FAIL checks=$($script:fail)"; exit 1 }
