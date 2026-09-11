# S4.4 gate: work order closed loop (alarm -> create -> triage -> assign -> handle -> verify -> close) + SLA breach
# Prereq: server :8400 fast mode (sla-minutes-high=0), sim not required, MySQL CLI in PATH
# Evidence: full lifecycle CAS transitions + audit logs; open WO breaches SLA -> WORK_ORDER_SLA_BREACH alarm
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c7_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
function Sql($sql) { cmd /c "mysql -uroot -proot swap_ops -N -e ""$sql"" 2>nul" }
function Sign-Event($cabinetNo, $canonical, $secret) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    return (-join ($hash | ForEach-Object { $_.ToString("x2") }))
}
Set-Content -Path $out -Value "== S4.4 gate: work order loop ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$secret = (Get-Content (Join-Path $local "dev-secret.txt") -Raw).Trim()
$cabA = "SWAP-C-006"
$cabB = "SWAP-C-007"

# ---- cleanup for deterministic re-runs ----
Sql "DELETE wol FROM work_order_log wol JOIN work_order wo ON wo.wo_no=wol.wo_no WHERE wo.device_no IN ('$cabA','$cabB')" | Out-Null
Sql "DELETE FROM work_order WHERE device_no IN ('$cabA','$cabB')" | Out-Null
Sql "DELETE FROM alarm WHERE device_no IN ('$cabA','$cabB') AND alarm_type='CABINET_FAULT'" | Out-Null
& "F:\Redis\redis-cli.exe" DEL "swap:alarm:dedup:CABINET_FAULT:$cabA" "swap:alarm:dedup:CABINET_FAULT:$cabB" | Out-Null

$gen = "boot-wo-" + (Get-Date -Format "HHmmss")
foreach ($pair in @(@($cabA, 8001), @($cabB, 8101))) {
    $cab = $pair[0]; $seq = $pair[1]
    $canonical = "$cab|CABINET_FAULT|||$gen|$seq"
    $sign = Sign-Event $cab $canonical $secret
    $body = @{ cabinetNo = $cab; eventType = "CABINET_FAULT"; bootId = $gen; eventSeq = $seq } | ConvertTo-Json
    Invoke-RestMethod -Method Post "$server/device/event" -Headers @{ "X-Device-No" = $cab; "X-Device-Sign" = $sign } -ContentType "application/json" -Body $body -TimeoutSec 5 | Out-Null
}
Start-Sleep -Seconds 1
$alarms = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=200" -Headers $admin -TimeoutSec 5).data
$alarmA = @($alarms | Where-Object { $_.deviceNo -eq $cabA -and $_.alarmType -eq "CABINET_FAULT" })[0]
$alarmB = @($alarms | Where-Object { $_.deviceNo -eq $cabB -and $_.alarmType -eq "CABINET_FAULT" })[0]
Check "source alarms raised" ($alarmA -ne $null -and $alarmB -ne $null)

# ---- full lifecycle (LOW sla = 480min, no breach) ----
$woA = (Invoke-RestMethod -Method Post "$server/admin/work-order/from-alarm/$($alarmA.id)?severity=LOW" -Headers $admin -TimeoutSec 5).data
Log "WO-A created woNo=$($woA.woNo) severity=$($woA.severity) status=$($woA.status)"
Check "created as OPEN" ($woA.status -eq 1)

$woA2 = (Invoke-RestMethod -Method Post "$server/admin/work-order/from-alarm/$($alarmA.id)?severity=LOW" -Headers $admin -TimeoutSec 5).data
Check "from-alarm idempotent (same woNo)" ($woA2.woNo -eq $woA.woNo)

$id = $woA.id
$bad = $false
# triage with invalid severity must fail (validate before CAS)
try { Invoke-RestMethod -Method Post "$server/admin/work-order/$id/triage?severity=URGENT" -Headers $admin -TimeoutSec 5 | Out-Null } catch { $bad = $true }
Check "invalid severity rejected" $bad

$t = (Invoke-RestMethod -Method Post "$server/admin/work-order/$id/triage?severity=MEDIUM" -Headers $admin -TimeoutSec 5).data
Check "TRIAGED (2)" ($t.status -eq 2)
$a = (Invoke-RestMethod -Method Post "$server/admin/work-order/$id/assign?handlerId=7" -Headers $admin -TimeoutSec 5).data
Check "ASSIGNED (3)" ($a.status -eq 3)
$s = (Invoke-RestMethod -Method Post "$server/admin/work-order/$id/start" -Headers $admin -TimeoutSec 5).data
Check "HANDLING (4)" ($s.status -eq 4)
$v = (Invoke-RestMethod -Method Post "$server/admin/work-order/$id/verify?remark=ok" -Headers $admin -TimeoutSec 5).data
Check "VERIFIED (5)" ($v.status -eq 5)
$c = (Invoke-RestMethod -Method Post "$server/admin/work-order/$id/close?remark=done" -Headers $admin -TimeoutSec 5).data
Check "CLOSED (6)" ($c.status -eq 6)
Check "no SLA breach on closed WO" ($c.slaBreached -eq 0)

$detail = (Invoke-RestMethod "$server/admin/work-order/$id" -Headers $admin -TimeoutSec 5).data
Log "WO-A audit logs=$($detail.logs.Count)"
Check "audit trail complete (>=6 entries)" ($detail.logs.Count -ge 6)

$again = $false
try { Invoke-RestMethod -Method Post "$server/admin/work-order/$id/triage?severity=HIGH" -Headers $admin -TimeoutSec 5 | Out-Null } catch { $again = $true }
Check "closed WO cannot be re-triaged" $again

# ---- SLA breach (HIGH sla = 0 in fast mode) ----
$woB = (Invoke-RestMethod -Method Post "$server/admin/work-order/from-alarm/$($alarmB.id)?severity=HIGH" -Headers $admin -TimeoutSec 5).data
Log "WO-B created woNo=$($woB.woNo) severity=$($woB.severity)"
$breached = $false
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline) {
    $d = (Invoke-RestMethod "$server/admin/work-order/$($woB.id)" -Headers $admin -TimeoutSec 5).data
    if ($d.order.slaBreached -eq 1) { $breached = $true; break }
    Start-Sleep -Seconds 1
}
Check "open WO breached SLA" $breached

Start-Sleep -Seconds 1
$alarms2 = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=300" -Headers $admin -TimeoutSec 5).data
$breachAlarm = @($alarms2 | Where-Object { $_.alarmType -eq "WORK_ORDER_SLA_BREACH" -and $_.deviceNo -eq $woB.woNo })
Check "WORK_ORDER_SLA_BREACH alarm raised" ($breachAlarm.Count -ge 1)

if ($script:fail -eq 0) { Log "GATE-WORKORDER PASS"; exit 0 } else { Log "GATE-WORKORDER FAIL checks=$($script:fail)"; exit 1 }
