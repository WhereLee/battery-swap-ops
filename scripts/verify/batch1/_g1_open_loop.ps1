# S1 gate script: open-cell loop (platform -> cabinet -> event -> command arrived)
# Prereq: swap-server on 8400 (swap.dev.enabled=true), swap-sim on 8500 (dev-enabled=true), SWAP_DEV_SECRET set for both
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$cabinetNo = "SWAP-C-001"
$cellNo = 1
$out = Join-Path $PSScriptRoot "_g1_out.txt"
function Log($msg) { $line = "$(Get-Date -Format HH:mm:ss) $msg"; Write-Host $line; Add-Content -Path $out -Value $line -Encoding UTF8 }
Set-Content -Path $out -Value "== S1 gate: open-cell loop ==" -Encoding UTF8

Log "health-server  $((Invoke-RestMethod "$server/actuator/health" -TimeoutSec 5).status)"
Log "health-sim     $((Invoke-RestMethod "http://127.0.0.1:8500/actuator/health" -TimeoutSec 5).status)"

$before = Invoke-RestMethod "$server/dev/device/cabinet?cabinetNo=$cabinetNo" -TimeoutSec 5
Log "online=$($before.data.online) lastEventSeq=$($before.data.lastEventSeq) cells=$($before.data.cells.Count)"

$open = Invoke-RestMethod -Method Post "$server/dev/device/open?cabinetNo=$cabinetNo&cellNo=$cellNo" -TimeoutSec 10
$seq = $open.data.commandSeq
Log "open sent: commandSeq=$seq traceId=$($open.data.traceId)"

$status = -1
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    $cmd = Invoke-RestMethod "$server/dev/device/command?cabinetNo=$cabinetNo&seq=$seq" -TimeoutSec 5
    $status = $cmd.data.commandStatus
    if ($status -eq 2) { break }
}
Log "command status=$status (expect 2=ARRIVED)"

$onlineCount = 0
1..10 | ForEach-Object {
    $no = "SWAP-C-" + $_.ToString("000")
    try { $r = Invoke-RestMethod "$server/dev/device/cabinet?cabinetNo=$no" -TimeoutSec 5; if ($r.data.online) { $onlineCount++ } } catch {}
}
Log "online cabinets=$onlineCount/10 (expect 10)"

$after = Invoke-RestMethod "$server/dev/device/cabinet?cabinetNo=$cabinetNo" -TimeoutSec 5
Log "after lastEventSeq=$($after.data.lastEventSeq) lastBootId=$($after.data.lastBootId)"
$cell = $after.data.cells | Where-Object { $_.cellNo -eq $cellNo }
Log "cell#$cellNo status=$($cell.status) batteryStatus=$($cell.batteryStatus) (door open does not change physical state)"

# 新代际（进程重启）事件序从 1 重计：推进判据 = bootId 变化 或 事件序递增
$advanced = ($after.data.lastBootId -ne $before.data.lastBootId) -or ($after.data.lastEventSeq -gt $before.data.lastEventSeq)
Log "sequence advanced=$advanced (bootId changed or eventSeq increased)"

if ($status -eq 2 -and $advanced -and $onlineCount -eq 10) {
    Log "GATE-1 PASS"
    exit 0
} else {
    Log "GATE-1 FAIL"
    exit 1
}
