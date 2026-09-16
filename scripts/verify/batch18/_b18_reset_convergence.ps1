# batch18 gate: dev-reset convergence (interleaved reference graph -> reset -> zero residue)
# Regression for pitfalls/dev-reset-nonconvergent-orphans.md (two-phase reset, batch18)
# Prereq: server :8400 (dev enabled) + sim :8500; admin token at .local/admin-token.txt
# Design note: the interleaved 4-cycle graph below is *consistent* (reconcile-clean) but misaligned;
#   the OLD one-pass reset left dangling backrefs (cells 1/4 suspended) -> this gate would FAIL there.
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_b18_out.txt"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$AH = @{ "X-Admin-Token" = $adminToken }
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
function Sql($q) { return ((mysql -uroot -proot -N -e $q 2>$null) | Where-Object { $_ -ne $null }) -join "" }
function Reset-Dev { return (Invoke-RestMethod -Method Post "$server/dev/device/reset" -TimeoutSec 60).data }
function CbViolations {
    $checks = (Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data.checks
    return [int]($checks | Where-Object { $_.name -eq 'cell-battery-consistency' }).violations
}
function SetRef($cellId, $batId) {
    Sql "UPDATE swap_ops.cell SET battery_id=$batId WHERE id=$cellId" | Out-Null
    Sql "UPDATE swap_ops.battery SET cell_id=$cellId WHERE id=$batId" | Out-Null
}
Set-Content -Path $out -Value "== batch18: dev-reset convergence (interleaved refs -> reset -> zero residue) ==" -Encoding UTF8

# 0) health
$h = try { (Invoke-RestMethod "http://127.0.0.1:8400/api/actuator/health" -TimeoutSec 5).status } catch { "DOWN" }
Log "server health=$h"
Check "server up" ($h -eq "UP")

# 1) reset#1: clean baseline
$r1 = Reset-Dev
Log "reset#1 cabinets=$($r1.cabinets) occupied=$($r1.cellsOccupied) detached=$($r1.batteriesDetached) seedMissing=$(@($r1.seedMissing).Count)"
Check "reset#1 occupied = cabinets x 6" ([int]$r1.cellsOccupied -eq [int]$r1.cabinets * 6)
Check "reset#1 no seed missing" (@($r1.seedMissing).Count -eq 0)
Check "reset#1 reconcile cell-battery = 0" ((CbViolations) -eq 0)

# 2) build interleaved 4-cycle graph in C-001 (cells/batteries 1..6); consistent by design
Sql "UPDATE swap_ops.battery SET cell_id=NULL WHERE id BETWEEN 1 AND 6" | Out-Null
Sql "UPDATE swap_ops.cell SET battery_id=NULL WHERE id BETWEEN 1 AND 6" | Out-Null
SetRef 1 6; SetRef 6 4; SetRef 4 5; SetRef 5 1; SetRef 2 2; SetRef 3 3
Log "interleaved graph built: 1<->6, 6<->4, 4<->5, 5<->1, 2<->2, 3<->3"
Check "interleaved graph consistent (reconcile 0)" ((CbViolations) -eq 0)

# 3) reset#2: must reseat exactly (old one-pass reset failed here with dangling backrefs)
$r2 = Reset-Dev
Log "reset#2 occupied=$($r2.cellsOccupied) seedMissing=$(@($r2.seedMissing).Count)"
$fwd = Sql "SELECT GROUP_CONCAT(CONCAT(c.id,'=',b.battery_no) ORDER BY c.id) FROM swap_ops.cell c JOIN swap_ops.battery b ON c.battery_id=b.id WHERE c.id BETWEEN 1 AND 6"
$rev = Sql "SELECT GROUP_CONCAT(CONCAT(id,'=',cell_id) ORDER BY id) FROM swap_ops.battery WHERE id BETWEEN 1 AND 6"
Log "fwd=$fwd"
Log "rev=$rev"
Check "C-001 cells reseated exactly (1..6 -> BAT-0001..0006)" ($fwd -eq "1=BAT-0001,2=BAT-0002,3=BAT-0003,4=BAT-0004,5=BAT-0005,6=BAT-0006")
Check "battery backrefs exact (b1..b6 -> cell 1..6)" ($rev -eq "1=1,2=2,3=3,4=4,5=5,6=6")
Check "reset#2 reconcile cell-battery = 0" ((CbViolations) -eq 0)

# 4) reset#3: idempotent
$r3 = Reset-Dev
Log "reset#3 occupied=$($r3.cellsOccupied)"
Check "reset#3 occupied stable" ([int]$r3.cellsOccupied -eq [int]$r2.cellsOccupied)
Check "reset#3 reconcile cell-battery = 0" ((CbViolations) -eq 0)

if ($script:fail -eq 0) { Log "BATCH18 RESET-CONVERGENCE PASS"; exit 0 } else { Log "BATCH18 FAIL checks=$($script:fail)"; exit 1 }
