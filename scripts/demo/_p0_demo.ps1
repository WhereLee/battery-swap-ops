# P0-4 demo: end-to-end happy path + admin reconciliation + OpenAPI export
# Prereq: swap-server :8400 (dev enabled), swap-sim :8500 up; dev secrets in .local/
# Output: _p0_demo_out.txt (evidence) + document/api/openapi.json (offline snapshot)
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$out = Join-Path $PSScriptRoot "_p0_demo_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== P0-4 demo: swap E2E + reconcile + OpenAPI ==" -Encoding UTF8

# ---- 0. health ----
try {
    $h1 = (Invoke-RestMethod "http://127.0.0.1:8400/api/actuator/health" -TimeoutSec 5).status
    Check "server health UP" ($h1 -eq "UP")
} catch { Check "server health UP" $false; Log "server unreachable: $($_.Exception.Message)"; exit 1 }
try {
    $h2 = (Invoke-RestMethod "http://127.0.0.1:8500/actuator/health" -TimeoutSec 5).status
    Check "sim health UP" ($h2 -eq "UP")
} catch { Check "sim health UP" $false; Log "sim unreachable: $($_.Exception.Message)"; exit 1 }

# ---- 1. reset sim (bootId rotation + seed state) then server dev data ----
$rs = (Invoke-RestMethod -Method Post "$sim/sim/reset" -TimeoutSec 30)
Log "sim-reset cabinets=$($rs.cabinets) bootId=$($rs.bootId)"
Check "sim reset ok (>=10 cabinets)" ($rs.cabinets -ge 10)
$r = (Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60).data
Log "dev-reset cabinets=$($r.cabinets) cancelled=$($r.ordersCancelled) occupied=$($r.cellsOccupied)"
Check "dev-reset full cells seeded (multiple of 6, >=60)" (($r.cellsOccupied % 6) -eq 0 -and $r.cellsOccupied -ge 60)

# ---- 1b. reconcile baseline (historical dev data may carry diffs; assert zero NEW diffs later) ----
$adminToken = (Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim()
$AH = @{ "X-Admin-Token" = $adminToken }
$base = (Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data
$script:baseTotal = $base.totalViolations
$baseDirty = ($base.checks | Where-Object { $_.violations -gt 0 } | ForEach-Object { "$($_.name)=$($_.violations)" }) -join ","
Log "reconcile baseline total=$($script:baseTotal) dirty=[$baseDirty]"

# ---- 2. rider login + read views ----
$phone = "13800000001"
$login = Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5
$token = $login.data.token
$H = @{ "X-User-Token" = $token }
$stations = (Invoke-RestMethod "$server/user/stations" -Headers $H -TimeoutSec 5).data
Log "login ok phone=$phone stations=$($stations.Count)"
Check "stations listed" ($stations.Count -gt 0)
$wallet0 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data
Log "wallet-before balance=$($wallet0.balanceFen) deposit=$($wallet0.depositFen) planRemain=$($wallet0.activePlan.remainingTimes)"

function Wait-Status($orderNo, $expect, $timeoutSec = 15) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-RestMethod "$server/user/order/$orderNo" -Headers $H -TimeoutSec 5
        if ($r.data.status -eq $expect) { return $r.data }
        Start-Sleep -Milliseconds 400
    }
    return $null
}
function New-Order($type) {
    $headers = @{ "X-User-Token" = $token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
    return Invoke-RestMethod -Method Post "$server/user/order" -Headers $headers -ContentType "application/json" -Body (@{ type = $type } | ConvertTo-Json) -TimeoutSec 10
}

# ---- 3. TAKE (borrow: deposit charged, plan times -1) ----
$take = New-Order "TAKE"
$takeNo = $take.data.orderNo
$takeOpened = Wait-Status $takeNo 2
Check "TAKE order opened" ($takeOpened -ne $null)
Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($takeOpened.cabinetNo)&cellNo=$($takeOpened.cellNo)" -TimeoutSec 5 | Out-Null
$takeDone = Wait-Status $takeNo 5
Check "TAKE completed" ($takeDone -ne $null)
Check "TAKE payType=PLAN" ($takeDone.payType -eq "PLAN")
$wallet1 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data
Check "TAKE deposit charged 9900" ($wallet1.depositFen -eq 9900)
$held = $takeDone.takeBatteryNo
Log "TAKE done orderNo=$takeNo cabinet=$($takeDone.cabinetNo) battery=$held"

# ---- 4. SWAP (take full, return held) ----
$swap = New-Order "SWAP"
$swapNo = $swap.data.orderNo
$swapOpened = Wait-Status $swapNo 2
Check "SWAP order opened" ($swapOpened -ne $null)
Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)" -TimeoutSec 5 | Out-Null
Invoke-RestMethod -Method Post "$sim/sim/battery/in?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)&batteryNo=$held&soc=15" -TimeoutSec 5 | Out-Null
$swapDone = Wait-Status $swapNo 5
Check "SWAP completed" ($swapDone -ne $null)
Check "holder transferred" ($swapDone.returnBatteryNo -eq $held)
$newHeld = $swapDone.takeBatteryNo
Log "SWAP done orderNo=$swapNo newHolder=$newHeld"

# ---- 5. RETURN (return battery: fee 0, deposit refunded) ----
$ret = New-Order "RETURN"
$retNo = $ret.data.orderNo
$retOpened = Wait-Status $retNo 2
Check "RETURN order opened" ($retOpened -ne $null)
Invoke-RestMethod -Method Post "$sim/sim/battery/in?cabinetNo=$($retOpened.cabinetNo)&cellNo=$($retOpened.cellNo)&batteryNo=$newHeld&soc=10" -TimeoutSec 5 | Out-Null
$retDone = Wait-Status $retNo 5
Check "RETURN completed fee=0" ($retDone -ne $null -and $retDone.feeFen -eq 0)
$wallet2 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data
Check "deposit refunded" ($wallet2.depositFen -eq 0)
Log "RETURN done orderNo=$retNo returnedCell=$($retOpened.cellNo) battery=$newHeld soc=10"

# ---- 6. admin break-glass: re-run reconciliation, expect zero NEW violations ----
$report = (Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data
$delta = $report.totalViolations - $script:baseTotal
Log "reconcile after total=$($report.totalViolations) baseline=$($script:baseTotal) delta=$delta checks=$($report.checks.Count)"
Check "reconcile no new violations (delta<=0)" ($delta -le 0)
Check "demo orders reconciled clean" (($report.checks | Where-Object { $_.name -eq 'completed-has-payment' }).violations -eq 0)
$last = (Invoke-RestMethod "$server/admin/reconcile/last" -Headers $AH -TimeoutSec 10).data
Check "reconcile report persisted" ($last -ne $null)

# ---- 7. OpenAPI page + offline snapshot export ----
try {
    $ui = Invoke-WebRequest "$server/swagger-ui.html" -TimeoutSec 10 -UseBasicParsing
    Check "swagger-ui reachable (HTTP $($ui.StatusCode))" ($ui.StatusCode -eq 200)
} catch { Check "swagger-ui reachable" $false }
$spec = Invoke-RestMethod "$server/v3/api-docs" -TimeoutSec 15
$apiDir = Join-Path $repo "document\api"
New-Item -ItemType Directory -Path $apiDir -Force | Out-Null
$spec | ConvertTo-Json -Depth 100 | Set-Content -Path (Join-Path $apiDir "openapi.json") -Encoding UTF8
$paths = ($spec.paths | Get-Member -MemberType NoteProperty).Count
Log "openapi.json exported paths=$paths"
Check "openapi has >50 paths" ($paths -gt 50)

if ($script:fail -eq 0) { Log "P0-DEMO PASS" ; exit 0 } else { Log "P0-DEMO FAIL checks=$($script:fail)" ; exit 1 }
