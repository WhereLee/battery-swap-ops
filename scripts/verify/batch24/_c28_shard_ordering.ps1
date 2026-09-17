# batch24 P0-2 sharded ordering drill (RocketMQ 5 FIFO message group per cabinet)
#
# Form under test:
#   sender  : MqEventReporter sets message group = cabinetNo (same cabinet -> same queue, strict FIFO delivery)
#   consumer: DeviceEventMqConsumer shards by cabinet onto fixed workers (same cabinet serial, cross cabinet parallel)
#   invariant: ordering guarantee narrowed from "global" to "per cabinet" (seq guard already keys on cabinetNo+bootId)
#
# Prereq:
#   platform 8400 running in MQ mode (.local\start-server-mq.ps1), RocketMQ proxy 8081 up,
#   sim stopped (HTTP channel must not move ledgers during the drill),
#   topic swap-device-event migrated to FIFO:
#     mqadmin updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t swap-device-event -a +message.type=FIFO
#   .local\dev-secret.txt present (event signing, zero plaintext in repo)
#
# Evidence: injector output (3 scenarios) + platform log (parallel workers / both guards rejecting) + ledger final state

$ErrorActionPreference = "Stop"
$rep  = "c:\Users\lrs\Desktop\py\interview\battery-swap-ops"
$plat = "http://127.0.0.1:8400/api"
$log  = "$rep\.local\server-mq.out.log"
$out  = "$rep\scripts\verify\batch24\_c28_out.txt"
$inj  = "$rep\scripts\verify\batch24\_c28_injector.log"

$script:pass = 0
$script:fail = 0
$script:lines = New-Object System.Collections.ArrayList

function Check([string]$name, [bool]$ok, [string]$detail) {
  if ($ok) { $script:pass = $script:pass + 1; [void]$script:lines.Add("PASS  $name  --  $detail") }
  else     { $script:fail = $script:fail + 1; [void]$script:lines.Add("FAIL  $name  --  $detail") }
}

# log file is held by the running JVM -> open with FileShare.ReadWrite
function Read-Shared([string]$path) {
  $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
  try {
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
    $txt = $sr.ReadToEnd()
    $sr.Close()
  } finally { $fs.Close() }
  return $txt
}

# ---------- 1. prerequisites ----------
$ledger = $null
try { $ledger = Invoke-RestMethod "$plat/dev/device/cabinet?cabinetNo=SWAP-C-003" -TimeoutSec 5 } catch { $ledger = $null }
Check "prereq.platform-dev-ledger" ($null -ne $ledger -and $ledger.code -eq 0) "dev ledger endpoint reachable on 8400"

$proxyUp = (Test-NetConnection -ComputerName 127.0.0.1 -Port 8081 -WarningAction SilentlyContinue).TcpTestSucceeded
Check "prereq.rocketmq-proxy" $proxyUp "proxy port 8081 open"

$simUp = (Test-NetConnection -ComputerName 127.0.0.1 -Port 8500 -WarningAction SilentlyContinue).TcpTestSucceeded
Check "prereq.sim-stopped" (-not $simUp) "sim stopped so the HTTP channel cannot move ledgers mid-drill"

$secret = (Get-Content "$rep\.local\dev-secret.txt" -Raw).Trim()
Check "prereq.signing-secret" ($secret.Length -eq 32) "SWAP_DEV_SECRET available (32 hex, env only)"

$logBefore = @((Read-Shared $log) -split "`n")
$startLines = $logBefore.Count
Check "prereq.consumer-sharded-form" (($logBefore -join "`n") -match "workers=4 batch=16") "consumer started sharded (workers=4 batch=16)"

# ---------- 2. run the injector (MQ FIFO group per cabinet, 3 scenarios) ----------
$env:SWAP_DEV_SECRET = $secret
Push-Location $rep
& mvn -pl swap-server -am test "-Dtest=ShardOrderingSpike" "-Dsurefire.failIfNoSpecifiedTests=false" 2>&1 |
  Out-File -FilePath $inj -Encoding UTF8
Pop-Location
$injTxt = Read-Shared $inj

Check "injector.s1-out-of-order" ($injTxt -match "\[S1\] PASS") "same-cabinet old seq rejected, ledger did not regress"
Check "injector.s2-cross-boot-replay" ($injTxt -match "\[S2\] PASS") "replayed old bootId rejected even with a larger seq"
Check "injector.s3-multi-cabinet" ($injTxt -match "\[S3\] PASS") "2 cabinets x 20 interleaved events all accepted, no cross-talk"
Check "injector.junit-green" ($injTxt -match "Tests run: 3, Failures: 0") "injector JUnit 3/3 green"

# ---------- 3. platform log evidence ----------
$logAfter = @((Read-Shared $log) -split "`n")
if ($logAfter.Count -gt $startLines) {
  $newTxt = ($logAfter[$startLines..($logAfter.Count - 1)]) -join "`n"
} else {
  $newTxt = ""
}

$workers = @([regex]::Matches($newTxt, "mq-worker-(\d)") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
Check "log.cross-cabinet-parallel" ($workers.Count -ge 2) ("distinct worker threads processed events: " + ($workers -join ","))

Check "log.seq-guard-rejected-old" ($newTxt -match "bootId=spike-s1-[^\r\n]*last=") "seq guard rejection line for the injected old seq"
Check "log.generation-guard-rejected-replay" ($newTxt -match "WARN[^\r\n]*bootId=spike-s2-old-") "generation guard rejection line for the replayed bootId"

# ---------- 4. ledger final state ----------
$a = Invoke-RestMethod "$plat/dev/device/cabinet?cabinetNo=SWAP-C-003" -TimeoutSec 5
$b = Invoke-RestMethod "$plat/dev/device/cabinet?cabinetNo=SWAP-C-004" -TimeoutSec 5
Check "ledger.c003-advanced" ($a.data.lastEventSeq -ge 100000) ("C-003 lastEventSeq=" + $a.data.lastEventSeq)
Check "ledger.c004-advanced" ($b.data.lastEventSeq -ge 100000) ("C-004 lastEventSeq=" + $b.data.lastEventSeq)
Check "ledger.generations-distinct" ($a.data.lastBootId -ne $b.data.lastBootId) ("C-003=" + $a.data.lastBootId + " / C-004=" + $b.data.lastBootId)

# ---------- 5. summary ----------
$total = $script:pass + $script:fail
$head = @(
  "batch24 P0-2 sharded ordering drill -- RocketMQ 5 FIFO message group per cabinet",
  ("run at      : " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss")),
  ("sender form : message group = cabinetNo (fifo-group=true)"),
  ("consumer    : shard by cabinet onto 4 workers, batch=16, invisible=30s"),
  ("invariant   : ordering narrowed from global to per-cabinet; seq guard keys on (cabinetNo, bootId)"),
  ("result      : PASS " + $script:pass + " / " + $total)
)
($head + $script:lines) | Set-Content -Path $out -Encoding UTF8
$head | ForEach-Object { Write-Host $_ }
$script:lines | ForEach-Object { Write-Host $_ }
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
