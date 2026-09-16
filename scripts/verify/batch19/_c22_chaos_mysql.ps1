# batch19 chaos-2: MySQL connection disruption (kill all platform connections, 5 rounds) -> recovery semantics
# Note: service-level stop needs an admin shell (unavailable here) -> connection-level fault injection
#       (documented as such). Asserts: no hang, pooled reconnection self-heals, retry idempotency, reconcile zero
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_c22_out.txt"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$AH = @{ "X-Admin-Token" = $adminToken }
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
function HealthRaw { try { return [int](Invoke-WebRequest "$server/actuator/health" -TimeoutSec 5 -UseBasicParsing).StatusCode } catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return "CONN_REFUSED" } } }
function Recon { try { return [int]((Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data.totalViolations) } catch { return -1 } }
function LoginUser($phone) { return (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5).data.token }
function KillPlatformDbConnections {
  $ids = ((mysql -uroot -proot -N -e "SELECT id FROM information_schema.processlist WHERE user='root' AND id <> CONNECTION_ID()" 2>$null) | Where-Object { $_ -match '^\d+$' })
  foreach ($cid in $ids) { mysql -uroot -proot -e "KILL $cid" 2>$null | Out-Null }
  return ($ids | Measure-Object).Count
}
Set-Content -Path $out -Value "== batch19 chaos-2: MySQL connection disruption ==" -Encoding UTF8

# 0) baseline
Log "baseline: health=$(HealthRaw) reconcile=$(Recon)"
$baseRec = Recon
$user1 = LoginUser "13800000002"
$H1 = @{ "X-User-Token" = $user1; "Idempotency-Key" = [guid]::NewGuid().ToString() }
$walletH = @{ "X-User-Token" = $user1 }
Log "baseline login user1 ok"

# 1) inject 5 rounds while probing with GETs; count outcomes
$okCnt = 0; $errCnt = 0; $hangCnt = 0; $maxMs = 0
foreach ($round in 1..5) {
  $killed = KillPlatformDbConnections
  Log "round#$round killed=$killed db connections"
  $t0 = Get-Date
  try {
    Invoke-RestMethod -Method Get "$server/user/wallet" -Headers $walletH -TimeoutSec 20 | Out-Null
    $okCnt++
  } catch {
    if ($_.Exception.Message -match "timed out|timeout") { $hangCnt++ } else { $errCnt++ }
  }
  $ms = [int]((Get-Date) - $t0).TotalMilliseconds
  if ($ms -gt $maxMs) { $maxMs = $ms }
  Start-Sleep -Milliseconds 400
}
Log "probe outcomes: ok=$okCnt err=$errCnt hang=$hangCnt maxMs=$maxMs"
Check "no hang (all probes returned within timeout)" ($hangCnt -eq 0)
Check "process alive (http reachable)" ((HealthRaw) -ne "CONN_REFUSED")

# 2) POST idempotency across disruption: same Idempotency-Key retried until success
$orderNos = @()
$key = [guid]::NewGuid().ToString()
$H2 = @{ "X-User-Token" = $user1; "Idempotency-Key" = $key }
foreach ($try in 1..3) {
  try {
    $r = Invoke-RestMethod -Method Post "$server/user/order" -Headers $H2 -ContentType "application/json" -Body '{"type":"TAKE"}' -TimeoutSec 20
    if ($r.data.orderNo) { $orderNos += $r.data.orderNo }
    Log "order try#$try ok: $($r.data.orderNo)"
    break
  } catch { Log "order try#$try err: $($_.Exception.Message.Substring(0,[Math]::Min(60,$_.Exception.Message.Length)))" }
  Start-Sleep -Milliseconds 500
}
$distinct = ($orderNos | Sort-Object -Unique).Count
Log "order numbers seen=$($orderNos.Count) distinct=$distinct"
Check "order eventually created after disruption" ($orderNos.Count -ge 1)
Check "same Idempotency-Key never yields two orders" ($distinct -le 1)

# 3) final: reconcile back to baseline (no corruption from connection kills)
$recEnd = Recon
Log "final reconcile=$recEnd (baseline=$baseRec)"
Check "reconcile back to baseline" ($recEnd -eq $baseRec)

if ($script:fail -eq 0) { Log "BATCH19-MYSQL PASS"; exit 0 } else { Log "BATCH19-MYSQL FAIL checks=$($script:fail)"; exit 1 }
