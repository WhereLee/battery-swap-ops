# batch19 chaos-1: Redis outage -> degraded semantics -> restore + rebuild-alloc -> zero delta
# Prereq: server :8400 (load profile) + sim :8500 + Redis (F:\Redis) running; admin token at .local/admin-token.txt
# Injects: kill redis-server (hard, data-loss style). Recovers: runbook start cmd + POST /admin/ops/rebuild-alloc
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_c21_out.txt"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$AH = @{ "X-Admin-Token" = $adminToken }
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
function HealthRaw { try { return [int](Invoke-WebRequest "$server/actuator/health" -TimeoutSec 5 -UseBasicParsing).StatusCode } catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return "CONN_REFUSED" } } }
function RedisPing { return ((& "F:\Redis\redis-cli.exe" ping 2>$null) | Out-String).Trim() }
function Recon { try { return [int]((Invoke-RestMethod -Method Post "$server/admin/reconcile/run" -Headers $AH -TimeoutSec 60).data.totalViolations) } catch { return -1 } }
function LoginUser($phone) { return (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5).data.token }
Set-Content -Path $out -Value "== batch19 chaos-1: Redis outage ==" -Encoding UTF8

# 0) baseline
Log "baseline: health=$(HealthRaw) redis=$(RedisPing) reconcile=$(Recon)"
$baseRec = Recon
$user1 = LoginUser "13800000002"
$H1 = @{ "X-User-Token" = $user1; "Idempotency-Key" = [guid]::NewGuid().ToString() }
Log "baseline login user1 ok"

# 1) inject: kill redis-server
$rp = Get-Process redis-server -ErrorAction SilentlyContinue
Check "redis running before inject" ($rp -ne $null)
if ($rp) { $rp | Stop-Process -Force }
Start-Sleep -Seconds 2
Log "injected: redis-server killed (pid=$($rp.Id))"
Check "redis down after inject" ((RedisPing) -ne "PONG")

# 2) degraded assertions (facts recorded; hard asserts = no hang / db-path alive)
$t0 = Get-Date
$code = HealthRaw
Log "health during outage=http:$code (process-alive probe)"
Check "http layer reachable during redis outage (not conn-refused)" ($code -ne "CONN_REFUSED")

$t0 = Get-Date
$orderOutcome = "OK"
try {
  $r = Invoke-RestMethod -Method Post "$server/user/order" -Headers $H1 -ContentType "application/json" -Body '{"type":"TAKE"}' -TimeoutSec 15
  $orderOutcome = "OK(orderNo=$($r.data.orderNo))"
} catch { $orderOutcome = "ERR: " + $_.Exception.Message.Substring(0, [Math]::Min(70, $_.Exception.Message.Length)) }
$el = [int]((Get-Date) - $t0).TotalSeconds
Log "user order during outage: $orderOutcome (${el}s)"
Check "user request returned fast (no hang, <=15s)" ($el -le 15)

$recDuring = Recon
Log "admin reconcile during outage=$recDuring (db-path probe)"
Check "db-path (admin reconcile) alive during redis outage" ($recDuring -ge 0)

# 3) recover: start redis + rebuild alloc pool
Start-Process -FilePath "F:\Redis\redis-server.exe" -ArgumentList "F:\Redis\redis.windows.conf", "--dir", "F:\Redis" -WorkingDirectory "F:\Redis" -WindowStyle Minimized
$ok = $false; foreach ($i in 1..20) { Start-Sleep -Seconds 1; if ((RedisPing) -eq "PONG") { $ok = $true; break } }
Log "redis restored: $ok ($($i)s)"
Check "redis restored" $ok
$rb = $false
try { Invoke-RestMethod -Method Post "$server/admin/ops/rebuild-alloc" -Headers $AH -TimeoutSec 15 | Out-Null; $rb = $true } catch { Log "rebuild-alloc error: $($_.Exception.Message)" }
Check "rebuild-alloc accepted" $rb

# 3b) immediately after recovery: reconcile must equal baseline (no half-write residue)
$recNow = Recon
Log "post-recovery pre-order reconcile=$recNow (baseline=$baseRec)"
Check "reconcile back to baseline immediately after recovery" ($recNow -eq $baseRec)

# 4) recovery assertions: fresh login (old token lost with redis) -> order ok -> reconcile zero
Start-Sleep -Seconds 2
$user1b = LoginUser "13800000003"
$H1b = @{ "X-User-Token" = $user1b; "Idempotency-Key" = [guid]::NewGuid().ToString() }
$takeOk = $false
try {
  $tk = Invoke-RestMethod -Method Post "$server/user/order" -Headers $H1b -ContentType "application/json" -Body '{"type":"TAKE"}' -TimeoutSec 15
  $takeOk = ($tk.data.orderNo -ne $null)
  Log "post-recovery order: $($tk.data.orderNo) status=$($tk.data.status)"
} catch { Log "post-recovery order error: $($_.Exception.Message)" }
Check "post-recovery order created" $takeOk
$recEnd = Recon
Log "post-recovery reconcile=$recEnd (baseline=$baseRec)"
Check "post-recovery reconcile back to baseline" ($recEnd -eq $baseRec)

if ($script:fail -eq 0) { Log "BATCH19-REDIS PASS"; exit 0 } else { Log "BATCH19-REDIS FAIL checks=$($script:fail)"; exit 1 }
