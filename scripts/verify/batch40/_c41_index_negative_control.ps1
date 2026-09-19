# =============================================================================
# _c41_index_negative_control.ps1 - batch43: prove the index audit CAN fail
#
# The _c39 audit has only ever been observed green. Its central assertion is "the access path
# exists", and the failure it guards against is silent by nature (queries keep returning correct
# results), so "it passes" is worth very little until the same script is shown going red.
#
# What it does:
#   1. drop idx_order_id (the access path batch40 restored) - this is the exact defect shape
#   2. run _c39 and assert it FAILS, and that the failing check is the access-path one
#   3. re-apply db/19 to put the index back (idempotent migration)
#   4. run _c39 again and assert it PASSES
#
# The index is removed for only a few seconds on the LOCAL dev database, and step 3 runs from a
# finally block so an interrupted run cannot leave it missing. Nothing else is touched: db/19 is
# additive DDL with its own rollback documented in the file header.
#
# Evidence: _c41_out.txt (this script writes its own record, like every other gate here).
# =============================================================================

$ErrorActionPreference = "Continue"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $scriptDir "..\..\..")
$local = Join-Path $repo ".local"

$script:evidence = New-Object System.Collections.Generic.List[string]
$script:outFile = Join-Path $scriptDir "_c41_out.txt"
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

function Sql([string]$query) {
    return (& (Join-Path $local "db-query.ps1") -Query $query 2>&1 | ForEach-Object { "$_" })
}
function IndexPresent {
    $r = Sql "SELECT COUNT(*) AS n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='swap_ops' AND TABLE_NAME='payment_record' AND INDEX_NAME='idx_order_id'"
    return ($r -join "`n") -match "(?m)^1\s*$"
}
function RunAudit {
    # The audit exits non-zero when it fails; capture both the exit code and the output.
    $lines = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo "scripts\verify\batch40\_c39_index_audit.ps1") 2>&1
    return @{ lines = ($lines | ForEach-Object { "$_" }); code = $LASTEXITCODE }
}

Emit "=== C41 batch43: negative control for the index audit (the gate must be able to fail) ==="

$started = IndexPresent
Emit "INFO  baseline: idx_order_id present = $started"
Check "precondition: idx_order_id exists before the control" $started
if (-not $started) { Save-Evidence; exit 1 }

try {
    Emit ""
    Emit "--- step 1: drop the access path (reproduce the batch40 defect shape) ---"
    Sql "ALTER TABLE payment_record DROP INDEX idx_order_id" | ForEach-Object { Emit ("      " + $_) }
    $dropped = -not (IndexPresent)
    Emit "INFO  idx_order_id present after drop = $(-not $dropped)"
    Check "step 1: the index is really gone" $dropped

    Emit ""
    Emit "--- step 2: the audit must go RED, and red on the access-path check ---"
    $red = RunAudit
    $redFailLines = $red.lines | Where-Object { $_ -match '^FAIL' }
    $redLines = $red.lines | Where-Object { $_ -match '^(PASS|FAIL)  P[12] ' }
    $redLines | ForEach-Object { Emit ("      " + $_) }
    Emit "INFO  audit exit code with the index missing = $($red.code)"
    Check "step 2: audit exits non-zero" ($red.code -ne 0) ("exit=" + $red.code)
    Check "step 2: it reports at least one FAIL" ($redFailLines.Count -gt 0) ($redFailLines.Count.ToString() + " FAIL lines")
    Check "step 2: the FAIL is the access-path assertion" (($redFailLines -join "`n") -match 'uses an index|leading with|access path') (($redFailLines | Select-Object -First 1))
    Check "step 2: the summary reports hardFailures" (($red.lines -join "`n") -match 'GATE-INDEX FAIL')
}
finally {
    Emit ""
    Emit "--- step 3: restore (db/19 is idempotent; runs even if the checks above threw) ---"
    & (Join-Path $local "apply-migration.ps1") -Sql "db\19-index-audit-fixes.sql" 2>&1 | ForEach-Object { Emit ("      " + $_) }
    $restored = IndexPresent
    Check "step 3: idx_order_id is back" $restored
}

Emit ""
Emit "--- step 4: the audit must go GREEN again ---"
$green = RunAudit
($green.lines | Where-Object { $_ -match '^(PASS|FAIL)  P[12] ' -or $_ -match 'summary|GATE-INDEX' }) | ForEach-Object { Emit ("      " + $_) }
Check "step 4: audit exits zero" ($green.code -eq 0) ("exit=" + $green.code)
Check "step 4: GATE-INDEX PASS" (($green.lines -join "`n") -match 'GATE-INDEX PASS')

Emit ""
Emit "=== C41 summary: PASS=$($script:pass) FAIL=$($script:fail) ==="
Save-Evidence
Emit "INFO evidence written: scripts/verify/batch40/_c41_out.txt"
if ($script:fail -gt 0) { Emit "GATE-IDX-NEGCTL FAIL"; Save-Evidence; exit 1 }
Emit "GATE-IDX-NEGCTL PASS"
Save-Evidence
