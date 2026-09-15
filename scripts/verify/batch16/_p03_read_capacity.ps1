# P0-3a/b read-path capacity with methodology: warmup + steady tiers (20/50/100) + resource profile
# Prereq: swap-server started via .local\run-server-load.bat (512m heap, GC log, ratelimit off), swap-sim up.
# Output: _p03_out.txt (summary) + diag-archive/p03-capacity-<date>/ (raw jtl + jmeter logs, not in git)
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$jmx = Join-Path $PSScriptRoot "jmeter\swap-ops-read.jmx"
$out = Join-Path $PSScriptRoot "_p03_out.txt"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$diag = Join-Path $repo ("..\diag-archive\p03-capacity-" + $stamp)
New-Item -ItemType Directory -Path $diag -Force | Out-Null
$gcLog = Join-Path $repo ".local\gc.log"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
Set-Content -Path $out -Value "== P0-3 read-path capacity (methodology run) ==" -Encoding UTF8

function Snapshot {
    $s = @{ cache = ""; mysql = ""; redis = ""; gcCount = 0; gcSumMs = 0.0; gcMaxMs = 0.0 }
    $AH = @{ "X-Admin-Token" = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim()) }
    try {
        $c = (Invoke-RestMethod "$server/admin/cache/stats" -Headers $AH -TimeoutSec 5).data
        $s.cache = ($c.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ","
    } catch { $s.cache = "unavailable" }
    try {
        $raw = mysql -uroot -proot -N -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Queries','Innodb_row_lock_waits','Innodb_row_lock_time_avg');" 2>$null
        $s.mysql = ($raw | Where-Object { $_ }) -join ";"
    } catch { $s.mysql = "unavailable" }
    try {
        $info = redis-cli info stats 2>$null
        $cmds = ($info | Select-String "total_commands_processed:(\d+)").Matches.Groups[1].Value
        $ops = ($info | Select-String "instantaneous_ops_per_sec:(\d+)").Matches.Groups[1].Value
        $s.redis = "total_commands=$cmds;inst_ops_per_sec=$ops"
    } catch { $s.redis = "unavailable" }
    if (Test-Path $gcLog) {
        try {
            $lines = Select-String -Path $gcLog -Pattern "Pause" -ErrorAction SilentlyContinue
            $ms = @()
            foreach ($ln in $lines) {
                $m = [regex]::Match($ln.Line, "([0-9]+[.,][0-9]+)ms")
                if ($m.Success) { $ms += [double]($m.Groups[1].Value.Replace(",", ".")) }
            }
            $s.gcCount = $ms.Count
            if ($ms.Count -gt 0) { $s.gcSumMs = ($ms | Measure-Object -Sum).Sum; $s.gcMaxMs = ($ms | Measure-Object -Maximum).Maximum }
        } catch { }
    }
    return $s
}

function ParseJtl($path) {
    $rows = Import-Csv -Path $path
    $n = $rows.Count
    if ($n -eq 0) { return $null }
    $elapsed = @($rows | ForEach-Object { [int]$_.elapsed } | Sort-Object)
    $err = @($rows | Where-Object { $_.success -ne "true" }).Count
    $first = [long]$rows[0].timeStamp
    $last = [long]$rows[-1].timeStamp + $elapsed[$n - 1]
    $span = ($last - $first) / 1000.0
    function Pct($arr, $p) { $i = [Math]::Max(0, [Math]::Min($arr.Count - 1, [Math]::Floor($arr.Count * $p) - 1)); return $arr[$i] }
    $perLabel = @()
    foreach ($g in ($rows | Group-Object label)) {
        $e = @($g.Group | ForEach-Object { [int]$_.elapsed } | Sort-Object)
        $ge = @($g.Group | Where-Object { $_.success -ne "true" }).Count
        $perLabel += "{0}: n={1} err={2} avg={3}ms p99={4}ms" -f $g.Name, $g.Count, $ge, [int](($g.Group | Measure-Object -Property elapsed -Average).Average), (Pct $e 0.99)
    }
    return [pscustomobject]@{
        n = $n; err = $err; span = [Math]::Round($span, 1)
        throughput = [Math]::Round($n / $span, 1)
        avg = [int](($rows | Measure-Object -Property elapsed -Average).Average)
        p95 = (Pct $elapsed 0.95); p99 = (Pct $elapsed 0.99); max = $elapsed[$n - 1]
        perLabel = $perLabel -join " | "
    }
}

function RunTier($name, $threads, $duration, $ramp) {
    $jtl = Join-Path $diag ("$name.jtl")
    $jlog = Join-Path $diag ("$name-jmeter.log")
    $before = Snapshot
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $jmArgs = @("-n", "-t", $jmx, "-Jthreads=$threads", "-Jduration=$duration", "-Jramp=$ramp", "-l", $jtl, "-j", $jlog)
    & jmeter @jmArgs 2>&1 | Out-File (Join-Path $diag "$name-console.log") -Encoding UTF8
    $sw.Stop()
    $after = Snapshot
    $r = ParseJtl $jtl
    if ($null -eq $r) { Log "FAIL $name no samples"; $script:fail++; return }
    Log "$name threads=$threads duration=${duration}s wall=$([int]$sw.Elapsed.TotalSeconds)s samples=$($r.n) err=$($r.err) rate=$($r.throughput)/s avg=$($r.avg)ms p95=$($r.p95) p99=$($r.p99) max=$($r.max)"
    Log "$name labels: $($r.perLabel)"
    Log "$name resources: cache[$($before.cache)] -> [$($after.cache)]"
    Log "$name mysql: [$($before.mysql)] -> [$($after.mysql)]"
    Log "$name redis: [$($before.redis)] -> [$($after.redis)]"
    Log ("$name gc: pauses {0}->{1} sumMs {2:N1}->{3:N1} maxMs {4:N1}" -f $before.gcCount, $after.gcCount, $before.gcSumMs, $after.gcSumMs, $after.gcMaxMs)
    if ($r.err -gt 0) { Log "FAIL $name errors=$($r.err)"; $script:fail++ }
    return $r
}

# ---- prereq ----
try { $h = (Invoke-RestMethod "http://127.0.0.1:8400/api/actuator/health" -TimeoutSec 5).status; Log "server health=$h" } catch { Log "FAIL server unreachable"; exit 1 }
try { $h2 = (Invoke-RestMethod "http://127.0.0.1:8500/actuator/health" -TimeoutSec 5).status; Log "sim health=$h2" } catch { Log "WARN sim unreachable (read path does not need it)" }
$heap = (Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" | Where-Object { $_.CommandLine -like "*swap-server*" } | Select-Object -First 1).CommandLine
Log "server cmd: $($heap -replace '.*javaw|.*java','java')"
Log "methodology: JDK17 / jmeter 5.6.3 / same-host (load+server+mysql+redis) / ratelimit OFF / warmup 60s then steady tiers"

# ---- warmup (discard) ----
RunTier "warmup-20" 20 75 15 | Out-Null
Log "warmup done (discarded)"

# ---- steady tiers ----
RunTier "steady-20"  20 195 15 | Out-Null
RunTier "steady-50"  50 195 15 | Out-Null
RunTier "steady-100" 100 195 15 | Out-Null

Log "raw evidence: $diag"
if ($script:fail -eq 0) { Log "P0-3 READ PASS" ; exit 0 } else { Log "P0-3 READ FAIL checks=$($script:fail)" ; exit 1 }
