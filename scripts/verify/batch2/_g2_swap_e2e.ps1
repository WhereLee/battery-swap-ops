# S2 gate script: swap E2E (TAKE -> SWAP -> RETURN) with billing/plan assertions
# Prereq: swap-server :8400 (swap.dev.enabled=true), swap-sim :8500 (dev-enabled=true), seeded dev users
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$out = Join-Path $PSScriptRoot "_g2_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S2 gate: swap E2E ==" -Encoding UTF8

$phone = "13800000001"
$login = Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5
$token = $login.data.token
$H = @{ "X-User-Token" = $token }
Log "login ok phone=$phone"

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

$wallet0 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data
Log "wallet-before balance=$($wallet0.balanceFen) deposit=$($wallet0.depositFen) planRemain=$($wallet0.activePlan.remainingTimes)"

# ---- TAKE (first borrow: deposit charged, plan times -1) ----
$take = New-Order "TAKE"
$takeNo = $take.data.orderNo
$takeOpened = Wait-Status $takeNo 2
Check "TAKE order opened" ($takeOpened -ne $null)
Log "TAKE orderNo=$takeNo cabinet=$($takeOpened.cabinetNo) cell=$($takeOpened.cellNo) battery=$($takeOpened.takeBatteryNo)"
Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($takeOpened.cabinetNo)&cellNo=$($takeOpened.cellNo)" -TimeoutSec 5 | Out-Null
$takeDone = Wait-Status $takeNo 5
Check "TAKE completed" ($takeDone -ne $null)
$held = $takeDone.takeBatteryNo
Check "TAKE payType=PLAN" ($takeDone.payType -eq "PLAN")
$wallet1 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data
Check "TAKE deposit charged 9900" ($wallet1.depositFen -eq 9900)
Check "TAKE plan remaining 4" ($wallet1.activePlan.remainingTimes -eq 4)

# ---- SWAP (take charged battery, return held one) ----
$swap = New-Order "SWAP"
$swapNo = $swap.data.orderNo
$swapOpened = Wait-Status $swapNo 2
Check "SWAP order opened" ($swapOpened -ne $null)
Log "SWAP orderNo=$swapNo cabinet=$($swapOpened.cabinetNo) cell=$($swapOpened.cellNo) takeBattery=$($swapOpened.takeBatteryNo)"
Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)" -TimeoutSec 5 | Out-Null
Invoke-RestMethod -Method Post "$sim/sim/battery/in?cabinetNo=$($swapOpened.cabinetNo)&cellNo=$($swapOpened.cellNo)&batteryNo=$held&soc=15" -TimeoutSec 5 | Out-Null
$swapDone = Wait-Status $swapNo 5
Check "SWAP completed" ($swapDone -ne $null)
Check "SWAP returned held battery" ($swapDone.returnBatteryNo -eq $held)
$newHeld = $swapDone.takeBatteryNo
Check "SWAP holder transferred (new battery)" ($newHeld -ne $held)
$wallet2 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data
Check "SWAP plan remaining 3" ($wallet2.activePlan.remainingTimes -eq 3)

# ---- RETURN (return battery, deposit refunded, no service fee) ----
$ret = New-Order "RETURN"
$retNo = $ret.data.orderNo
$retOpened = Wait-Status $retNo 2
Check "RETURN order opened" ($retOpened -ne $null)
Log "RETURN orderNo=$retNo cabinet=$($retOpened.cabinetNo) cell=$($retOpened.cellNo)"
Invoke-RestMethod -Method Post "$sim/sim/battery/in?cabinetNo=$($retOpened.cabinetNo)&cellNo=$($retOpened.cellNo)&batteryNo=$newHeld&soc=10" -TimeoutSec 5 | Out-Null
$retDone = Wait-Status $retNo 5
Check "RETURN completed" ($retDone -ne $null)
Check "RETURN fee=0" ($retDone.feeFen -eq 0)
$wallet3 = (Invoke-RestMethod "$server/user/wallet" -Headers $H -TimeoutSec 5).data
Check "RETURN deposit refunded" ($wallet3.depositFen -eq 0)
Check "RETURN plan remaining still 3" ($wallet3.activePlan.remainingTimes -eq 3)
Log "wallet-after balance=$($wallet3.balanceFen) deposit=$($wallet3.depositFen) planRemain=$($wallet3.activePlan.remainingTimes)"

if ($script:fail -eq 0) { Log "GATE-2 PASS" ; exit 0 } else { Log "GATE-2 FAIL checks=$($script:fail)" ; exit 1 }
