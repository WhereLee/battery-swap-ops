# batch19 chaos-3: broker outage -> platform blast-radius zero -> alarm survives in outbox
#   -> broker-only restart -> outbox auto-delivers (proxy must self-reconnect, no restart) -> sim restart -> heartbeat heals
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_c23_out.txt"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$AH = @{ "X-Admin-Token" = $adminToken }
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
function SqlN($q) { try { $s = ((mysql -uroot -proot swap_ops -N -e $q 2>$null) -join "").Trim(); if ($s -eq "") { return -1 }; return [long]$s } catch { return -1 } }
function SqlS($q) { try { return ((mysql -uroot -proot swap_ops -N -e $q 2>$null) -join " ").Trim() } catch { return "" } }
function Recon { try { return [int]((Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data.totalViolations) } catch { return -1 } }
function LoginUser($phone) { return (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5).data.token }
Set-Content -Path $out -Value "== batch19 chaos-3: broker outage ==" -Encoding UTF8

# 0) baseline
$baseRec = Recon
$alarmBefore = SqlN "SELECT COUNT(*) FROM alarm"
$user = LoginUser "13800000002"
$walletH = @{ "X-User-Token" = $user }
$w0 = Invoke-RestMethod -Method Get "$server/user/wallet" -Headers $walletH -TimeoutSec 5
Log "baseline: reconcile=$baseRec alarms=$alarmBefore wallet(balance=$($w0.data.balanceFen))"

# 1) inject: kill broker (10911) only
$brokerPid = (Get-NetTCPConnection -LocalPort 10911 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
Check "broker running before inject" ($brokerPid -ne $null)
if ($brokerPid) { Stop-Process -Id $brokerPid -Force }
Start-Sleep -Seconds 3
$still = (Get-NetTCPConnection -LocalPort 10911 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
Log "injected: broker killed (pid=$brokerPid)"
Check "broker down after inject" ($still -eq $null)

# 2) blast radius: user path must be unaffected while broker down
$w1ok = $false
try { Invoke-RestMethod -Method Get "$server/user/wallet" -Headers $walletH -TimeoutSec 5 | Out-Null; $w1ok = $true } catch { Log "wallet probe error: $($_.Exception.Message)" }
Check "user path unaffected by broker outage" $w1ok

# 3) generate a real alarm: kill sim -> OFFLINE after heartbeat-timeout (30s) + scan
$simPid = (Get-NetTCPConnection -LocalPort 8500 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
Check "sim running before inject" ($simPid -ne $null)
$cutoff = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if ($simPid) { Stop-Process -Id $simPid -Force }
Log "sim killed (pid=$simPid); waiting OFFLINE alarm (heartbeat 30s + scan, budget 80s)"
$alarmAfter = $alarmBefore
foreach ($i in 1..16) { Start-Sleep -Seconds 5; $alarmAfter = SqlN "SELECT COUNT(*) FROM alarm"; if ($alarmAfter -gt $alarmBefore) { break } }
Log "alarm count $alarmBefore -> $alarmAfter (waited $($i*5)s)"
Check "OFFLINE alarm raised while broker down" ($alarmAfter -gt $alarmBefore)

$unsent = SqlN "SELECT COUNT(*) FROM outbox_event WHERE create_time > $cutoff AND status <> 'SENT'"
$lastErr = SqlS "SELECT last_error FROM outbox_event WHERE create_time > $cutoff ORDER BY id DESC LIMIT 1"
Log "outbox: unsent=$unsent last_error=$($lastErr.Substring(0,[Math]::Min(80,$lastErr.Length)))"
Check "alarm event persisted in outbox (no loss)" ($unsent -ge 1)

# 4) restart broker only (proxy untouched - it must self-reconnect)
Start-Process -FilePath (Join-Path $repo ".local\start-broker.bat") -WorkingDirectory (Join-Path $repo ".local") | Out-Null
$back = $false
foreach ($i in 1..30) { Start-Sleep -Seconds 2; if (Get-NetTCPConnection -LocalPort 10911 -State Listen -ErrorAction SilentlyContinue) { $back = $true; break } }
Log "broker back: $back ($($i*2)s)"
Check "broker restarted" $back

# 5) wait outbox delivery (relay 5s per round; proxy reconnect time unknown)
$sent = 0
foreach ($i in 1..30) { Start-Sleep -Seconds 5; $sent = SqlN "SELECT COUNT(*) FROM outbox_event WHERE create_time > $cutoff AND status = 'SENT'"; if ($sent -ge 1) { break } }
$errNow = SqlS "SELECT last_error FROM outbox_event WHERE create_time > $cutoff ORDER BY id DESC LIMIT 1"
Log "delivery: sent=$sent (waited $($i*5)s) last_error_now=$($errNow.Substring(0,[Math]::Min(60,$errNow.Length)))"
Check "alarm delivered after broker-only restart (proxy self-reconnect)" ($sent -ge 1)

# 6) restart sim -> back online
Start-Process -FilePath (Join-Path $repo ".local\run-sim.bat") -WorkingDirectory (Join-Path $repo ".local") | Out-Null
$simBack = $false
foreach ($i in 1..45) { Start-Sleep -Seconds 2; if (Get-NetTCPConnection -LocalPort 8500 -State Listen -ErrorAction SilentlyContinue) { $simBack = $true; break } }
Log "sim back: $simBack ($($i*2)s)"
Check "sim restarted" $simBack

# 7) final
Start-Sleep -Seconds 3
$recEnd = Recon
Log "final reconcile=$recEnd (baseline=$baseRec)"
Check "reconcile back to baseline" ($recEnd -eq $baseRec)

if ($script:fail -eq 0) { Log "BATCH19-BROKER PASS"; exit 0 } else { Log "BATCH19-BROKER FAIL checks=$($script:fail)"; exit 1 }
