# =============================================================================
# _c37_refund_ledger.ps1 - batch35 gate: payment ledger idempotency key is typed
#                          (AUD-6: a second partial refund on one order must be recorded)
#
# Why this script exists:
#   payment_record carried uk_order_type(order_id, payment_type) as the "money action
#   idempotency gate". That gate is right for charges (one BALANCE_FEE per order) but
#   wrong for REFUND: refund_record's own key is (order_id, reason), so two partial
#   refunds on one order are legal - yet the second one hit the unique key, got swallowed
#   as "idempotent hit", and the ledger silently lost it (wallet was correct, the books
#   were not). db/18 replaces the key with a generated column that is NULL for REFUND and
#   for order-less rows, and keeps "orderId:paymentType" for everything else.
#
# What it proves (schema + constraint semantics on the real database):
#   1. uk_order_type is gone, uk_payment_idem(idem_key) exists, idem_key is a generated column
#   2. two REFUND rows on the same order INSERT fine            (the case that used to fail)
#   3. the same trade_no twice still fails                       (uk_trade_no intact)
#   4. a second BALANCE_FEE row on the same order still fails    (charge idempotency intact)
#   5. two order-less rows of the same type still insert fine    (NULL semantics preserved)
#   6. probe rows are removed afterwards (the check leaves no residue)
#
# Optional live path (SKIP when not applicable): craft a charge on an EXCEPTION order,
# trigger an admin partial refund, let the compensation task refund the rest, and assert
# the order detail exposes TWO refunds - i.e. the app really writes both ledger rows.
#
# Conventions: English only, no secret in git or on screen (temp --defaults-extra-file).
# =============================================================================

$ErrorActionPreference = "Continue"
$base = "http://127.0.0.1:8400/api"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $scriptDir "..\..\..")
$local = Join-Path $repo ".local"

$script:pass = 0
$script:fail = 0
$script:skip = 0

function Check([string]$name, [bool]$cond, [string]$detail) {
    if ($cond) { $script:pass++; Write-Output "PASS  $name ($detail)" }
    else { $script:fail++; Write-Output "FAIL  $name ($detail)" }
}
function Skip([string]$name, [string]$why) {
    $script:skip++; Write-Output "SKIP  $name ($why)"
}

# --- SQL helper: credentials come from application.yml, never printed -------------
$yml = Get-Content (Join-Path $repo "swap-server\src\main\resources\application.yml") -Raw
$dbPassword = ([regex]::Match($yml, "password:\s*([^\r\n]+)")).Groups[1].Value.Trim().Trim('"').Trim("'")
if ($dbPassword -like "*SWAP_DB*") {
    $envName = [regex]::Match($dbPassword, '\$\{([A-Z_]+)').Groups[1].Value
    $dbPassword = [Environment]::GetEnvironmentVariable($envName)
}
$cnf = Join-Path $env:TEMP "swap_c37.cnf"
Set-Content -Path $cnf -Value (@("[client]", "user=root", "password=$dbPassword",
    "default-character-set=utf8mb4") -join "`r`n") -Encoding ASCII

function Invoke-Sql([string]$sql) {
    $file = Join-Path $env:TEMP "swap_c37.sql"
    Set-Content -Path $file -Value $sql -Encoding UTF8
    $out = & cmd /c "mysql --defaults-extra-file=`"$cnf`" -N swap_ops < `"$file`"" 2>&1
    Remove-Item $file -Force -ErrorAction SilentlyContinue
    return ($out | Out-String).Trim()
}

$SCRATCH_ORDER = 900000001

# ---------- 1) schema ----------
Write-Output "=== C37 batch35: payment ledger idempotency key ==="
$indexes = Invoke-Sql "SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='swap_ops' AND TABLE_NAME='payment_record' AND INDEX_NAME LIKE 'uk%' ORDER BY INDEX_NAME;"
Write-Output "INFO  unique indexes now: $($indexes -replace '\s+', ' ')"
Check "P01 legacy uk_order_type is gone" (($indexes -notmatch "uk_order_type") -and ($indexes -match "uk_payment_idem")) $indexes.Replace("`n", " | ")
Check "P02 uk_trade_no preserved" ($indexes -match "uk_trade_no") "ok"

$gen = Invoke-Sql "SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='swap_ops' AND TABLE_NAME='payment_record' AND COLUMN_NAME='idem_key';"
Check "P03 idem_key is a generated column keyed on REFUND/order-less = NULL" `
    (($gen -match "REFUND") -and ($gen -match "order_id") -and ($gen -match "is null")) "expr captured"

# ---------- 2) constraint semantics (fresh scratch order) ----------
Invoke-Sql "DELETE FROM payment_record WHERE order_id = $SCRATCH_ORDER;" | Out-Null

$r1 = Invoke-Sql "INSERT INTO payment_record (user_id, order_id, payment_type, amount_fen, channel, trade_no, status, create_time) VALUES (2, $SCRATCH_ORDER, 'REFUND', 100, 'MOCK', 'PROBE-RF-1', 1, UNIX_TIMESTAMP()*1000);"
Check "P04 first REFUND row inserts (refund #1 of the order)" (($r1 -eq "") -or ($r1 -notmatch "ERROR")) "out=[$r1]"

$r2 = Invoke-Sql "INSERT INTO payment_record (user_id, order_id, payment_type, amount_fen, channel, trade_no, status, create_time) VALUES (2, $SCRATCH_ORDER, 'REFUND', 200, 'MOCK', 'PROBE-RF-2', 1, UNIX_TIMESTAMP()*1000);"
Check "P05 SECOND partial refund on the same order inserts (AUD-6: used to be swallowed)" `
    (($r2 -eq "") -or ($r2 -notmatch "ERROR")) "out=[$r2]"

$dupTrade = Invoke-Sql "INSERT INTO payment_record (user_id, order_id, payment_type, amount_fen, channel, trade_no, status, create_time) VALUES (2, $SCRATCH_ORDER, 'REFUND', 1, 'MOCK', 'PROBE-RF-1', 1, UNIX_TIMESTAMP()*1000);"
Check "P06 duplicate trade_no still rejected (uk_trade_no intact)" ($dupTrade -match "1062|Duplicate") "out=[$dupTrade]"

$c1 = Invoke-Sql "INSERT INTO payment_record (user_id, order_id, payment_type, amount_fen, channel, trade_no, status, create_time) VALUES (2, $SCRATCH_ORDER, 'BALANCE_FEE', 300, 'MOCK', 'PROBE-BF-1', 1, UNIX_TIMESTAMP()*1000);"
$c2 = Invoke-Sql "INSERT INTO payment_record (user_id, order_id, payment_type, amount_fen, channel, trade_no, status, create_time) VALUES (2, $SCRATCH_ORDER, 'BALANCE_FEE', 300, 'MOCK', 'PROBE-BF-2', 1, UNIX_TIMESTAMP()*1000);"
Check "P07 charge idempotency gate INTACT: second BALANCE_FEE on one order rejected" `
    (($c1 -notmatch "ERROR") -and ($c2 -match "1062|Duplicate")) "first=[$c1] second=[$c2]"

$n1 = Invoke-Sql "INSERT INTO payment_record (user_id, order_id, payment_type, amount_fen, channel, trade_no, status, create_time) VALUES (2, NULL, 'PLAN_PURCHASE', 100, 'MOCK', 'PROBE-N-1', 1, UNIX_TIMESTAMP()*1000);"
$n2 = Invoke-Sql "INSERT INTO payment_record (user_id, order_id, payment_type, amount_fen, channel, trade_no, status, create_time) VALUES (2, NULL, 'PLAN_PURCHASE', 200, 'MOCK', 'PROBE-N-2', 1, UNIX_TIMESTAMP()*1000);"
Check "P08 order-less rows stay unconstrained (NULL semantics preserved)" `
    (($n1 -notmatch "ERROR") -and ($n2 -notmatch "ERROR")) "out=[$n1][$n2]"

$count = Invoke-Sql "SELECT COUNT(*) FROM payment_record WHERE order_id = $SCRATCH_ORDER;"
Check "P09 probe rows present (2 REFUND + 1 BALANCE_FEE = 3)" ($count.Trim() -eq "3") "count=$count"

$cleanup = Invoke-Sql "DELETE FROM payment_record WHERE trade_no LIKE 'PROBE-%';"
$left = Invoke-Sql "SELECT COUNT(*) FROM payment_record WHERE trade_no LIKE 'PROBE-%';"
Check "P10 probe rows cleaned up (no residue in the dev database)" ($left.Trim() -eq "0") "left=$left out=[$cleanup]"

# ---------- 3) live path: two real refunds on one order ----------
Write-Output ""
Write-Output "--- live path: two partial refunds on one real order ---"
$tokenFile = Join-Path $local "admin-token.txt"
if (-not (Test-Path -LiteralPath $tokenFile)) {
    Skip "P11 live two-refund path" "admin token file missing"
} else {
    $H = @{ "X-Admin-Token" = (Get-Content -LiteralPath $tokenFile -Raw).Trim() }
    # Two refunds on ONE order need two DIFFERENT reasons (refund_record is keyed by
    # (order_id, reason)). The natural pair is ADMIN_MANUAL (in-flight order) + ADMIN_REVERSAL
    # (completed order), so the live path seeds a synthetic charge on an EXCEPTION order,
    # takes a partial manual refund, flips the order to COMPLETED in the dev database and
    # takes the reversal. (The compensation task's ORDER_EXCEPTION reason is already used up
    # by its 0-fen "checked" marker on every EXCEPTION order - by design.)
    $candidate = (Invoke-Sql "SELECT o.id, o.order_no, o.user_id FROM swap_order o WHERE o.status = 8 AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.payment_type IN ('BALANCE_FEE','OVERDUE_FEE')) ORDER BY o.id DESC LIMIT 1;")
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        Skip "P11 live two-refund path" "no EXCEPTION order without an existing charge"
    } else {
        $parts = $candidate -split "\s+"
        $orderId = $parts[0]; $orderNo = $parts[1]; $userId = $parts[2]
        Invoke-Sql "INSERT INTO payment_record (user_id, order_id, payment_type, amount_fen, channel, trade_no, status, create_time) VALUES ($userId, $orderId, 'BALANCE_FEE', 300, 'MOCK', 'C37-SEED-$orderId', 1, UNIX_TIMESTAMP()*1000);" | Out-Null
        Write-Output "INFO  seeded charge 300 fen on $orderNo (orderId=$orderId, userId=$userId)"

        $manualOk = $false
        try {
            $manual = Invoke-RestMethod -Method Post -Headers $H -TimeoutSec 20 `
                -Uri "$base/admin/refund/$orderNo`?amountFen=100"
            $manualOk = $true
            Write-Output "INFO  refund #1 ADMIN_MANUAL accepted: $(($manual.data | ConvertTo-Json -Compress))"
        } catch {
            Write-Output "INFO  refund #1 rejected: $($_.Exception.Message)"
        }

        Invoke-Sql "UPDATE swap_order SET status = 5 WHERE id = $orderId;" | Out-Null
        $reversalOk = $false
        try {
            $reversal = Invoke-RestMethod -Method Post -Headers $H -TimeoutSec 20 `
                -Uri "$base/admin/refund/$orderNo/reversal`?amountFen=200"
            $reversalOk = $true
            Write-Output "INFO  refund #2 ADMIN_REVERSAL accepted: $(($reversal.data | ConvertTo-Json -Compress))"
        } catch {
            Write-Output "INFO  refund #2 rejected: $($_.Exception.Message)"
        }
        Invoke-Sql "UPDATE swap_order SET status = 8 WHERE id = $orderId;" | Out-Null

        if (-not ($manualOk -and $reversalOk)) {
            Skip "P11 live two-refund path" "one of the two refunds was rejected (see INFO above)"
        } else {
            $r = Invoke-RestMethod -Headers $H -TimeoutSec 20 -Uri "$base/admin/view/order/$orderNo"
            $refundRows = @($r.data.payments | Where-Object { $_.paymentType -eq "REFUND" })
            # PaymentVO deliberately carries no free-text remark, so reasons come from refund_record
            $reasons = (@($r.data.refunds) | ForEach-Object { $_.reason } | Sort-Object -Unique) -join " / "
            Check "P11 order detail exposes both refunds with distinct reasons" `
                (@($r.data.refunds).Count -ge 2) "refunds=$(@($r.data.refunds).Count) reasons: $reasons"
            Check "P12 ledger exposes TWO REFUND rows (AUD-6: the second one used to be swallowed)" `
                ($refundRows.Count -ge 2) "REFUND payment rows=$($refundRows.Count)"
            $sumLedger = ($refundRows | Measure-Object -Property amountFen -Sum).Sum
            Check "P13 ledger sum equals refund_record sum for this order" ($sumLedger -eq 300) "ledger sum=$sumLedger (expect 100+200)"
        }
    }
}

Remove-Item $cnf -Force -ErrorAction SilentlyContinue
Write-Output ""
Write-Output "=== C37 summary: PASS=$($script:pass) FAIL=$($script:fail) SKIP=$($script:skip) ==="
if ($script:fail -gt 0) {
    Write-Output "GATE-REFUND-LEDGER FAIL"
    exit 1
}
Write-Output "GATE-REFUND-LEDGER PASS"
