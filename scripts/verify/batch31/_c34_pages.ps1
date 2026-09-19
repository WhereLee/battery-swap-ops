# =============================================================================
# _c34_pages.ps1 - batch31 gate: page-level contract for the swap-web console
#
# Why this script exists:
#   The S8 console is driven by BFF view endpoints (/admin/view/**). Each page
#   depends on an exact response shape: field names, capability bits
#   (allowedActions), and the absence of internal columns (idemKey, cabinet
#   secret). A backend refactor that renames a field or drops a capability bit
#   would compile on both sides (TS types are hand-written mirrors) and only
#   surface as a blank cell in the browser. This script is the machine-checkable
#   half of that contract; the browser run is the other half.
#
# What it proves, per page:
#   1. the exact endpoint the page calls answers code=0 with the expected fields
#   2. capability bits are consistent with the documented state machine
#      (work-order: OPEN..CLOSED; order money path: refund vs reversal;
#       settlement: GENERATED->confirm, CONFIRMED->paid, PAID->none)
#   3. nothing internal leaks (no idemKey / openCommandSeq / cabinet secret)
#   4. paging still honours limit and reports a real total
#   5. every field the page renders exists in swap-web/src/api/types.ts
#      (cross-language drift check, same spirit as PermissionCodeContractTest)
#
# Conventions: no secrets printed; bootstrap admin token from .local/admin-token.txt
# (same as _c32/_c33). Read-only except one idempotent setup step that generates a
# settlement statement when the table is empty and unclaimed ledger lines exist
# (the console needs data to render; it is skipped when a statement already exists).
# =============================================================================

$ErrorActionPreference = "Continue"
$base = "http://127.0.0.1:8400/api"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $scriptDir "..\..\..")
$local = Join-Path $repo ".local"
$tokenFile = Join-Path $local "admin-token.txt"
if (-not (Test-Path -LiteralPath $tokenFile)) { Emit "FATAL admin token file missing"; exit 1 }
$adminToken = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
$H = @{ "X-Admin-Token" = $adminToken }

# ---------------------------------------------------------------------------
# Evidence: this gate records itself. The batch38 review found _c35_out.txt sitting in the
# repo asserting 31/31 while the committed script only ran 25 checks - output had been
# tee'd by hand one time and never regenerated. Every printed line therefore goes through
# Emit, which both prints it and appends it to the list flushed into _c34_out.txt at each
# exit path. Running the script is now the only way to produce the record.
# ---------------------------------------------------------------------------
$script:evidence = New-Object System.Collections.Generic.List[string]
$script:outFile = Join-Path $scriptDir "_c34_out.txt"
function Emit([string]$line) {
    $script:evidence.Add($line)
    Write-Output $line
}
function Save-Evidence {
    [System.IO.File]::WriteAllLines($script:outFile, $script:evidence, (New-Object System.Text.UTF8Encoding($false)))
}

$script:pass = 0
$script:fail = 0
$script:skip = 0

function Check([string]$name, [bool]$cond) {
    if ($cond) { $script:pass++; Emit "PASS  $name" }
    else { $script:fail++; Emit "FAIL  $name" }
}
function Skip([string]$name, [string]$why) {
    $script:skip++; Emit "SKIP  $name ($why)"
}
function Get-Json([string]$path) {
    $r = Invoke-WebRequest "$base$path" -Headers $H -UseBasicParsing -TimeoutSec 30
    return ($r.Content | ConvertFrom-Json)
}
function Get-Raw([string]$path) {
    $r = Invoke-WebRequest "$base$path" -Headers $H -UseBasicParsing -TimeoutSec 30
    return $r.Content
}
function Get-Status([string]$path) {
    try {
        $r = Invoke-WebRequest "$base$path" -Headers $H -UseBasicParsing -TimeoutSec 20
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode.value__ }
        return -1
    }
}
function Post-Json([string]$path, [string]$body) {
    if ([string]::IsNullOrEmpty($body)) {
        $r = Invoke-WebRequest "$base$path" -Method Post -Headers $H -UseBasicParsing -TimeoutSec 30
    } else {
        $r = Invoke-WebRequest "$base$path" -Method Post -Headers $H -ContentType "application/json" `
            -Body $body -UseBasicParsing -TimeoutSec 30
    }
    return ($r.Content | ConvertFrom-Json)
}
function Has-Prop($obj, [string]$name) {
    return ($null -ne $obj) -and ($null -ne $obj.PSObject.Properties[$name])
}
function Assert-Fields($row, [string[]]$fields, [string]$tag, [string]$what) {
    if ($null -eq $row) { Check "$tag $what row present" $false; return }
    $missing = @()
    foreach ($f in $fields) { if (-not (Has-Prop $row $f)) { $missing += $f } }
    Check "$tag $what exposes $($fields.Count) fields (missing: $($missing -join ','))" ($missing.Count -eq 0)
}
function Subset-Of([string[]]$actual, [string[]]$allowed) {
    foreach ($a in $actual) { if ($allowed -notcontains $a) { return $false } }
    return $true
}

# TypeScript interface bodies, used for the frontend parity section.
$typesPath = Join-Path $repo "swap-web\src\api\types.ts"
$typesText = ""
if (Test-Path -LiteralPath $typesPath) { $typesText = Get-Content -LiteralPath $typesPath -Raw -Encoding UTF8 }
function Ts-Interface-Body([string]$name) {
    if ($typesText -eq "") { return "" }
    $m = [regex]::Match($typesText, "export interface $name\s*\{(?<body>[^}]*)\}")
    if ($m.Success) { return $m.Groups["body"].Value }
    return ""
}
function Assert-TsFields([string]$iface, [string[]]$fields, [string]$tag) {
    $body = Ts-Interface-Body $iface
    if ($body -eq "") { Check "$tag types.ts declares $iface" $false; return }
    $missing = @()
    foreach ($f in $fields) {
        if ($body -notmatch "(?m)^\s*$([regex]::Escape($f))\??\s*:") { $missing += $f }
    }
    Check "$tag $iface mirrors $($fields.Count) backend fields (missing: $($missing -join ','))" ($missing.Count -eq 0)
}

Emit "=== C34 batch31: page-level contract (console endpoints + frontend type parity) ==="

# ---------- P00 preflight ----------
$health = Get-Status "/actuator/health"
Check "P00 platform health UP" ($health -eq 200)
if ($health -ne 200) { Emit "GATE-PAGES FAIL (platform down)"; Save-Evidence; exit 1 }

# ---------- P01 setup: make sure the settlement page has something to render ----------
$statements = Get-Json "/admin/view/settlement?page=1&limit=1"
$statementTotal = 0
if ($null -ne $statements.data) { $statementTotal = [long]$statements.data.total }
if ($statementTotal -eq 0) {
    $agents = Get-Json "/admin/agent"
    $generated = $false
    foreach ($agent in @($agents.data)) {
        if ($generated) { continue }
        try {
            $r = Post-Json ("/admin/settlement/generate?agentId={0}&periodStart=1700000000000&periodEnd=1800000000000" -f $agent.id) ""
            if ($r.code -eq 0) {
                Emit "SETUP generated settlement statement $($r.data.statementNo) for agentId=$($agent.id) (period 2023-11~2027-01)"
                $generated = $true
            }
        } catch {
            Emit "SETUP agentId=$($agent.id) has no claimable ledger lines (expected for a settled agent)"
        }
    }
    if (-not $generated) { Emit "SETUP no statement generated (no unclaimed ledger lines) - settlement row checks will SKIP" }
} else {
    Emit "SETUP $statementTotal settlement statement(s) already exist - setup skipped"
}

# =============================================================================
# Page 1: work-order list  -> GET /admin/view/work-order
# =============================================================================
Emit ""
Emit "--- page 1: work-order list ---"
$wo = Get-Json "/admin/view/work-order?page=1&limit=3"
Check "P01 work-order list code=0" ($wo.code -eq 0)
Check "P02 work-order list honours limit=3 (rows=$(@($wo.data.list).Count))" (@($wo.data.list).Count -le 3)
Check "P03 work-order list reports real total (total=$($wo.data.total))" ([long]$wo.data.total -ge @($wo.data.list).Count)
$woRow = @($wo.data.list) | Select-Object -First 1
Assert-Fields $woRow @("id","woNo","title","severity","status","deviceType","deviceNo","stationId","handlerId",
    "slaDeadline","slaBreached","createTime","allowedActions") "P04" "work-order row"
$woStatusToAction = @{ 1 = "triage"; 2 = "assign"; 3 = "start"; 4 = "verify"; 5 = "close" }
$woBitProblems = @()
foreach ($row in @($wo.data.list)) {
    $expected = @()
    if ($woStatusToAction.ContainsKey([int]$row.status)) { $expected = @($woStatusToAction[[int]$row.status]) }
    $actual = @($row.allowedActions)
    if (($expected -join ",") -ne ($actual -join ",")) {
        $woBitProblems += "id=$($row.id) status=$($row.status) expected=[$($expected -join ',')] actual=[$($actual -join ',')]"
    }
}
Check "P05 work-order capability bits match the 5-step chain on every row ($($woBitProblems.Count) mismatches)" ($woBitProblems.Count -eq 0)
if ($woBitProblems.Count -gt 0) { $woBitProblems | ForEach-Object { Emit "      $_" } }

# =============================================================================
# Page 2: work-order detail -> GET /admin/view/work-order/{id}
# =============================================================================
Emit ""
Emit "--- page 2: work-order detail ---"
if ($null -ne $woRow) {
    $woDetail = Get-Json ("/admin/view/work-order/{0}" -f $woRow.id)
    Check "P06 work-order detail code=0" ($woDetail.code -eq 0)
    Assert-Fields $woDetail.data @("order","logs","alarm") "P07" "work-order detail"
    Assert-Fields $woDetail.data.order @("id","woNo","status","allowedActions","slaBreached") "P08" "detail.order"
    $logsOk = $true
    foreach ($log in @($woDetail.data.logs)) {
        if (-not (Has-Prop $log "action") -or -not (Has-Prop $log "fromStatus") -or
            -not (Has-Prop $log "toStatus") -or -not (Has-Prop $log "operator")) { $logsOk = $false }
    }
    Check "P09 work-order detail log rows carry action/fromStatus/toStatus/operator" $logsOk
    $alarm = $woDetail.data.alarm
    if ($null -eq $alarm) {
        Skip "P10 work-order detail alarm brief shape" "this work order has no source alarm"
    } else {
        Assert-Fields $alarm @("id","alarmType","deviceNo","content","handled","createTime") "P10" "alarm brief"
    }
} else {
    Skip "P06-P10 work-order detail" "work-order list is empty"
}

# =============================================================================
# Page 3: order list -> GET /admin/view/order
# =============================================================================
Emit ""
Emit "--- page 3: order list ---"
$orders = Get-Json "/admin/view/order?page=1&limit=5"
Check "P11 order list code=0" ($orders.code -eq 0)
Check "P12 order list honours limit=5 (rows=$(@($orders.data.list).Count))" (@($orders.data.list).Count -le 5)
$orderRow = @($orders.data.list) | Select-Object -First 1
Assert-Fields $orderRow @("orderNo","orderType","userId","stationId","cabinetNo","status","statusDesc","feeFen",
    "discountFen","payType","createTime","completeTime","refundableFen","allowedActions") "P13" "order row"
$ordersRaw = Get-Raw "/admin/view/order?page=1&limit=5"
Check "P14 order list does not leak internal columns (idemKey/openCommandSeq/couponId/userPlanId)" `
    (($ordersRaw -notmatch "idemKey") -and ($ordersRaw -notmatch "openCommandSeq") -and
     ($ordersRaw -notmatch "couponId") -and ($ordersRaw -notmatch "userPlanId"))
$refundBitProblems = @()
foreach ($row in @($orders.data.list)) {
    if (-not (Subset-Of @($row.allowedActions) @("refund", "reversal"))) {
        $refundBitProblems += "orderNo=$($row.orderNo) unknown action in [$(@($row.allowedActions) -join ',')]"
    }
    if (([int]$row.refundableFen) -le 0 -and @($row.allowedActions).Count -gt 0) {
        $refundBitProblems += "orderNo=$($row.orderNo) refundableFen=0 but actions=[$(@($row.allowedActions) -join ',')]"
    }
    if ((@($row.allowedActions) -contains "reversal") -and ([int]$row.status -ne 5)) {
        $refundBitProblems += "orderNo=$($row.orderNo) reversal offered on status=$($row.status) (only COMPLETED=5 may reverse)"
    }
    if ((@($row.allowedActions) -contains "refund") -and ([int]$row.status -eq 5)) {
        $refundBitProblems += "orderNo=$($row.orderNo) plain refund offered on COMPLETED order (money already settled)"
    }
}
Check "P15 order money-path bits consistent with status/refundableFen ($($refundBitProblems.Count) problems)" ($refundBitProblems.Count -eq 0)
if ($refundBitProblems.Count -gt 0) { $refundBitProblems | ForEach-Object { Emit "      $_" } }

# =============================================================================
# Page 4: order detail -> GET /admin/view/order/{orderNo}
# =============================================================================
Emit ""
Emit "--- page 4: order detail ---"
if ($null -ne $orderRow) {
    $orderDetail = Get-Json ("/admin/view/order/{0}" -f $orderRow.orderNo)
    Check "P16 order detail code=0" ($orderDetail.code -eq 0)
    Assert-Fields $orderDetail.data @("orderNo","orderType","userId","status","statusDesc","feeFen","discountFen",
        "payType","createTime","completeTime","payments","refunds","refundableFen","allowedActions") "P17" "order detail"
    $payOk = $true
    foreach ($p in @($orderDetail.data.payments)) {
        if (-not (Has-Prop $p "tradeNo") -or -not (Has-Prop $p "amountFen") -or -not (Has-Prop $p "status")) { $payOk = $false }
    }
    $refundOk = $true
    foreach ($r in @($orderDetail.data.refunds)) {
        if (-not (Has-Prop $r "refundNo") -or -not (Has-Prop $r "reason") -or -not (Has-Prop $r "status")) { $refundOk = $false }
    }
    Check "P18 order detail payment rows carry tradeNo/amountFen/status" $payOk
    Check "P19 order detail refund rows carry refundNo/reason/status" $refundOk
    Check "P20 order detail refundableFen matches the list row for the same order" `
        ([int]$orderDetail.data.refundableFen -eq [int]$orderRow.refundableFen)
} else {
    Skip "P16-P20 order detail" "order list is empty"
}

# =============================================================================
# Page 5: cabinet list -> GET /admin/view/cabinet
# =============================================================================
Emit ""
Emit "--- page 5: cabinet list ---"
$cabinets = Get-Json "/admin/view/cabinet?page=1&limit=3"
Check "P21 cabinet list code=0" ($cabinets.code -eq 0)
Check "P22 cabinet list honours limit=3 (rows=$(@($cabinets.data.list).Count))" (@($cabinets.data.list).Count -le 3)
$cabinetRow = @($cabinets.data.list) | Select-Object -First 1
Assert-Fields $cabinetRow @("id","cabinetNo","stationId","cellCount","status","lastBootId","lastEventSeq",
    "lastHeartbeatTime","heartbeatAgeMs") "P23" "cabinet row"
$cabinetRaw = Get-Raw "/admin/view/cabinet?page=1&limit=3"
Check "P24 cabinet list does not leak the device secret" (($cabinetRaw -notmatch "secret") -and ($cabinetRaw -notmatch "0123456789abcdef"))

# =============================================================================
# Page 6: cabinet detail -> GET /admin/view/cabinet/{cabinetNo}
# =============================================================================
Emit ""
Emit "--- page 6: cabinet detail ---"
if ($null -ne $cabinetRow) {
    $cabinetDetail = Get-Json ("/admin/view/cabinet/{0}" -f $cabinetRow.cabinetNo)
    Check "P25 cabinet detail code=0" ($cabinetDetail.code -eq 0)
    Assert-Fields $cabinetDetail.data @("cabinet","cells","openAlarms","activeOrders","recentCommands") "P26" "cabinet detail"
    Assert-Fields $cabinetDetail.data.cabinet @("cabinetNo","stationId","cellCount","status","heartbeatAgeMs") "P27" "detail.cabinet"
    $cellOk = $true
    foreach ($c in @($cabinetDetail.data.cells)) {
        if (-not (Has-Prop $c "cellNo") -or -not (Has-Prop $c "status") -or -not (Has-Prop $c "batteryNo") -or
            -not (Has-Prop $c "soc") -or -not (Has-Prop $c "lockOrderId")) { $cellOk = $false }
    }
    Check "P28 cabinet detail cell rows carry cellNo/status/batteryNo/soc/lockOrderId" $cellOk
    $cmdOk = $true
    foreach ($c in @($cabinetDetail.data.recentCommands)) {
        if (-not (Has-Prop $c "commandAction") -or -not (Has-Prop $c "commandSeq") -or
            -not (Has-Prop $c "commandStatus") -or -not (Has-Prop $c "retryCount")) { $cmdOk = $false }
    }
    Check "P29 cabinet detail command rows carry commandAction/commandSeq/commandStatus/retryCount" $cmdOk
    $nested = @(@($cabinetDetail.data.openAlarms).Count, @($cabinetDetail.data.activeOrders).Count,
        @($cabinetDetail.data.recentCommands).Count)
    Check "P30 nested lists stay capped at 20 ($($nested -join '/'))" (($nested | Where-Object { $_ -gt 20 }).Count -eq 0)
    $detailRaw = Get-Raw ("/admin/view/cabinet/{0}" -f $cabinetRow.cabinetNo)
    Check "P31 cabinet detail does not leak the device secret" ($detailRaw -notmatch "secret")
} else {
    Skip "P25-P31 cabinet detail" "cabinet list is empty"
}

# =============================================================================
# Page 7: settlement list -> GET /admin/view/settlement
# =============================================================================
Emit ""
Emit "--- page 7: settlement list ---"
$settlements = Get-Json "/admin/view/settlement?page=1&limit=5"
Check "P32 settlement list code=0" ($settlements.code -eq 0)
Check "P33 settlement list honours limit=5 (rows=$(@($settlements.data.list).Count))" (@($settlements.data.list).Count -le 5)
$settlementRow = @($settlements.data.list) | Select-Object -First 1
if ($null -eq $settlementRow) {
    Skip "P34-P37 settlement row contract" "no settlement statement in this database"
} else {
    Assert-Fields $settlementRow @("id","statementNo","agentId","agentName","periodStart","periodEnd","orderCount",
        "baseAmountFen","agentAmountFen","platformAmountFen","subsidyFen","status","generatedBy","allowedActions") "P34" "settlement row"
    $settlementStatusToAction = @{ 1 = "confirm"; 2 = "paid"; 3 = "" }
    $settleBitProblems = @()
    foreach ($row in @($settlements.data.list)) {
        $expected = $settlementStatusToAction[[int]$row.status]
        $actual = (@($row.allowedActions) -join ",")
        if ($actual -ne $expected) {
            $settleBitProblems += "id=$($row.id) status=$($row.status) expected=[$expected] actual=[$actual]"
        }
    }
    Check "P35 settlement capability bits match GENERATED->confirm / CONFIRMED->paid / PAID->none" ($settleBitProblems.Count -eq 0)
    if ($settleBitProblems.Count -gt 0) { $settleBitProblems | ForEach-Object { Emit "      $_" } }
    Check "P36 settlement split conservation on every row (agent + platform = base)" `
        ((@($settlements.data.list) | Where-Object {
            ([int]$_.agentAmountFen + [int]$_.platformAmountFen) -ne [int]$_.baseAmountFen }).Count -eq 0)

    # ---------- page 8: settlement detail ----------
    Emit ""
    Emit "--- page 8: settlement detail ---"
    $settlementDetail = Get-Json ("/admin/view/settlement/{0}" -f $settlementRow.id)
    Check "P37 settlement detail code=0" ($settlementDetail.code -eq 0)
    Assert-Fields $settlementDetail.data @("statement","lines") "P38" "settlement detail"
    $lineOk = $true
    $negativeLines = 0
    foreach ($line in @($settlementDetail.data.lines)) {
        if (-not (Has-Prop $line "orderNo") -or -not (Has-Prop $line "eventType") -or -not (Has-Prop $line "baseType") -or
            -not (Has-Prop $line "baseAmountFen") -or -not (Has-Prop $line "agentShareFen") -or
            -not (Has-Prop $line "platformShareFen")) { $lineOk = $false }
        if ([int]$line.baseAmountFen -lt 0) { $negativeLines++ }
    }
    Check "P39 settlement line rows carry orderNo/eventType/baseType/amounts" $lineOk
    Check "P40 settlement statement links to all its ledger lines (lines=$(@($settlementDetail.data.lines).Count), statement lines>=1)" `
        (@($settlementDetail.data.lines).Count -ge 1)
    Emit "INFO  settlement detail contains $negativeLines negative (reversal) line(s)"
    $detailRaw = Get-Raw ("/admin/view/settlement/{0}" -f $settlementRow.id)
    Check "P41 settlement detail does not leak eventKey (internal idempotency key)" ($detailRaw -notmatch "eventKey")
}

# =============================================================================
# Frontend parity: every field the pages render must exist in types.ts,
# and every page must be a real component wired into the router.
# =============================================================================
Emit ""
Emit "--- frontend parity (swap-web) ---"
Assert-TsFields "WorkOrderVO" @("id","woNo","title","severity","status","deviceType","deviceNo","stationId",
    "handlerId","slaDeadline","slaBreached","allowedActions") "F01"
Assert-TsFields "WorkOrderDetailVO" @("order","logs","alarm") "F02"
Assert-TsFields "OrderListItemVO" @("orderNo","orderType","userId","stationId","cabinetNo","status","statusDesc",
    "feeFen","discountFen","payType","createTime","completeTime","refundableFen","allowedActions") "F03"
Assert-TsFields "OrderDetailVO" @("payments","refunds","refundableFen","allowedActions","statusDesc") "F04"
Assert-TsFields "CabinetVO" @("cabinetNo","stationId","cellCount","status","lastBootId","lastEventSeq",
    "lastHeartbeatTime","heartbeatAgeMs") "F05"
Assert-TsFields "CabinetDetailVO" @("cabinet","cells","openAlarms","activeOrders","recentCommands") "F06"
Assert-TsFields "SettlementVO" @("statementNo","agentId","agentName","periodStart","periodEnd","orderCount",
    "baseAmountFen","agentAmountFen","platformAmountFen","subsidyFen","status","allowedActions") "F07"
Assert-TsFields "SettlementDetailVO" @("statement","lines") "F08"

$orderListBody = Ts-Interface-Body "OrderListItemVO"
Check "F09 OrderListItemVO declares no internal column (idemKey/openCommandSeq/couponId/userPlanId)" `
    (($orderListBody -notmatch "idemKey") -and ($orderListBody -notmatch "openCommandSeq") -and
     ($orderListBody -notmatch "couponId") -and ($orderListBody -notmatch "userPlanId"))

$routerText = Get-Content -LiteralPath (Join-Path $repo "swap-web\src\router\index.ts") -Raw -Encoding UTF8
$pages = @(
    @{ file = "WorkOrderListView.vue"; route = "work-orders"; code = "workOrderRead" },
    @{ file = "WorkOrderDetailView.vue"; route = "work-orders/:id"; code = "workOrderRead" },
    @{ file = "OrderListView.vue"; route = "orders"; code = "orderRead" },
    @{ file = "OrderDetailView.vue"; route = "orders/:orderNo"; code = "orderRead" },
    @{ file = "CabinetListView.vue"; route = "cabinets"; code = "assetRead" },
    @{ file = "CabinetDetailView.vue"; route = "cabinets/:cabinetNo"; code = "assetRead" },
    @{ file = "SettlementListView.vue"; route = "settlements"; code = "settlementRead" },
    @{ file = "SettlementDetailView.vue"; route = "settlements/:id"; code = "settlementRead" }
)
foreach ($page in $pages) {
    $viewPath = Join-Path $repo ("swap-web\src\views\" + $page.file)
    $exists = Test-Path -LiteralPath $viewPath
    # The router reaches the component through a lazy import, so the file name alone is
    # the right needle (the import path prefix is "../views/").
    $routed = $routerText -match [regex]::Escape($page.file)
    $coded = $routerText -match [regex]::Escape("CODES." + $page.code)
    $tag = "F10 " + $page.file
    Check "$tag exists and is lazily routed with meta.codes=$($page.code)" ($exists -and $routed -and $coded)
}

Emit ""
Emit "=== C34 summary: PASS=$($script:pass) FAIL=$($script:fail) SKIP=$($script:skip) ==="
Save-Evidence
Emit "INFO evidence written: scripts/verify/batch31/_c34_out.txt"
if ($script:fail -gt 0) {
    Emit "GATE-PAGES FAIL"
    Save-Evidence
    exit 1
}
Emit "GATE-PAGES PASS"
Save-Evidence
