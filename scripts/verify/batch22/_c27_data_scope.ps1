# batch22 P1-8: admin data scope (DataFilter) -- station-scoped operator sees only own domain; cross-domain 403.
# Prereq: server :8400 (load profile) up; admin token at .local/admin-token.txt; db/16-data-scope.sql applied.
# Demo data: ST-002 owns cabinet SWAP-C-009; ST-001 is out of scope for the scoped account.
# NOTE: English-only (PS 5.1 + BOM-less UTF-8: CJK literals corrupt parsing). Brace vars before ? in URLs.
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_c27_out.txt"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$AH = @{ "X-Admin-Token" = $adminToken }
$SCOPE_USER = "ops-st002"
$SCOPE_PASS = "St002#12345"
$script:fail = 0

function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
function Http($method, $url, $headers, $body) {
    try {
        $params = @{ Method = $method; Uri = $url; UseBasicParsing = $true; TimeoutSec = 20 }
        if ($headers) { $params.Headers = $headers }
        if ($body) { $params.ContentType = "application/json"; $params.Body = $body }
        $resp = Invoke-WebRequest @params
        $j = $null; try { $j = $resp.Content | ConvertFrom-Json } catch { }
        return @{ status = [int]$resp.StatusCode; json = $j; raw = $resp.Content }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        $raw = ""; try { $raw = $_.ErrorDetails.Message } catch { }
        $j = $null; if ($raw) { try { $j = $raw | ConvertFrom-Json } catch { } }
        return @{ status = $status; json = $j; raw = $raw }
    }
}
# NOTE (batch21 pitfall): PS functions unroll returns (0->$null / 1->scalar / N->array).
# Always wrap call sites with @(...) -- never rely on a helper to return an array.

Set-Content -Path $out -Value "== batch22: P1-8 data scope (station-scoped admin) ==" -Encoding UTF8

# 0) preflight: server healthy + bootstrap sees full demo set
$h = Http "GET" "$server/actuator/health" $null $null
Check "0 server healthy" ($h.status -eq 200)
$allSt = Http "GET" "$server/admin/station?limit=500" $AH $null
$stList = @($allSt.json.data.list)
$st002 = @($stList | Where-Object { $_.stationNo -eq "ST-002" })[0]
$st001 = @($stList | Where-Object { $_.stationNo -eq "ST-001" })[0]
Check "1 bootstrap station list has ST-001/ST-002" ($null -ne $st001 -and $null -ne $st002)
if ($null -eq $st001 -or $null -eq $st002) { Log "FATAL missing demo stations"; exit 1 }
$allCabs = Http "GET" "$server/admin/cabinet?limit=500" $AH $null
$cabList = @($allCabs.json.data.list)
$c009 = @($cabList | Where-Object { $_.cabinetNo -eq "SWAP-C-009" })[0]
Check "2 bootstrap cabinet list has SWAP-C-009" ($null -ne $c009 -and $c009.stationId -eq $st002.id)
Check "3 bootstrap sees more than scoped domain (stations/cabinets)" ($stList.Count -ge 3 -and $cabList.Count -ge 10)

# 4) ensure scoped account (idempotent): ops-st002 / STATION / ST-002, enabled
$mkBody = @{ username = $SCOPE_USER; password = $SCOPE_PASS; realName = "st002-scoped"; role = "OPS";
             dataScope = "STATION"; scopeStationNos = "ST-002" } | ConvertTo-Json
$mk = Http "POST" "$server/admin/account" $AH $mkBody
$created = ($mk.status -eq 200)
if (-not $created) {
    Log "account create returned status=$($mk.status) msg=$($mk.json.msg) (assume exists -> reuse)"
    $acc = Http "GET" "$server/admin/account" $AH $null
    $mine = @(@($acc.json.data) | Where-Object { $_.username -eq $SCOPE_USER })[0]
    if ($mine -and $mine.status -ne 1) {
        $en = Http "POST" "$server/admin/account/$($mine.id)/status?status=1" $AH $null
        Log "re-enabled scoped account status=$($en.status)"
    }
}
Check "4 scoped account ready (created or existing)" ($created -or $null -ne $mine)

# 5) creation validation: scope field must be explicit and valid
$bad1 = Http "POST" "$server/admin/account" $AH (@{ username = "ops-bad1"; password = $SCOPE_PASS; role = "OPS"; dataScope = "STATION" } | ConvertTo-Json)
Check "5 STATION without stationNos -> 400" ($bad1.status -eq 400)
$bad2 = Http "POST" "$server/admin/account" $AH (@{ username = "ops-bad2"; password = $SCOPE_PASS; role = "OPS"; dataScope = "STATION"; scopeStationNos = "ST-999" } | ConvertTo-Json)
Check "6 STATION with unknown station -> 400" ($bad2.status -eq 400)
$bad3 = Http "POST" "$server/admin/account" $AH (@{ username = "ops-bad3"; password = $SCOPE_PASS; role = "OPS"; dataScope = "REGION" } | ConvertTo-Json)
Check "7 invalid dataScope -> 400" ($bad3.status -eq 400)

# 6) scoped login
$login = Http "POST" "$server/admin/auth/login" $AH (@{ username = $SCOPE_USER; password = $SCOPE_PASS } | ConvertTo-Json)
$tok = $login.json.data.token
Check "8 scoped login ok (32-hex session token)" ($login.status -eq 200 -and $tok -and $tok.Length -eq 32)
if (-not $tok) { Log "FATAL scoped login failed"; exit 1 }
$SH = @{ "X-Admin-Token" = $tok }

# 7) read scope: lists only own domain
$mySt = Http "GET" "$server/admin/station?limit=500" $SH $null
$myStList = @($mySt.json.data.list)
Check "9 scoped station list == 1 (ST-002)" ($myStList.Count -eq 1 -and $myStList[0].stationNo -eq "ST-002")

$myCab = Http "GET" "$server/admin/cabinet?limit=500" $SH $null
$myCabList = @($myCab.json.data.list)
Check "10 scoped cabinet list == 1 (SWAP-C-009)" ($myCabList.Count -eq 1 -and $myCabList[0].cabinetNo -eq "SWAP-C-009")

$myCell = Http "GET" "$server/admin/cell?limit=500" $SH $null
$myCellList = @($myCell.json.data.list)
$c009Id = $myCabList[0].id
$cellAllIn = $true; foreach ($c in $myCellList) { if ($c.cabinetId -ne $c009Id) { $cellAllIn = $false } }
Check "11 scoped cell list all in C-009 (count == cellCount)" ($myCellList.Count -eq $myCabList[0].cellCount -and $cellAllIn -and $myCellList.Count -gt 0)
$myCellIds = @($myCellList | ForEach-Object { $_.id })

$myBat = Http "GET" "$server/admin/battery?limit=500" $SH $null
$myBatList = @($myBat.json.data.list)
$batAllIn = $true; foreach ($b in $myBatList) { if ($myCellIds -notcontains $b.cellId) { $batAllIn = $false } }
Check "12 scoped battery list all in scoped cells (non-empty)" ($myBatList.Count -gt 0 -and $batAllIn)

$myOrd = Http "GET" "$server/admin/order?limit=500" $SH $null
$myOrdList = @($myOrd.json.data.list)
$ordAllIn = $true; foreach ($o in $myOrdList) { if ($o.stationId -ne $st002.id) { $ordAllIn = $false } }
$allOrd = Http "GET" "$server/admin/order?limit=500" $AH $null
Check "13 scoped order list all ST-002 (0 < count < global)" ($myOrdList.Count -gt 0 -and $ordAllIn -and $myOrdList.Count -lt @($allOrd.json.data.list).Count)

$myDb = Http "GET" "$server/admin/dashboard/overview" $SH $null
Check "14 scoped dashboard totalStations == 1" ($myDb.status -eq 200 -and $myDb.json.data.totalStations -eq 1)

# 8) resource-level 403: details outside the domain
$stIn = Http "GET" "$server/admin/cabinet/SWAP-C-009/state" $SH $null
$stOut = Http "GET" "$server/admin/cabinet/SWAP-C-001/state" $SH $null
Check "15 cabinetState in-scope 200 / out-of-scope 403" ($stIn.status -eq 200 -and $stOut.status -eq 403)

$myBatNo = $myBatList[0].batteryNo
$hIn = Http "GET" "$server/admin/battery/$myBatNo/health" $SH $null
$outBatList = @((Http "GET" "$server/admin/battery?limit=500" $AH $null).json.data.list)
$outBatNo = @($outBatList | Where-Object { @($myBatList | ForEach-Object { $_.batteryNo }) -notcontains $_.batteryNo })[0].batteryNo
$hOut = Http "GET" "$server/admin/battery/$outBatNo/health" $SH $null
Check "16 batteryHealth in-scope 200 / out-of-scope 403" ($hIn.status -eq 200 -and $hOut.status -eq 403)

$ordInNo = $myOrdList[0].orderNo
$ordOutNo = @(@($allOrd.json.data.list) | Where-Object { $_.stationId -ne $st002.id })[0].orderNo
$dIn = Http "GET" "$server/admin/order/$ordInNo" $SH $null
$dOut = Http "GET" "$server/admin/order/$ordOutNo" $SH $null
Check "17 order detail in-scope 200 / out-of-scope 403" ($dIn.status -eq 200 -and $dOut.status -eq 403)

# 9) resource-level 403: writes outside the domain; in-scope write allowed
$w1 = Http "POST" "$server/admin/cabinet/SWAP-C-001/status?status=4" $SH $null
Check "18 cross-domain cabinet status write -> 403" ($w1.status -eq 403)
$w2 = Http "POST" "$server/admin/station/$($st001.id)/status?status=2" $SH $null
Check "19 cross-domain station status write -> 403" ($w2.status -eq 403)
$w3 = Http "POST" "$server/admin/station" $SH (@{ stationNo = "ST-SCOPE-TEST"; name = "scope-test" } | ConvertTo-Json)
Check "20 create station -> 403 (no station home for scoped identity)" ($w3.status -eq 403)
$w4 = Http "POST" "$server/admin/cabinet" $SH (@{ cabinetNo = "SWAP-C-9X-TEST"; stationId = $st001.id; cellCount = 2; secret = "aabbccddeeff00112233445566778899" } | ConvertTo-Json)
Check "21 create cabinet in other station -> 403" ($w4.status -eq 403)
$w5 = Http "POST" "$server/admin/battery" $SH (@{ batteryNo = "BAT-SCOPE-TEST"; soc = 100 } | ConvertTo-Json)
Check "22 create battery without cell (no home) -> 403" ($w5.status -eq 403)

$r1 = Http "POST" "$server/admin/cabinet/SWAP-C-009/status?status=4" $SH $null
$r2 = Http "POST" "$server/admin/cabinet/SWAP-C-009/status?status=1" $SH $null
Check "23 in-scope cabinet status round-trip (4 then 1) -> 200/200" ($r1.status -eq 200 -and $r2.status -eq 200)

# 10) side-effect guard: rejected writes left no trace
$afterSt = @((Http "GET" "$server/admin/station?limit=500" $AH $null).json.data.list)
$ghost = @($afterSt | Where-Object { $_.stationNo -eq "ST-SCOPE-TEST" })
$ghostCab = @(@((Http "GET" "$server/admin/cabinet?limit=500" $AH $null).json.data.list) | Where-Object { $_.cabinetNo -eq "SWAP-C-9X-TEST" })
Check "24 rejected writes left no rows (station/cabinet)" ($ghost.Count -eq 0 -and $ghostCab.Count -eq 0)

Log "== summary: fail=$($script:fail) =="
if ($script:fail -gt 0) { exit 1 }
exit 0
