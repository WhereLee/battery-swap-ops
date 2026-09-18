# =============================================================================
# _c33_paging.ps1 - batch30 gate: real SQL paging on every paged admin endpoint
#
# Why this script exists:
#   MyBatis-Plus selectPage() silently degrades to a FULL TABLE SCAN with total=0
#   when PaginationInnerInterceptor is not registered. It does not throw, so every
#   "HTTP 200 + fields present" assertion passes while the platform ships all rows.
#   This was found by the first real browser run of swap-web (limit=5 returned 3854
#   alarm rows). Fix: config/MybatisPlusConfig.java. This script is the regression net.
#
# What it proves for each of the 13 paged endpoints (paths taken from openapi.json):
#   1. limit is honoured            -> rows returned <= requested limit
#   2. total is a real COUNT        -> total >= rows on the page
#   3. page 2 is a different page   -> first row of page1 != first row of page2
#   4. oversized limit is capped    -> limit=100000 yields at most MAX_LIMIT (200)
#
# Conventions: no secrets printed; reuses the bootstrap admin token from
# .local/admin-token.txt (same as _c32).
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

# Must match common/utils/PageParams.MAX_LIMIT and MybatisPlusConfig maxLimit.
$maxLimit = 200

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
    $r = Invoke-WebRequest "$base$path" -Headers $headers -UseBasicParsing -TimeoutSec 30
    return ($r.Content | ConvertFrom-Json)
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
function Row-Count($page) {
    if ($null -eq $page.data -or $null -eq $page.data.list) { return 0 }
    return @($page.data.list).Count
}
function First-Row([object]$page) {
    if ($null -eq $page.data -or $null -eq $page.data.list) { return "" }
    $rows = @($page.data.list)
    if ($rows.Count -eq 0) { return "" }
    return ($rows[0] | ConvertTo-Json -Compress -Depth 5)
}

Write-Output "=== C33 batch30: paging regression net ==="

$health = Get-Status "/actuator/health" @{}
Check "P00 platform health UP" ($health -eq 200)
if ($health -ne 200) { Write-Output "GATE-PAGING FAIL (platform down)"; exit 1 }

# All 13 GET endpoints declaring a `page` parameter, per document/api/openapi.json.
$endpoints = @(
    "/admin/station",
    "/admin/cabinet",
    "/admin/cell",
    "/admin/battery",
    "/admin/order",
    "/admin/user",
    "/admin/work-order",
    "/admin/transfer",
    "/admin/agent-action",
    "/admin/account/op-log",
    "/admin/view/alarm",
    "/admin/view/agent-action",
    "/admin/view/work-order"
)

$idx = 0
foreach ($ep in $endpoints) {
    $idx++
    $tag = "P{0:d2}" -f $idx

    $small = Get-Json ("{0}?page=1&limit=3" -f $ep) $H
    $n = Row-Count $small
    $total = 0
    if ($null -ne $small.data) { $total = [long]$small.data.total }

    Check "$tag $ep code=0" ($small.code -eq 0)
    Check "$tag $ep honours limit=3 (rows=$n)" ($n -le 3)
    Check "$tag $ep reports real total (total=$total rows=$n)" ($total -ge $n)

    # A total of 0 with rows present is the exact signature of the missing interceptor.
    Check "$tag $ep total is not the silent-zero signature" (-not ($n -gt 0 -and $total -eq 0))

    if ($total -ge 3) {
        $p1 = Get-Json ("{0}?page=1&limit=2" -f $ep) $H
        $p2 = Get-Json ("{0}?page=2&limit=2" -f $ep) $H
        $f1 = First-Row $p1
        $f2 = First-Row $p2
        Check "$tag $ep page=2 returns a different row than page=1" `
            (($f1.Length -gt 0) -and ($f2.Length -gt 0) -and ($f1 -ne $f2))
    } else {
        Skip "$tag $ep page=2 differs" "total=$total < 3, not enough rows to prove offset"
    }

    $big = Get-Json ("{0}?page=1&limit=100000" -f $ep) $H
    $nb = Row-Count $big
    Check "$tag $ep caps oversized limit at $maxLimit (rows=$nb)" ($nb -le $maxLimit)
}

# ---------- frontend contract gate (batch30) ----------
$webDir = Join-Path $repo "swap-web"
$permFile = Join-Path $webDir "src\api\permissions.ts"
Check "F01 swap-web permission code module present" (Test-Path -LiteralPath $permFile)
if (Test-Path -LiteralPath $permFile) {
    $permText = Get-Content -LiteralPath $permFile -Raw -Encoding UTF8
    $codes = [regex]::Matches($permText, '"(admin:[a-z\-]+:[a-z\-]+)"') | ForEach-Object { $_.Groups[1].Value }
    Check "F02 permissions.ts declares codes (found $($codes.Count))" ($codes.Count -ge 30)
    Check "F03 no duplicate permission code literals" `
        (($codes | Select-Object -Unique).Count -eq $codes.Count)
} else {
    Skip "F02 permissions.ts declares codes" "file missing"
    Skip "F03 no duplicate permission code literals" "file missing"
}

Write-Output ""
Write-Output "=== C33 summary: PASS=$($script:pass) FAIL=$($script:fail) SKIP=$($script:skip) ==="
if ($script:fail -gt 0) {
    Write-Output "GATE-PAGING FAIL"
    exit 1
}
Write-Output "GATE-PAGING PASS"
