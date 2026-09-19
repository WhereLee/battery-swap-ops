# =============================================================================
# _c39_index_audit.ps1 - batch40 gate: index/access-path audit with server-side evidence
#
# Why this script exists:
#   document/knowledge/选型卡与表设计依据.md used to say "本项目没做索引全量审计" - every
#   index was justified by pointing at a query, but nobody had checked the OTHER direction:
#   do the queries the platform actually runs have an access path at all? A dropped or
#   never-created index does not fail any test, does not show up in EXPLAIN unless you look,
#   and produces correct results - just slowly. That is exactly the class of defect that
#   survives unit tests, integration tests, contract gates and browser gates.
#
# What it proves:
#   1. AUDIT: replay MySQL's own per-statement statistics (performance_schema
#      .events_statements_summary_by_digest) for schema swap_ops and report every statement
#      the SERVER recorded as executed without an index, joined with table size, so a full
#      scan of a 10-row table (fine) is not confused with a full scan of a 5,000-row table
#      (a missing access path). Measured facts, not code reading.
#   2. PLAN: EXPLAIN the hot paths and assert the optimizer can use an index where the table
#      is big enough for it to matter.
#   3. CAUSAL DELTA: read the digest counters, drive real traffic through the running
#      platform, read them again, and assert the watched statements grew in COUNT_STAR while
#      SUM_NO_INDEX_USED did NOT grow and rows-examined-per-execution stayed near 1. This is
#      the part that proves a fix rather than describing one: the pre-fix measurement for
#      payment_record WHERE order_id = ? was ~8,090 rows examined per execution.
#   4. UNBOUNDED READS: no SELECT with neither WHERE nor LIMIT is currently returning a large
#      result set. A class-level guard, added because one such statement could not be
#      attributed to any caller - the guard makes attribution unnecessary next time.
#
# Non-mutating by design: it only reads statistics and calls read-only endpoints. It does not
# truncate performance_schema, does not change server variables, and does not write rows.
#
# Inputs (environment): none required; token from .local/admin-token.txt, platform on :8400.
# Output: PASS/FAIL lines + summary on stdout, and its own _c39_out.txt evidence file.
# =============================================================================

$ErrorActionPreference = "Continue"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $scriptDir "..\..\..")
$local = Join-Path $repo ".local"
$base = "http://127.0.0.1:8400/api"

$script:evidence = New-Object System.Collections.Generic.List[string]
$script:outFile = Join-Path $scriptDir "_c39_out.txt"
function Emit([string]$line) {
    $script:evidence.Add($line)
    Write-Output $line
}
function Save-Evidence {
    [System.IO.File]::WriteAllLines($script:outFile, $script:evidence, (New-Object System.Text.UTF8Encoding($false)))
}

$script:pass = 0
$script:fail = 0
function Check([string]$name, [bool]$cond, [string]$detail = "") {
    if ($cond) { $script:pass++; Emit ("PASS  " + $name + $(if ($detail) { " ($detail)" } else { "" })) }
    else { $script:fail++; Emit ("FAIL  " + $name + $(if ($detail) { " ($detail)" } else { "" })) }
}

$tokenFile = Join-Path $local "admin-token.txt"
if (-not (Test-Path -LiteralPath $tokenFile)) { Emit "FATAL admin token file missing"; Save-Evidence; exit 1 }
$headers = @{ "X-Admin-Token" = (Get-Content -LiteralPath $tokenFile -Raw).Trim() }

# -- SQL helper: read-only query, TSV out, credentials never printed -------------
function Sql([string]$query) {
    $out = & (Join-Path $local "db-query.ps1") -Query $query 2>&1
    return ($out | ForEach-Object { "$_" })
}
# rows of a TSV result as objects. The leading comma matters: a single-row result would
# otherwise be unrolled by PowerShell into a bare hashtable, so `$rows[0]` would be $null and
# every single-row assertion would silently read nothing (the repo has a pitfall file about
# exactly this trap - ps-function-return-unroll-count.md).
function SqlRows([string]$query) {
    $lines = Sql $query
    if ($lines.Count -lt 2) { return , @() }
    $header = $lines[0] -split "`t"
    $result = @()
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if ([string]::IsNullOrWhiteSpace($lines[$i])) { continue }
        $cells = $lines[$i] -split "`t"
        $row = @{}
        for ($c = 0; $c -lt $header.Count; $c++) {
            $row[$header[$c]] = $(if ($c -lt $cells.Count) { $cells[$c] } else { "" })
        }
        $result += , $row
    }
    return , $result
}

Emit "=== C39 batch40: index/access-path audit (server-side digest evidence) ==="

$health = 0
try { $health = (Invoke-WebRequest "$base/admin/view/dashboard" -Headers $headers -UseBasicParsing -TimeoutSec 10).StatusCode } catch { $health = 0 }
if ($health -ne 200) { Emit "GATE-INDEX FAIL (platform not answering on :8400)"; Save-Evidence; exit 1 }

# ---------------------------------------------------------------------------
# 1. AUDIT - what the SERVER says ran without an index
# ---------------------------------------------------------------------------
Emit ""
Emit "--- audit: statements MySQL recorded as executed without an index ---"

# Table size is resolved in PowerShell rather than joined in SQL: DIGEST_TEXT renders table
# names backticked, so SUBSTRING_INDEX gymnastics in SQL are fragile and silently produce a
# NULL join - which would classify every scan as "small table, benign".
$raw = SqlRows @"
SELECT LEFT(DIGEST_TEXT, 200) AS shape, COUNT_STAR AS execs, SUM_NO_INDEX_USED AS no_idx,
       ROUND(SUM_ROWS_EXAMINED / NULLIF(COUNT_STAR, 0), 1) AS rows_per_exec,
       SUM_ROWS_EXAMINED AS examined, SUM_ROWS_SENT AS sent
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'swap_ops' AND SUM_NO_INDEX_USED > 0
ORDER BY SUM_ROWS_EXAMINED DESC LIMIT 15
"@
$sizes = @{}
foreach ($r in (SqlRows "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='swap_ops'")) {
    $sizes[$r["TABLE_NAME"]] = [int]$r["TABLE_ROWS"]
}

$REAL_SCAN_ROWS = 200     # rows examined per execution that look like a full scan
$REAL_TABLE_ROWS = 1000   # ...on a table big enough for it to matter
$realProblems = 0
foreach ($r in $raw) {
    $shape = $r["shape"]
    $m = [regex]::Match($shape, 'FROM\s+`?([a-z_]+)`?')
    if (-not $m.Success) {
        # The FROM clause is outside the captured window (long column lists). Report it as
        # unclassified rather than silently calling it benign - "could not tell" and "fine"
        # are different answers, and collapsing them is how an audit lies to itself.
        Emit ("INFO  [unclassified] execs=$($r['execs']) no_idx=$($r['no_idx']) rows/exec=$($r['rows_per_exec']) - FROM clause not inside the captured digest window")
        Emit ("        shape: " + $shape)
        continue
    }
    $tbl = $m.Groups[1].Value
    $size = $(if ($sizes.ContainsKey($tbl)) { $sizes[$tbl] } else { 0 })
    $perExec = [double]$r["rows_per_exec"]
    $isReal = ($size -gt $REAL_TABLE_ROWS) -and ($perExec -gt $REAL_SCAN_ROWS)
    if ($isReal) { $realProblems++ }
    $verdict = $(if ($isReal) { "REAL-GAP(history)" } else { "benign" })
    $why = $(if ($size -le $REAL_TABLE_ROWS) { "table only $size rows" } else { "$perExec rows/exec on a $size-row table" })
    Emit ("INFO  [$verdict] $tbl (rows=$size): execs=$($r['execs']) no_idx=$($r['no_idx']) rows/exec=$perExec - $why")
    Emit ("        shape: " + $shape)
}
Emit "INFO  counters are cumulative since MySQL start, so REAL-GAP(history) entries include scans from before the batch40 fix."
Emit "INFO  verdict rule: rows/exec > $REAL_SCAN_ROWS on a table larger than $REAL_TABLE_ROWS rows; smaller tables are scanned by design and are not defects."

# ---------------------------------------------------------------------------
# 2. PLAN - can the optimizer use an index for the hot paths?
# ---------------------------------------------------------------------------
Emit ""
Emit "--- plan: EXPLAIN the hot paths (a full scan on a big table is a FAIL) ---"

function ExplainType([string]$query) {
    $lines = Sql ("EXPLAIN " + $query)
    if ($lines.Count -lt 2) { return "?" }
    $header = $lines[0] -split "`t"
    $cells = $lines[1] -split "`t"
    $idx = [array]::IndexOf($header, "type")
    $keyIdx = [array]::IndexOf($header, "key")
    $rowIdx = [array]::IndexOf($header, "rows")
    return @{
        type = $(if ($idx -ge 0 -and $idx -lt $cells.Count) { $cells[$idx] } else { "?" })
        key  = $(if ($keyIdx -ge 0 -and $keyIdx -lt $cells.Count) { $cells[$keyIdx] } else { "?" })
        rows = $(if ($rowIdx -ge 0 -and $rowIdx -lt $cells.Count) { $cells[$rowIdx] } else { "?" })
    }
}

$hotPaths = @(
    @{ name = "payment_record WHERE order_id = ?"; table = "payment_record";
       sql = "SELECT id, user_id, order_id, payment_type, amount_fen, channel, trade_no, status, remark, create_time FROM payment_record WHERE order_id = 1";
       requiresIndexOn = "order_id" },
    @{ name = "payment_record WHERE order_id IN (...)"; table = "payment_record";
       sql = "SELECT id, order_id, payment_type FROM payment_record WHERE order_id IN (1, 2)";
       requiresIndexOn = "order_id" },
    # The alarm page's "all" tab passes no handled filter, so the statement is ORDER BY only.
    # Before batch40 this was type=ALL + Using filesort over the whole (growing) alarm table;
    # neither existing alarm index leads with create_time, which is why the filtered tab was
    # fast and the unfiltered tab was not.
    @{ name = "alarm list ordered by create_time (the all tab)"; table = "alarm";
       sql = "SELECT id, device_type, device_no, alarm_type, content, handled, handler, create_time, handled_time FROM alarm ORDER BY create_time DESC LIMIT 20";
       requiresIndexOn = "create_time" }
)

foreach ($p in $hotPaths) {
    $plan = ExplainType $p.sql
    $size = $(if ($sizes.ContainsKey($p.table)) { $sizes[$p.table] } else { 0 })
    $ok = ($plan.type -ne "ALL") -and ($plan.key -ne "NULL")
    Check ("P1 " + $p.name + " uses an index") $ok ("type=$($plan.type) key=$($plan.key) rows=$($plan.rows) table_rows=$size")

    # Schema-level assertion: the access path must exist as an index leading with the column
    # the predicate filters on. This is what actually broke once (db/18 dropped the only
    # index that led with order_id), so it is asserted directly and not only via EXPLAIN.
    $lead = SqlRows ("SELECT COUNT(*) AS n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='swap_ops' AND TABLE_NAME='" + $p.table + "' AND SEQ_IN_INDEX=1 AND COLUMN_NAME='" + $p.requiresIndexOn + "'")
    $n = [int]$lead[0]["n"]
    Check ("P2 " + $p.table + " has an index leading with " + $p.requiresIndexOn) ($n -gt 0) ("leading-column indexes=$n")
}

# The composite index for the reconcile window. It is NOT chosen for the dominant shape on
# this dev dataset, and that is the correct optimizer decision, not a failure: status=5 is
# 5,681 of 5,842 rows (~97%), so "status = 5 AND complete_time >= <everything>" has nothing to
# narrow. The index earns its place on the shape the reconcile task actually runs - a bounded
# time window - so that is the shape asserted, with the window taken from the data.
$windowStart = SqlRows "SELECT (SELECT MAX(complete_time) FROM swap_order WHERE status = 5) - 3600000 AS since_ms"
$since = $(if ($windowStart.Count -gt 0) { $windowStart[0]["since_ms"] } else { "0" })
$plan = ExplainType ("SELECT COUNT(*) FROM swap_order WHERE status = 5 AND complete_time >= " + $since)
Check "P3 swap_order reconcile window uses idx_status_complete" ($plan.key -eq "idx_status_complete") ("window_since=$since type=$($plan.type) key=$($plan.key) rows=$($plan.rows)")
$composite = SqlRows "SELECT COUNT(*) AS n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='swap_ops' AND TABLE_NAME='swap_order' AND INDEX_NAME='idx_status_complete'"
Check "P4 swap_order has idx_status_complete(status, complete_time) for the reconcile window" ([int]$composite[0]["n"] -eq 2) ("index columns=" + [int]$composite[0]["n"])

# ---------------------------------------------------------------------------
# 3. CAUSAL DELTA - real traffic, measured before/after
#
# This is the part that proves a fix rather than describing one. For each watched statement
# the gate reads the server-side counters, drives real HTTP traffic through the running
# platform, reads them again, and asserts three things: the statement really was executed,
# none of those executions went without an index, and rows-examined-per-execution collapsed
# to roughly what the query returns (pre-fix it was a whole-table scan).
# ---------------------------------------------------------------------------
Emit ""
Emit "--- causal delta: drive real traffic and compare the server-side counters ---"

function Counter([string]$filter) {
    $r = SqlRows ("SELECT COUNT_STAR AS execs, SUM_NO_INDEX_USED AS no_idx, SUM_ROWS_EXAMINED AS examined FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='swap_ops' AND " + $filter + " ORDER BY COUNT_STAR DESC LIMIT 1")
    if ($r.Count -eq 0) { return $null }
    return @{ execs = [int]$r[0]["execs"]; no_idx = [int]$r[0]["no_idx"]; examined = [long]$r[0]["examined"] }
}

# Resolve the two traffic targets once.
$orderNo = $null
try {
    $page = (Invoke-WebRequest "$base/admin/view/order?page=1&limit=1" -Headers $headers -UseBasicParsing -TimeoutSec 15).Content | ConvertFrom-Json
    $orderNo = $page.data.list[0].orderNo
} catch { }

$watched = @(
    @{ tag = "W1"; why = "payment_record WHERE order_id = ?";
       filter = "DIGEST_TEXT LIKE '%payment_record%WHERE ( %order_id% = ?%'";
       preFixRowsPerExec = 8090; maxRowsPerExec = 5;
       url = $(if ($orderNo) { "$base/admin/view/order/" + $orderNo } else { $null }) },
    @{ tag = "W2"; why = "alarm list with no handled filter (the all tab)";
       filter = "DIGEST_TEXT LIKE '%FROM %alarm%ORDER BY %create_time% DESC LIMIT%' AND DIGEST_TEXT NOT LIKE '%WHERE%'";
       preFixRowsPerExec = 4032; maxRowsPerExec = 40;
       url = "$base/admin/view/alarm?page=1&limit=20" }
)

$calls = 20
foreach ($w in $watched) {
    if (-not $w.url) { Check ($w.tag + " traffic target available for " + $w.why) $false "no URL could be resolved"; continue }
    $before = Counter $w.filter
    if ($before -eq $null) { Emit ("INFO  " + $w.tag + " watched digest not present yet; skipping"); continue }

    $ok = 0
    for ($i = 0; $i -lt $calls; $i++) {
        try {
            $r = Invoke-WebRequest $w.url -Headers $headers -UseBasicParsing -TimeoutSec 15
            if ($r.StatusCode -eq 200) { $ok++ }
        } catch { }
    }

    $after = Counter $w.filter
    $dExecs = $after.execs - $before.execs
    $dNoIdx = $after.no_idx - $before.no_idx
    $dExamined = $after.examined - $before.examined
    $perExec = $(if ($dExecs -gt 0) { [math]::Round($dExamined / $dExecs, 2) } else { 0 })
    Emit ("INFO  " + $w.tag + " " + $w.why + ": drove $ok/$calls requests; delta execs=+$dExecs no_index_used=+$dNoIdx rows_examined=+$dExamined (=$perExec per execution; pre-fix ~" + $w.preFixRowsPerExec + ")")

    Check ($w.tag + " statements were executed by the traffic (" + $w.why + ")") ($dExecs -ge $calls) ("execs +$dExecs, expected >= $calls")
    Check ($w.tag + " none ran without an index (" + $w.why + ")") ($dNoIdx -eq 0) ("no_index_used +$dNoIdx")
    Check ($w.tag + " rows examined per execution collapsed (" + $w.why + ")") ($perExec -le $w.maxRowsPerExec) ("$perExec rows/exec, threshold $($w.maxRowsPerExec), pre-fix ~$($w.preFixRowsPerExec)")
}

# ---------------------------------------------------------------------------
# 4. UNBOUNDED READS - a SELECT with neither WHERE nor LIMIT that returns many rows
#
# This came out of chasing a loose end: the batch40 audit found a statement
#   SELECT <every column> FROM swap_order ORDER BY create_time DESC
# (6 executions, 5,832 rows returned each, last seen 2026-09-19 02:01) and three searches -
# Java sources, scripts/, .local/ - could not attribute it to any current caller. Spending
# more effort on archaeology for a statement that already stopped is the wrong trade; the
# right one is a guard that catches the CLASS, so if it ever comes back the gate says so
# immediately instead of requiring another hunt.
#
# Rule: a SELECT from the application schema with no WHERE and no LIMIT is unbounded by
# construction. Small result sets are not interesting (a lookup table read has no WHERE
# either), so the threshold is on rows returned per execution. Historical occurrences are
# reported as INFO; anything seen in the last 30 minutes is a FAIL, because that means the
# running platform is doing it right now.
# ---------------------------------------------------------------------------
Emit ""
Emit "--- unbounded reads: SELECT with neither WHERE nor LIMIT ---"

$rowsPerExecMin = 500
$activeWindowMinutes = 30
$unbounded = SqlRows @"
SELECT LEFT(DIGEST_TEXT, 200) AS shape, COUNT_STAR AS execs,
       ROUND(SUM_ROWS_SENT / NULLIF(COUNT_STAR, 0), 1) AS rows_per_exec,
       SUM_ROWS_SENT AS sent, LAST_SEEN,
       TIMESTAMPDIFF(MINUTE, LAST_SEEN, NOW()) AS minutes_ago
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'swap_ops'
  AND DIGEST_TEXT LIKE 'SELECT%'
  AND DIGEST_TEXT NOT LIKE '%WHERE%'
  AND DIGEST_TEXT NOT LIKE '%LIMIT%'
  AND DIGEST_TEXT NOT LIKE '%information_schema%'
  AND DIGEST_TEXT NOT LIKE '%performance_schema%'
  AND SUM_ROWS_SENT / NULLIF(COUNT_STAR, 0) > $rowsPerExecMin
ORDER BY SUM_ROWS_SENT DESC LIMIT 10
"@

$activeUnbounded = 0
if ($unbounded.Count -eq 0) {
    Emit "INFO  none: no SELECT without WHERE/LIMIT returned more than $rowsPerExecMin rows"
} else {
    foreach ($u in $unbounded) {
        $ago = [int]$u["minutes_ago"]
        $isActive = $ago -le $activeWindowMinutes
        if ($isActive) { $activeUnbounded++ }
        Emit ("INFO  [" + $(if ($isActive) { "ACTIVE" } else { "historical" }) + "] execs=$($u['execs']) rows/exec=$($u['rows_per_exec']) last_seen=$($u['LAST_SEEN']) (${ago}min ago)")
        Emit ("        shape: " + $u["shape"])
    }
}
Check "no unbounded SELECT is currently being executed (seen within $activeWindowMinutes min)" ($activeUnbounded -eq 0) ("$activeUnbounded active, " + ($unbounded.Count - $activeUnbounded) + " historical")

Emit ""
Emit "=== C39 summary: PASS=$($script:pass) FAIL=$($script:fail) ==="
Save-Evidence
Emit "INFO evidence written: scripts/verify/batch40/_c39_out.txt"
if ($script:fail -gt 0) { Emit "GATE-INDEX FAIL"; Save-Evidence; exit 1 }
Emit "GATE-INDEX PASS"
Save-Evidence
