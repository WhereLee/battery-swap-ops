# S6 gate: ops agent loop (alarm -> agent propose -> human confirm -> execute -> audit)
# + reverse assertion: agent process down => platform unaffected (health / device channel / observation seam)
# Prereq: server :8400 up; MySQL CLI on PATH; mvn on PATH; .local/admin-token.txt + dev-secret.txt present
# Evidence: proposals idempotent by agent-<alarmId>-<type>; arrears alarm untouched (funds red line);
#           confirm executes once + work order created + admin_op_log audited; reject no side effect;
#           after killing agent: platform UP, signed heartbeat accepted, new alarms recorded.
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$agentBase = "http://127.0.0.1:8700"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$local = Join-Path $repo ".local"
$out = Join-Path $PSScriptRoot "_c31_out.txt"
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
function SqlInsertGetId($sql) {
    $result = cmd /c "mysql -uroot -proot swap_ops -N -e ""$sql"" 2>nul"
    $line = @($result) | Where-Object { $_ -ne $null -and "$_".Trim() -ne "" } | Select-Object -Last 1
    if (-not $line) { return 0 }
    return [int64]$line
}
function Sign-Payload($canonical, $secret) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    return (-join ($hash | ForEach-Object { $_.ToString("x2") }))
}
Set-Content -Path $out -Value "== S6 gate: ops agent loop ==" -Encoding UTF8

$admin = @{ "X-Admin-Token" = (Get-Content (Join-Path $local "admin-token.txt") -Raw).Trim() }
$secret = (Get-Content (Join-Path $local "dev-secret.txt") -Raw).Trim()
# Probe cabinet: a dedicated number with NO historical alarms, so the device+type group
# contains only this run's alarm (agent groups same device+type and picks the earliest source).
$cab = "SWAP-C-031"
$hbCab = "SWAP-C-005" # real registered cabinet for the signed-heartbeat reverse assertion

# ---- 0) prerequisites: platform up + agent jar built ----
$health = Invoke-RestMethod "$server/actuator/health" -TimeoutSec 5
Check "platform health UP" ($health.status -eq "UP")
Push-Location $repo
& mvn -q -pl swap-agent package -DskipTests 2>&1 | Out-Null
Pop-Location
$jar = Join-Path $repo "swap-agent\target\swap-agent-1.0.0.jar"
Check "agent jar built" (Test-Path $jar)

# ---- 1) plant probe alarms via SQL (deterministic; independent of device-signature path) ----
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$idA = SqlInsertGetId "INSERT INTO alarm (device_type, device_no, alarm_type, content, handled, create_time) VALUES ('CABINET','$cab','CABINET_FAULT','c31 fault probe',0,$now); SELECT LAST_INSERT_ID();"
$idB = SqlInsertGetId "INSERT INTO alarm (device_type, device_no, alarm_type, content, handled, create_time) VALUES ('SYSTEM','reconcile','RECONCILE_ERROR','c31 recon probe',0,$now); SELECT LAST_INSERT_ID();"
$idC = SqlInsertGetId "INSERT INTO alarm (device_type, device_no, alarm_type, content, handled, create_time) VALUES ('USER','U-c31','ORDER_ARREARS','c31 arrears probe',0,$now); SELECT LAST_INSERT_ID();"
Log "probe alarms: A=$idA (CABINET_FAULT->workorder) B=$idB (RECONCILE_ERROR->reconcile) C=$idC (ORDER_ARREARS->restraint)"
Check "3 probe alarms planted" ($idA -gt 0 -and $idB -gt 0 -and $idC -gt 0)

# ---- 2) start agent (bare call, no pipe: spawned-JVM pipe lesson) + bounded health wait ----
powershell -ExecutionPolicy Bypass -File (Join-Path $local "start-agent.ps1")
$agentUp = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    try {
        $h = Invoke-RestMethod "$agentBase/actuator/health" -TimeoutSec 2
        if ($h.status -eq "UP") { $agentUp = $true; break }
    } catch { }
}
Check "agent health UP (bounded wait)" $agentUp

# ---- 3) manual scan round 1 ----
$scan1 = Invoke-RestMethod -Method Post "$agentBase/agent/scan" -TimeoutSec 60
Log "scan1: alarms=$($scan1.alarms) suggestions=$($scan1.suggestions) submitted=$($scan1.submitted) errors=$($scan1.errors)"
Check "scan1 zero submit errors" ($scan1.errors -eq 0)
Check "scan1 produced suggestions for probes" ($scan1.suggestions -ge 2)

# ---- 4) platform-side assertions on the seam (idempotency key) ----
$keyA = "agent-$idA-create_work_order_from_alarm"
$keyB = "agent-$idB-run_reconcile"
Check "A proposed once (work order)" ((SqlScalar "SELECT COUNT(*) FROM agent_action WHERE idem_key = '$keyA'") -eq 1)
Check "B proposed once (reconcile)" ((SqlScalar "SELECT COUNT(*) FROM agent_action WHERE idem_key = '$keyB'") -eq 1)
Check "C got no proposal (funds red line)" ((SqlScalar "SELECT COUNT(*) FROM agent_action WHERE idem_key LIKE 'agent-$idC-%'") -eq 0)
Check "proposal reason tagged [agent-auto]" ((SqlScalar "SELECT COUNT(*) FROM agent_action WHERE idem_key = '$keyA' AND reason LIKE '[agent-auto]%'") -eq 1)

# ---- 5) rescan is idempotent (no duplicate rows) ----
$scan2 = Invoke-RestMethod -Method Post "$agentBase/agent/scan" -TimeoutSec 60
Check "rescan keeps single row for A" ((SqlScalar "SELECT COUNT(*) FROM agent_action WHERE idem_key = '$keyA'") -eq 1)
Check "rescan keeps single row for B" ((SqlScalar "SELECT COUNT(*) FROM agent_action WHERE idem_key = '$keyB'") -eq 1)

# ---- 6) agent endpoints: status / diagnose / summary ----
$st = Invoke-RestMethod "$agentBase/agent/status" -TimeoutSec 5
Check "status: platformUp=true" ($st.platformUp -eq $true)
Check "status: submittedTotal>=2" ($st.submittedTotal -ge 2)
$dg = Invoke-RestMethod "$agentBase/agent/diagnose?deviceNo=$cab" -TimeoutSec 10
Log "diagnose: $($dg.answer)"
Check "diagnose suggests work order" ($dg.answer -like "*CREATE_WORK_ORDER_FROM_ALARM*")
$sm = Invoke-RestMethod "$agentBase/agent/summary?windowMinutes=5" -TimeoutSec 10
$dev = @($sm.devices | Where-Object { $_.deviceNo -eq $cab })
Check "summary window covers probe cabinet" ($dev.Count -ge 1)

# ---- 7) human confirm -> execute -> audit ----
$actA = SqlScalar "SELECT id FROM agent_action WHERE idem_key = '$keyA'"
$confirmed = (Invoke-RestMethod -Method Post "$server/admin/agent-action/$actA/confirm" -Headers $admin -TimeoutSec 15).data
Log "confirmed: no=$($confirmed.actionNo) status=$($confirmed.status) confirmer=$($confirmed.confirmer) result=$($confirmed.resultJson)"
Check "A confirmed & executed (status=2)" ($confirmed.status -eq 2)
Check "work order created from alarm A" ((SqlScalar "SELECT COUNT(*) FROM work_order WHERE alarm_id = $idA") -eq 1)
$doubleDenied = $false
try {
    Invoke-RestMethod -Method Post "$server/admin/agent-action/$actA/confirm" -Headers $admin -TimeoutSec 10 | Out-Null
} catch { $doubleDenied = $true }
Check "double confirm refused (CAS guard)" $doubleDenied
Check "still exactly one work order" ((SqlScalar "SELECT COUNT(*) FROM work_order WHERE alarm_id = $idA") -eq 1)
Check "audit: SUGGESTION_CONFIRM logged" ((SqlScalar "SELECT COUNT(*) FROM admin_op_log WHERE action = 'SUGGESTION_CONFIRM'") -ge 1)

# ---- 8) reject flow (no side effect) ----
$actB = SqlScalar "SELECT id FROM agent_action WHERE idem_key = '$keyB'"
$rejected = (Invoke-RestMethod -Method Post "$server/admin/agent-action/$actB/reject?remark=c31-skip" -Headers $admin -TimeoutSec 10).data
Check "B rejected (status=3)" ($rejected.status -eq 3)

# ---- 9) REVERSE ASSERTION: kill agent -> platform unaffected ----
$conn = Get-NetTCPConnection -LocalPort 8700 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2
$agentDead = $false
try { Invoke-RestMethod "$agentBase/actuator/health" -TimeoutSec 2 | Out-Null } catch { $agentDead = $true }
Check "agent process is down" $agentDead
$h2 = Invoke-RestMethod "$server/actuator/health" -TimeoutSec 5
Check "platform health UP with agent down" ($h2.status -eq "UP")
$hbSign = Sign-Payload "$hbCab|1" $secret
$hbBody = @{ cabinetNo = $hbCab; status = 1; bootId = "c31-hb-$now"; eventSeq = 1 } | ConvertTo-Json
$hbOk = $true
try {
    Invoke-RestMethod -Method Post "$server/device/heartbeat" -Headers @{ "X-Device-No" = $hbCab; "X-Device-Sign" = $hbSign } -ContentType "application/json" -Body $hbBody -TimeoutSec 5 | Out-Null
} catch { $hbOk = $false }
Check "signed heartbeat accepted with agent down" $hbOk
$idD = SqlInsertGetId "INSERT INTO alarm (device_type, device_no, alarm_type, content, handled, create_time) VALUES ('SYSTEM','outbox','OUTBOX_DEAD','c31 after-kill probe',0,$now); SELECT LAST_INSERT_ID();"
$list = (Invoke-RestMethod "$server/admin/alarm?handled=0&limit=500" -Headers $admin -TimeoutSec 5).data
Check "new alarm visible with agent down" (@($list | Where-Object { $_.id -eq $idD }).Count -eq 1)

# ---- 10) cleanup probes (mark handled; keep audit trail / work order) ----
SqlScalar "UPDATE alarm SET handled = 1, handled_time = $now WHERE id IN ($idA,$idB,$idC,$idD)" | Out-Null
Check "probe alarms closed (handled=1)" ((SqlScalar "SELECT COUNT(*) FROM alarm WHERE id IN ($idA,$idB,$idC,$idD) AND handled = 1") -eq 4)

if ($script:fail -eq 0) { Log "GATE-OPS-AGENT PASS"; exit 0 } else { Log "GATE-OPS-AGENT FAIL checks=$($script:fail)"; exit 1 }
