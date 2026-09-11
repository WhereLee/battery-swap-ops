# S3.8 gate: MySQL deadlock reproduction + fix comparison (WP7)
# Prereq: local MySQL (root/root, swap_ops); battery BAT-0001/BAT-0002 exist
# Evidence: opposite lock order -> one session rolled back with 1213; consistent order -> no deadlock.
#           Also dumps LATEST DETECTED DEADLOCK snippet from SHOW ENGINE INNODB STATUS.
$ErrorActionPreference = "Continue"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$tmp = Join-Path $local "deadlock-tmp"
$out = Join-Path $PSScriptRoot "_c5_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S3.8 gate: MySQL deadlock ==" -Encoding UTF8
if (!(Test-Path $tmp)) { New-Item -ItemType Directory -Path $tmp | Out-Null }

function Run-SqlFile($name, $sql, $delayMs) {
    if ($delayMs -gt 0) { Start-Sleep -Milliseconds $delayMs }
    $file = Join-Path $tmp "$name.sql"
    $log = Join-Path $tmp "$name.log"
    [System.IO.File]::WriteAllText($file, $sql, (New-Object System.Text.UTF8Encoding($false)))
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "mysql -uroot -proot swap_ops < `"$file`" > `"$log`" 2>&1" -WindowStyle Hidden -PassThru
}
function Wait-Logs($seconds) { Start-Sleep -Seconds $seconds }
function Log-Text($name) {
    $path = Join-Path $tmp "$name.log"
    if (!(Test-Path $path)) { return "" }
    return [System.IO.File]::ReadAllText($path)
}

# ---- 1) opposite lock order -> deadlock (1213) ----
$a = Run-SqlFile "a_dl" "START TRANSACTION; UPDATE battery SET soc=soc WHERE battery_no='BAT-0001'; SELECT SLEEP(2); UPDATE battery SET soc=soc WHERE battery_no='BAT-0002'; COMMIT;" 0
$b = Run-SqlFile "b_dl" "START TRANSACTION; UPDATE battery SET soc=soc WHERE battery_no='BAT-0002'; SELECT SLEEP(2); UPDATE battery SET soc=soc WHERE battery_no='BAT-0001'; COMMIT;" 400
$a.WaitForExit(20000) | Out-Null
$b.WaitForExit(20000) | Out-Null
Wait-Logs 1
$textA = Log-Text "a_dl"
$textB = Log-Text "b_dl"
$dlA = $textA -match "1213|Deadlock"
$dlB = $textB -match "1213|Deadlock"
Log "opposite order: sessionA_deadlock=$dlA sessionB_deadlock=$dlB"
Check "deadlock reproduced (one session rolled back with 1213)" ($dlA -or $dlB)

# ---- 2) consistent lock order -> no deadlock ----
$c = Run-SqlFile "c_ok" "START TRANSACTION; UPDATE battery SET soc=soc WHERE battery_no='BAT-0001'; SELECT SLEEP(2); UPDATE battery SET soc=soc WHERE battery_no='BAT-0002'; COMMIT;" 0
$d = Run-SqlFile "d_ok" "START TRANSACTION; UPDATE battery SET soc=soc WHERE battery_no='BAT-0001'; SELECT SLEEP(2); UPDATE battery SET soc=soc WHERE battery_no='BAT-0002'; COMMIT;" 400
$c.WaitForExit(20000) | Out-Null
$d.WaitForExit(20000) | Out-Null
Wait-Logs 1
$textC = Log-Text "c_ok"
$textD = Log-Text "d_ok"
$dlC = $textC -match "1213|Deadlock"
$dlD = $textD -match "1213|Deadlock"
Log "consistent order: sessionC_deadlock=$dlC sessionD_deadlock=$dlD"
Check "consistent order: no deadlock" ((-not $dlC) -and (-not $dlD))

# ---- 3) deadlock evidence from InnoDB status ----
$statusFile = Join-Path $tmp "innodb_status.log"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "mysql -uroot -proot swap_ops -e `"SHOW ENGINE INNODB STATUS\G`" > `"$statusFile`" 2>&1" -WindowStyle Hidden -Wait
$status = [System.IO.File]::ReadAllText($statusFile)
$hasDeadlock = $status -match "LATEST DETECTED DEADLOCK"
Log "innodb status contains LATEST DETECTED DEADLOCK: $hasDeadlock"
Check "InnoDB deadlock log present" $hasDeadlock

if ($script:fail -eq 0) { Log "GATE-DEADLOCK PASS"; exit 0 } else { Log "GATE-DEADLOCK FAIL checks=$($script:fail)"; exit 1 }
