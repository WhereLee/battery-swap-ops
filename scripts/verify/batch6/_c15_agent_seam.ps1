# S4.6 gate: agent action seam (propose -> confirm/reject; whitelist; idempotency; audit)
# Prereq: server :8400 fast mode; admin token; MySQL CLI; sim optional
# Evidence: propose has no side effect; idem key returns same action; confirm executes once; reject closes;
#           whitelist rejects non-listed types; audit fields recorded
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c15_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
function SqlScalar($sql) {
    $result = cmd /c "mysql -uroot -proot swap_ops -N -e ""$sql"" 2>nul"
    if ($result -eq $null -or $result -eq "") { return 0 }
    return [int64]$result
}
function Sign-Event($cabinetNo, $canonical, $secret) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    return (-join ($hash | ForEach-Object { $_.ToString("x2") }))
}
Set-Content -Path $out -Value "== S4.6 gate: agent seam ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$secret = (Get-Content (Join-Path $local "dev-secret.txt") -Raw).Trim()
$cabinet = "SWAP-C-005"

# source alarm
$gen = "boot-agent-" + (Get-Date -Format "HHmmss")
$canonical = "$cabinet|CABINET_FAULT|||$gen|9001"
$sign = Sign-Event $cabinet $canonical $secret
$body = @{ cabinetNo = $cabinet; eventType = "CABINET_FAULT"; bootId = $gen; eventSeq = 9001 } | ConvertTo-Json
Invoke-RestMethod -Method Post "$server/device/event" -Headers @{ "X-Device-No" = $cabinet; "X-Device-Sign" = $sign } -ContentType "application/json" -Body $body -TimeoutSec 5 | Out-Null
Start-Sleep -Seconds 1
$alarms = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=200" -Headers $admin -TimeoutSec 5).data
$alarm = @($alarms | Where-Object { $_.deviceNo -eq $cabinet -and $_.alarmType -eq "CABINET_FAULT" })[0]
Check "source alarm found" ($alarm -ne $null)
$alarmId = $alarm.id

# ---- propose (no side effect) + idempotency ----
$form = @{ actionType = "CREATE_WORK_ORDER_FROM_ALARM"; proposer = "agent-ops"; reason = "cabinet fault detected";
    params = @{ alarmId = $alarmId; severity = "HIGH" } } | ConvertTo-Json -Depth 4
$proposeHeaders = @{ "X-Admin-Token" = $admin["X-Admin-Token"]; "Idempotency-Key" = "idem-$alarmId-1" }
$proposal = (Invoke-RestMethod -Method Post "$server/admin/agent-action" -Headers $proposeHeaders -ContentType "application/json" -Body $form -TimeoutSec 5).data
Log "proposal no=$($proposal.actionNo) status=$($proposal.status) proposer=$($proposal.proposer)"
Check "proposed as PROPOSED (1)" ($proposal.status -eq 1)
$woBefore = SqlScalar "SELECT COUNT(*) FROM work_order WHERE alarm_id = $alarmId"
Check "no work order created before confirm" ($woBefore -eq 0)

$replay = (Invoke-RestMethod -Method Post "$server/admin/agent-action" -Headers $proposeHeaders -ContentType "application/json" -Body $form -TimeoutSec 5).data
Check "idempotent replay returns same action" ($replay.id -eq $proposal.id)

# ---- whitelist rejects ----
$denied = $false
try {
    Invoke-RestMethod -Method Post "$server/admin/agent-action" -Headers $admin -ContentType "application/json" -Body (@{ actionType = "REFUND_MONEY"; params = @{ amount = 100 } } | ConvertTo-Json -Depth 3) -TimeoutSec 5 | Out-Null
} catch { $denied = $true }
Check "non-whitelisted action rejected (money)" $denied

# ---- confirm executes exactly once ----
$confirmed = (Invoke-RestMethod -Method Post "$server/admin/agent-action/$($proposal.id)/confirm" -Headers $admin -TimeoutSec 10).data
Log "confirmed status=$($confirmed.status) confirmer=$($confirmed.confirmer) result=$($confirmed.resultJson)"
Check "executed (2) with result" ($confirmed.status -eq 2 -and $confirmed.resultJson -ne $null)
$woAfter = SqlScalar "SELECT COUNT(*) FROM work_order WHERE alarm_id = $alarmId"
Check "work order created after confirm" ($woAfter -eq 1)

$doubleConfirm = $false
try {
    Invoke-RestMethod -Method Post "$server/admin/agent-action/$($proposal.id)/confirm" -Headers $admin -TimeoutSec 5 | Out-Null
} catch { $doubleConfirm = $true }
Check "double confirm refused" $doubleConfirm
Check "still exactly one work order" ((SqlScalar "SELECT COUNT(*) FROM work_order WHERE alarm_id = $alarmId") -eq 1)

# ---- reject flow ----
$p2 = (Invoke-RestMethod -Method Post "$server/admin/agent-action" -Headers $admin -ContentType "application/json" -Body (@{ actionType = "RUN_RECONCILE"; proposer = "agent-ops"; reason = "daily check" } | ConvertTo-Json) -TimeoutSec 5).data
$rejected = (Invoke-RestMethod -Method Post "$server/admin/agent-action/$($p2.id)/reject?remark=not-needed" -Headers $admin -TimeoutSec 5).data
Check "rejected (3)" ($rejected.status -eq 3)
Check "no reconcile side effect on reject" ($rejected.resultJson -eq $null -or $rejected.resultJson -eq "")

# ---- audit view ----
$audit = (Invoke-RestMethod "$server/admin/agent-action?page=1&limit=10" -Headers $admin -TimeoutSec 5).data
Check "audit list contains both actions" (@($audit.list).Count -ge 2)

if ($script:fail -eq 0) { Log "GATE-AGENT-SEAM PASS"; exit 0 } else { Log "GATE-AGENT-SEAM FAIL checks=$($script:fail)"; exit 1 }
