# S3 gate script: MQ reliable channel (S3.5) - dual channel, broker outage hold/resume
# Prereq: sim :8500 started with --swap.sim.event-channel=dual; server :8400 + broker/namesrv/proxy running
# Logs: .local/sim.out.log, .local/server.out.log (console captures)
# Evidence: same event on HTTP+Mq; broker down -> sender hold (WARN/ERROR); broker up -> resumed (INFO + consumed)
# Note: poison-message grading (ack-drop vs DLQ) is covered by DeviceEventMqConsumerTest unit tests.
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_g6_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S3 gate: MQ reliable channel ==" -Encoding UTF8

$simLog = Join-Path $local "sim.out.log"
$srvLog = Join-Path $local "server.out.log"

function Read-LogText($path) {
    if (!(Test-Path $path)) { return "" }
    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $sr = New-Object System.IO.StreamReader($fs)
        return $sr.ReadToEnd()
    } finally { $sr.Close(); $fs.Close() }
}
function Count-Matches($text, $pattern) {
    if ($text -eq "") { return 0 }
    return ([regex]::Matches($text, $pattern)).Count
}
function Pick-CabinetCell {
    foreach ($i in 1..10) {
        $cab = "SWAP-C-" + $i.ToString("000")
        $state = (Invoke-RestMethod "$server/dev/device/cabinet?cabinetNo=$cab" -TimeoutSec 5).data
        $cell = $state.cells | Where-Object { $_.batteryNo -ne $null -and $_.batteryNo -ne "" } | Select-Object -First 1
        if ($cell -ne $null) { return @{ cabinetNo = $cab; cellNo = $cell.cellNo; batteryNo = $cell.batteryNo } }
    }
    return $null
}

$mqSimBefore = Count-Matches (Read-LogText $simLog) "\[MQ"
$mqSrvBefore = Count-Matches (Read-LogText $srvLog) "\[MQ"

# ---- 1) dual channel: trigger one event, verify MQ delivered + HTTP delivered (same eventSeq) ----
$pick1 = Pick-CabinetCell
if ($pick1 -eq $null) { Log "no battery available; aborting dual check"; $script:fail++ }
else {
    Log "trigger event cabinet=$($pick1.cabinetNo) cellNo=$($pick1.cellNo) battery=$($pick1.batteryNo)"
    Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($pick1.cabinetNo)&cellNo=$($pick1.cellNo)" -TimeoutSec 5 | Out-Null
    $dualDeadline = (Get-Date).AddSeconds(20)
    $simText = Read-LogText $simLog
    $srvText = Read-LogText $srvLog
    while ((Get-Date) -lt $dualDeadline) {
        $simText = Read-LogText $simLog
        $srvText = Read-LogText $srvLog
        $mqSimAfter = Count-Matches $simText "\[MQ"
        $mqSrvAfter = Count-Matches $srvText "\[MQ"
        if ($mqSimAfter -gt $mqSimBefore -and $mqSrvAfter -gt $mqSrvBefore) { break }
        Start-Sleep -Seconds 2
    }
    Check "sim MQ activity increased" ($mqSimAfter -gt $mqSimBefore)
    Check "server MQ consume activity increased" ($mqSrvAfter -gt $mqSrvBefore)
    $mqLine = ([regex]::Matches($simText, "\[MQ[^\r\n]*eventSeq=\d+")) | Select-Object -Last 1
    if ($mqLine -ne $null) {
        $seq = ([regex]::Match($mqLine.Value, "eventSeq=(\d+)")).Groups[1].Value
        $sameSeq = Count-Matches $srvText "eventSeq=$seq"
        Log "latest MQ eventSeq=$seq server-side occurrences=$sameSeq (HTTP+Mq expected >=2)"
        Check "same event seen twice on server (dual paths)" ($sameSeq -ge 2)
    } else { Check "latest MQ line parsed" $false }
}

# ---- 2) broker outage -> hold; restart -> resume ----
$pick2 = Pick-CabinetCell
if ($pick2 -eq $null) { Log "no battery for outage test; aborting"; $script:fail++ }
else {
    $infoBefore = Count-Matches (Read-LogText $simLog) "(?m)^.*?INFO.*\[MQ"
    $brokerPids = Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'javaw?.exe' -and $_.CommandLine -match 'BrokerStartup' } | Select-Object -ExpandProperty ProcessId
    Log "stopping broker pids=$($brokerPids -join ',')"
    foreach ($procId in $brokerPids) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 5
    Invoke-RestMethod -Method Post "$sim/sim/battery/out?cabinetNo=$($pick2.cabinetNo)&cellNo=$($pick2.cellNo)" -TimeoutSec 5 | Out-Null
    Start-Sleep -Seconds 8
    $simDown = Read-LogText $simLog
    $holdEvidence = ([regex]::Matches($simDown, "(?m)^.*?(WARN|ERROR).*\[MQ")) | Select-Object -Last 1
    Check "sender holds event while broker down (WARN/ERROR MQ line)" ($holdEvidence -ne $null)
    Log "restarting broker"
    Start-Process -FilePath (Join-Path $local "start-broker.bat") -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds(90)
    $infoAfter = $infoBefore
    while ((Get-Date) -lt $deadline) {
        $infoAfter = Count-Matches (Read-LogText $simLog) "(?m)^.*?INFO.*\[MQ"
        if ($infoAfter -gt $infoBefore) { break }
        Start-Sleep -Seconds 5
    }
    Log "INFO MQ lines before=$infoBefore after=$infoAfter (held event must be delivered after restart)"
    Check "sender resumed after broker restart (new INFO MQ line, <=90s)" ($infoAfter -gt $infoBefore)
}

Log "done: this gate requires visual confirmation of ASCII tokens above; log excerpts kept in _out.txt"
if ($script:fail -eq 0) { Log "GATE-6 PASS"; exit 0 } else { Log "GATE-6 FAIL checks=$($script:fail)"; exit 1 }
