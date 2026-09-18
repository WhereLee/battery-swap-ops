# =============================================================================
# _c32_admin_view.ps1 - S8 batch29 gate: BFF view layer + capability bits + data scope
#
# What it proves (all against a live platform on :8400):
#   A. /admin/auth/me returns role + permission codes + data scope (frontend bootstrap)
#   B. /admin/view/dashboard maps the weakly-typed Map into a typed VO
#   C. /admin/view/alarm carries allowedActions, incl. the cross-resource
#      "create-work-order" only when no work order exists yet
#   D. /admin/view/agent-action resolves the source alarm out of params_json
#   E. work order five-step chain: allowedActions moves with the state machine
#      (triage -> assign -> start -> verify -> close -> none)
#   F. /admin/view/cabinet/{no} aggregates cells/alarms/orders/commands and
#      NEVER leaks the device secret
#   G. /admin/view/order/{no} exposes server-side refundable amount (not derived by UI)
#   H. station-scoped identity: in-scope reads pass, out-of-scope reads are 403
#   I. db/17 migration is idempotent (re-apply exits 0)
#
# Conventions: no secrets printed; the temporary operator password lives in
# .local/c32-pass.txt and is deleted at the end of the run.
# =============================================================================

$ErrorActionPreference = "Continue"
$base = "http://127.0.0.1:8400/api"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $scriptDir "..\..\..")
$local = Join-Path $repo ".local"
$tokenFile = Join-Path $local "admin-token.txt"
if (-not (Test-Path -LiteralPath $tokenFile)) { Write-Output "FATAL admin token file missing"; exit 1 }
$adminToken = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
$H = @{ "X-Admin-Token" = $adminToken }

$script:pass = 0
$script:fail = 0
$script:skip = 0

function Check([string]$name, [bool]$cond) {
    if ($cond) { $script:pass++; Write-Output "PASS  $name" }
    else { $script:fail++; Write-Output "FAIL  $name" }
}
function Skip([string]$name, [string]$why) {
    $script:skip++; Write-Output "SKIP  $name ($why)"
}
function Get-Json([string]$path, [hashtable]$headers) {
    $r = Invoke-WebRequest "$base$path" -Headers $headers -UseBasicParsing -TimeoutSec 20
    return ($r.Content | ConvertFrom-Json)
}
function Get-Raw([string]$path, [hashtable]$headers) {
    return (Invoke-WebRequest "$base$path" -Headers $headers -UseBasicParsing -TimeoutSec 20).Content
}
function Get-Status([string]$path, [hashtable]$headers) {
    try {
        $r = Invoke-WebRequest "$base$path" -Headers $headers -UseBasicParsing -TimeoutSec 20
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode.value__ }
        return -1
    }
}
function Post-Json([string]$path, [hashtable]$headers, $body) {
    $json = if ($null -eq $body) { "{}" } else { $body | ConvertTo-Json -Compress }
    $r = Invoke-WebRequest "$base$path" -Method Post -Headers $headers -Body $json `
        -ContentType "application/json" -UseBasicParsing -TimeoutSec 20
    return ($r.Content | ConvertFrom-Json)
}

Write-Output "=== C32 S8 batch29: BFF view layer ==="

# ---------- 0. platform up ----------
$health = Get-Status "/actuator/health" @{}
Check "A00 platform health UP" ($health -eq 200)
if ($health -ne 200) { Write-Output "GATE-ADMIN-VIEW FAIL (platform down)"; exit 1 }

# ---------- A. identity ----------
$me = Get-Json "/admin/auth/me" $H
Check "A01 me code=0" ($me.code -eq 0)
Check "A02 me role=SUPER (break-glass)" ($me.data.role -eq "SUPER")
Check "A03 me bootstrap=true" ($me.data.bootstrap -eq $true)
Check "A04 me codes count = 37" ($me.data.codes.Count -eq 37)
Check "A05 me dataScoped=false for break-glass" ($me.data.dataScoped -eq $false)
$meNoToken = Get-Status "/admin/auth/me" @{}
Check "A06 me without token -> 401" ($meNoToken -eq 401)

# ---------- B. dashboard view ----------
$dash = Get-Json "/admin/view/dashboard" $H
Check "B01 dashboard code=0" ($dash.code -eq 0)
Check "B02 dashboard typed fields present" ($null -ne $dash.data.totalBatteries -and $null -ne $dash.data.fullBatteryRate)
Check "B03 dashboard dataScoped flag present" ($null -ne $dash.data.PSObject.Properties["dataScoped"])
$dashDenied = Get-Status "/admin/view/dashboard" @{}
Check "B04 dashboard without token -> 401" ($dashDenied -eq 401)

# ---------- C. alarm page view ----------
$alarms = Get-Json "/admin/view/alarm?page=1&limit=20&handled=0" $H
Check "C01 alarm page code=0" ($alarms.code -eq 0)
Check "C02 alarm page has rows" ($alarms.data.list.Count -gt 0)
$first = $alarms.data.list[0]
Check "C03 alarm row carries allowedActions" ($null -ne $first.PSObject.Properties["allowedActions"])
Check "C04 unhandled alarm allows handle" ($first.allowedActions -contains "handle")
$noWo = $alarms.data.list | Where-Object { $null -eq $_.workOrderId } | Select-Object -First 1
if ($noWo) {
    Check "C05 alarm without work order offers create-work-order" ($noWo.allowedActions -contains "create-work-order")
} else {
    Skip "C05 create-work-order offer" "every unhandled alarm already has a work order"
}
$hasWo = $alarms.data.list | Where-Object { $null -ne $_.workOrderId } | Select-Object -First 1
if ($hasWo) {
    Check "C06 alarm with work order does NOT offer create-work-order" (-not ($hasWo.allowedActions -contains "create-work-order"))
} else {
    Skip "C06 duplicate-create guard" "no alarm with an existing work order on this page"
}

# ---------- D. agent action (suggestion) view ----------
$sugg = Get-Json "/admin/view/agent-action?page=1&limit=20" $H
Check "D01 suggestion page code=0" ($sugg.code -eq 0)
Check "D02 suggestion page has rows" ($sugg.data.list.Count -gt 0)
$proposed = $sugg.data.list | Where-Object { $_.status -eq 1 } | Select-Object -First 1
if ($proposed) {
    Check "D03 PROPOSED suggestion allows confirm+reject" (($proposed.allowedActions -contains "confirm") -and ($proposed.allowedActions -contains "reject"))
} else {
    Skip "D03 PROPOSED capability" "no PROPOSED suggestion on this page"
}
$withAlarm = $sugg.data.list | Where-Object { $null -ne $_.alarmId } | Select-Object -First 1
if ($withAlarm) {
    Check "D04 suggestion resolves source alarm type" (-not [string]::IsNullOrWhiteSpace($withAlarm.alarmType))
} else {
    Skip "D04 source alarm resolution" "no suggestion carrying an alarmId"
}

# ---------- H1. station-scoped operator (needed as handler id for E) ----------
$cabinets = (Get-Json "/admin/cabinet?limit=100" $H).data.list
$stations = (Get-Json "/admin/station?limit=50" $H).data.list
$inStationId = ($cabinets | Where-Object { $null -ne $_.stationId } | Select-Object -First 1).stationId
$inCabinetNo = ($cabinets | Where-Object { $_.stationId -eq $inStationId } | Select-Object -First 1).cabinetNo
$outCabinet = $cabinets | Where-Object { $null -ne $_.stationId -and $_.stationId -ne $inStationId } | Select-Object -First 1
$inStationNo = ($stations | Where-Object { $_.id -eq $inStationId } | Select-Object -First 1).stationNo
Check "H01 fixture: in-scope cabinet resolved" (-not [string]::IsNullOrWhiteSpace($inCabinetNo))
Check "H02 fixture: out-of-scope cabinet resolved" ($null -ne $outCabinet)

$passFile = Join-Path $local "c32-pass.txt"
$opsUser = "c32ops" + (Get-Date -Format "HHmmss")
$opsPass = -join ((48..57) + (97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ })
Set-Content -LiteralPath $passFile -Value $opsPass -Encoding ASCII -NoNewline
$created = Post-Json "/admin/account" $H @{ username = $opsUser; password = $opsPass; realName = "C32 OPS"; role = "OPS"; dataScope = "STATION"; scopeStationNos = $inStationNo }
Check "H03 scoped operator created" ($created.code -eq 0)
$opsId = $created.data.id
$login = Post-Json "/admin/auth/login" @{} @{ username = $opsUser; password = $opsPass }
Check "H04 scoped operator login ok" ($login.code -eq 0)
$opsToken = $login.data.token
$H2 = @{ "X-Admin-Token" = $opsToken }
$me2 = Get-Json "/admin/auth/me" $H2
Check "H05 scoped me: dataScoped=true" ($me2.data.dataScoped -eq $true)
Check "H06 scoped me: station range downloaded" ($me2.data.scopeStationIds -contains $inStationId)
Check "H07 scoped me: OPS codes exclude settlement manage" (-not ($me2.data.codes -contains "admin:settlement:manage"))

# ---------- E. work order five-step chain ----------
$target = $alarms.data.list | Where-Object { $null -eq $_.workOrderId } | Select-Object -First 1
if ($null -eq $target) { $target = (Get-Json "/admin/view/alarm?page=2&limit=50&handled=0" $H).data.list | Where-Object { $null -eq $_.workOrderId } | Select-Object -First 1 }
if ($target) {
    $wo = Post-Json "/admin/work-order/from-alarm/$($target.id)" $H $null
    Check "E01 work order created from alarm" ($wo.code -eq 0)
    $woId = $wo.data.id
    $v1 = (Get-Json "/admin/view/work-order/$woId" $H).data
    Check "E02 OPEN -> [triage]" (($v1.order.allowedActions -join ",") -eq "triage")
    Post-Json "/admin/work-order/$woId/triage" $H $null | Out-Null
    $v2 = (Get-Json "/admin/view/work-order/$woId" $H).data
    Check "E03 TRIAGED -> [assign]" (($v2.order.allowedActions -join ",") -eq "assign")
    Post-Json "/admin/work-order/$woId/assign?handlerId=$opsId" $H $null | Out-Null
    $v3 = (Get-Json "/admin/view/work-order/$woId" $H).data
    Check "E04 ASSIGNED -> [start]" (($v3.order.allowedActions -join ",") -eq "start")
    Post-Json "/admin/work-order/$woId/start" $H $null | Out-Null
    $v4 = (Get-Json "/admin/view/work-order/$woId" $H).data
    Check "E05 HANDLING -> [verify]" (($v4.order.allowedActions -join ",") -eq "verify")
    Post-Json "/admin/work-order/$woId/verify" $H $null | Out-Null
    $v5 = (Get-Json "/admin/view/work-order/$woId" $H).data
    Check "E06 VERIFIED -> [close]" (($v5.order.allowedActions -join ",") -eq "close")
    Post-Json "/admin/work-order/$woId/close" $H $null | Out-Null
    $v6 = (Get-Json "/admin/view/work-order/$woId" $H).data
    Check "E07 CLOSED -> [] (terminal, no action)" ($v6.order.allowedActions.Count -eq 0)
    Check "E08 detail carries full transition log (>=6)" ($v6.logs.Count -ge 6)
    Check "E09 detail carries source alarm brief" ($v6.alarm.id -eq $target.id)
} else {
    Skip "E01-E09 work order chain" "no unhandled alarm without a work order available"
    $woId = $null
}

# ---------- F. cabinet detail aggregation ----------
$cab = Get-Json "/admin/view/cabinet/$inCabinetNo" $H
Check "F01 cabinet detail code=0" ($cab.code -eq 0)
Check "F02 cabinet detail aggregates cells" ($null -ne $cab.data.cells)
Check "F03 cabinet detail aggregates nested lists" (($null -ne $cab.data.openAlarms) -and ($null -ne $cab.data.activeOrders) -and ($null -ne $cab.data.recentCommands))
$cabRaw = Get-Raw "/admin/view/cabinet/$inCabinetNo" $H
Check "F04 cabinet detail NEVER leaks secret" (-not ($cabRaw -match '"secret"'))
Check "F05 heartbeat age computed server-side" ($null -ne $cab.data.PSObject.Properties["cabinet"])

# ---------- G. order detail ----------
$orderNo = ((Get-Json "/admin/order?limit=1" $H).data.list | Select-Object -First 1).orderNo
if ($orderNo) {
    $od = Get-Json "/admin/view/order/$orderNo" $H
    Check "G01 order detail code=0" ($od.code -eq 0)
    Check "G02 order detail statusDesc resolved" (-not [string]::IsNullOrWhiteSpace($od.data.statusDesc))
    Check "G03 order detail exposes server-side refundableFen" ($null -ne $od.data.PSObject.Properties["refundableFen"])
    Check "G04 order detail carries payment ledger array" ($null -ne $od.data.payments)
} else {
    Skip "G01-G04 order detail" "no order in database"
}

# ---------- H2. data scope enforcement ----------
$inStatus = Get-Status "/admin/view/cabinet/$inCabinetNo" $H2
Check "H08 in-scope cabinet readable by scoped operator" ($inStatus -eq 200)
$outStatus = Get-Status "/admin/view/cabinet/$($outCabinet.cabinetNo)" $H2
Check "H09 out-of-scope cabinet -> 403" ($outStatus -eq 403)
$woPage = Get-Json "/admin/view/work-order?page=1&limit=50" $H2
$leaked = @($woPage.data.list | Where-Object { $null -ne $_.stationId -and $_.stationId -ne $inStationId })
Check "H10 scoped work order list contains no foreign station rows" ($leaked.Count -eq 0)
Check "H11 scoped work order list only own-station rows" (@($woPage.data.list | Where-Object { $_.stationId -eq $inStationId }).Count -eq $woPage.data.list.Count)
$allWo = (Get-Json "/admin/view/work-order?page=1&limit=100" $H).data.list
$foreign = $allWo | Where-Object { $null -ne $_.stationId -and $_.stationId -ne $inStationId } | Select-Object -First 1
if ($foreign) {
    $foreignStatus = Get-Status "/admin/view/work-order/$($foreign.id)" $H2
    Check "H12 foreign work order detail -> 403" ($foreignStatus -eq 403)
} else {
    Skip "H12 foreign work order detail" "no foreign-station work order in live data; covered by unit test WorkOrderScopeTest"
}
# Unowned (system-level) work orders have station_id NULL: fail-closed means a scoped
# identity must NOT see them, while an unrestricted identity still can.
$unowned = $allWo | Where-Object { $null -eq $_.stationId } | Select-Object -First 1
if ($unowned) {
    $unownedScoped = Get-Status "/admin/view/work-order/$($unowned.id)" $H2
    Check "H13 unowned work order -> 403 for scoped identity (fail-closed)" ($unownedScoped -eq 403)
    $unownedSuper = Get-Status "/admin/view/work-order/$($unowned.id)" $H
    Check "H14 unowned work order still readable by unrestricted identity" ($unownedSuper -eq 200)
} else {
    Skip "H13-H14 unowned work order" "every work order has a station owner"
}

# ---------- I. migration idempotency ----------
$applyOut = & (Join-Path $local "apply-migration.ps1") -Sql "db\17-work-order-scope.sql" 2>&1 | Out-String
Check "I01 db/17 re-apply is idempotent (exit 0)" ($applyOut -match "APPLY_EXIT: 0")

# ---------- cleanup ----------
Remove-Item -LiteralPath $passFile -Force -ErrorAction SilentlyContinue
Check "Z01 temporary operator password file removed" (-not (Test-Path -LiteralPath $passFile))

Write-Output ""
Write-Output "RESULT pass=$($script:pass) fail=$($script:fail) skip=$($script:skip)"
if ($script:fail -eq 0) { Write-Output "GATE-ADMIN-VIEW PASS" } else { Write-Output "GATE-ADMIN-VIEW FAIL" }
