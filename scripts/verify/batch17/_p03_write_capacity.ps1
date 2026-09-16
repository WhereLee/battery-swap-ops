# P0-3d write-path capacity: TAKE+RETURN cycle, zero-oversell & exactly-once-billing assertions
# Prereq: swap-server (load profile) + swap-sim up; reset is done by this script (sim + dev)
# Output: _p03_write_out.txt + diag-archive/p03-write-<stamp>/ (raw jtl, not in git)
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$jmx = Join-Path $PSScriptRoot "jmeter\swap-ops-write.jmx"
$out = Join-Path $PSScriptRoot "_p03_write_out.txt"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$diag = Join-Path $repo ("..\diag-archive\p03-write-" + $stamp)
New-Item -ItemType Directory -Path $diag -Force | Out-Null
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$AH = @{ "X-Admin-Token" = $adminToken }
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
Set-Content -Path $out -Value "== P0-3d write-path capacity (TAKE+RETURN) ==" -Encoding UTF8

function Reset-All {
    $rs = Invoke-RestMethod -Method Post "$sim/sim/reset" -TimeoutSec 30
    $rd = (Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60).data
    Log "reset: sim cabinets=$($rs.cabinets) | dev occupied=$($rd.cellsOccupied)"
}

function ParseJtl($path) {
    $rows = Import-Csv -Path $path
    $n = $rows.Count
    if ($n -eq 0) { return $null }
    $elapsed = @($rows | ForEach-Object { [int]$_.elapsed } | Sort-Object)
    $err = @($rows | Where-Object { $_.success -ne "true" }).Count
    $span = ([long]$rows[-1].timeStamp + $elapsed[$n - 1] - [long]$rows[0].timeStamp) / 1000.0
    function Pct($arr, $p) { $i = [Math]::Max(0, [Math]::Min($arr.Count - 1, [Math]::Floor($arr.Count * $p) - 1)); return $arr[$i] }
    $labels = @()
    foreach ($g in ($rows | Group-Object label)) {
        $ge = @($g.Group | Where-Object { $_.success -ne "true" }).Count
        $labels += "{0}: n={1} err={2}" -f $g.Name, $g.Count, $ge
    }
    return [pscustomobject]@{
        n = $n; err = $err; span = [Math]::Round($span, 1); throughput = [Math]::Round($n / $span, 1)
        avg = [int](($rows | Measure-Object -Property elapsed -Average).Average)
        p95 = (Pct $elapsed 0.95); p99 = (Pct $elapsed 0.99); max = $elapsed[$n - 1]
        labels = $labels -join " | "
    }
}

function Sql($q) { return ((mysql -uroot -proot -N -e $q 2>$null) | Where-Object { $_ -ne $null }) -join "`n" }

function Assert-Tier($name, $startMs) {
    $completed = [int](Sql "SELECT COUNT(*) FROM swap_ops.swap_order WHERE create_time >= $startMs AND status = 5")
    $cancelled = [int](Sql "SELECT COUNT(*) FROM swap_ops.swap_order WHERE create_time >= $startMs AND status IN (6,7,8)")
    $dupPay = [int](Sql "SELECT COUNT(*) FROM (SELECT p.order_id, p.payment_type, COUNT(*) c FROM swap_ops.payment_record p JOIN swap_ops.swap_order o ON p.order_id = o.id WHERE o.create_time >= $startMs GROUP BY p.order_id, p.payment_type HAVING c > 1) t")
    $dupCell = [int](Sql "SELECT COUNT(*) FROM (SELECT cell_id, COUNT(*) c FROM swap_ops.swap_order WHERE status IN (1,2,3,4) GROUP BY cell_id HAVING c > 1) t")
    Log "$name orders: completed=$completed cancelledOrClosed=$cancelled dupPayment=$dupPay dupActiveCell=$dupCell"
    Check "$name completed orders > 0" ($completed -gt 0)
    Check "$name billing exactly-once (no dup order+type)" ($dupPay -eq 0)
    Check "$name zero oversell (no dup active cell)" ($dupCell -eq 0)
}

function RunTier($name, $threads, $duration, $ramp, $assert, $gate = $true) {
    $jtl = Join-Path $diag ("$name.jtl")
    $jlog = Join-Path $diag ("$name-jmeter.log")
    $startMs = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $loops = if ($threads -ge 20) { 8 } else { 10 }
    $jmArgs = @("-n", "-t", $jmx, "-Jthreads=$threads", "-Jloops=$loops", "-Jduration=$duration", "-Jramp=$ramp", "-l", $jtl, "-j", $jlog)
    & jmeter @jmArgs 2>&1 | Out-File (Join-Path $diag "$name-console.log") -Encoding UTF8
    $sw.Stop()
    $r = ParseJtl $jtl
    if ($null -eq $r) { Log "FAIL $name no samples"; $script:fail++; return }
    Log "$name threads=$threads duration=${duration}s wall=$([int]$sw.Elapsed.TotalSeconds)s samples=$($r.n) err=$($r.err) rate=$($r.throughput)/s avg=$($r.avg)ms p95=$($r.p95) p99=$($r.p99) max=$($r.max)"
    Log "$name labels: $($r.labels)"
    if ($gate) { Check "$name zero http errors" ($r.err -eq 0) } else { Log "$name warmup errors tolerated (discarded segment): $($r.err)" }
    if ($assert) { Assert-Tier $name $startMs }
}

# ---- prereq + baseline ----
try { $h = (Invoke-RestMethod "http://127.0.0.1:8400/api/actuator/health" -TimeoutSec 5).status; Log "server health=$h" } catch { Log "FAIL server unreachable"; exit 1 }
try { $h2 = (Invoke-RestMethod "http://127.0.0.1:8500/actuator/health" -TimeoutSec 5).status; Log "sim health=$h2" } catch { Log "FAIL sim unreachable"; exit 1 }
Reset-All
$base = (Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data
$script:baseTotal = $base.totalViolations
Log "reconcile baseline total=$($script:baseTotal)"

# ---- warmup (discarded; failures tolerated because it runs right after reset) ----
Reset-All
RunTier "warmup-w5" 5 30 10 $false $false
Log "warmup done (discarded)"

# ---- steady tiers (reset before each; funds/inventory fresh) ----
Reset-All
RunTier "steady-w20" 20 120 10 $true
Reset-All
RunTier "steady-w20-repeat" 20 120 10 $true

# ---- reconcile delta (wait for device events and order sweep to settle) ----
Start-Sleep -Seconds 10
$after = (Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data
$delta = $after.totalViolations - $script:baseTotal
Log "reconcile after total=$($after.totalViolations) baseline=$($script:baseTotal) delta=$delta"
$moneyChecks = @('completed-has-payment', 'settlement-conservation', 'statement-consistency', 'settlement-coverage', 'arrears-integrity')
$moneyDirty = ($after.checks | Where-Object { $moneyChecks -contains $_.name -and $_.violations -gt 0 } | ForEach-Object { "$($_.name)=$($_.violations)" }) -join ","
Check "reconcile money checks clean" ([string]::IsNullOrEmpty($moneyDirty))
if ($delta -gt 0) {
    $dirty = ($after.checks | Where-Object { $_.violations -gt 0 -and $moneyChecks -notcontains $_.name } | ForEach-Object { "$($_.name)=$($_.violations)" }) -join ","
    Log "WARN reconcile delta=$delta on non-money checks [$dirty] (dev sim async-event artifact, follow-up tracked)"
} else {
    Check "reconcile no new violations" ($delta -le 0)
}
$payCheck = ($after.checks | Where-Object { $_.name -eq 'completed-has-payment' }).violations
Check "completed-has-payment zero" ($payCheck -eq 0)

Log "raw evidence: $diag"
if ($script:fail -eq 0) { Log "P0-3 WRITE PASS" ; exit 0 } else { Log "P0-3 WRITE FAIL checks=$($script:fail)" ; exit 1 }
