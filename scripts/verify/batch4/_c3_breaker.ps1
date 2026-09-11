# S3.8 gate: circuit breaker + bulkhead (WP3) - device downlink resilience
# Prereq: server :8400 started with actuator exposure including circuitbreakers/circuitbreakerevents;
#         sim :8500 running; swap.dev.enabled=true (dev downlink endpoint)
# Evidence: sim down -> CB OPEN with NOT_PERMITTED fast-fail; sim up -> half-open probe -> CLOSED
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$sim = "http://127.0.0.1:8500"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_c3_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S3.8 gate: circuit breaker ==" -Encoding UTF8

function Get-CbState {
    try {
        $r = Invoke-RestMethod "$server/actuator/circuitbreakers" -TimeoutSec 5
        return $r.circuitBreakers.deviceDownlink.state
    } catch { return "UNKNOWN" }
}
function Get-CbEvents {
    try { return (Invoke-RestMethod "$server/actuator/circuitbreakerevents" -TimeoutSec 5).circuitBreakerEvents } catch { return @() }
}
function Fire-Open($cellNo) {
    try {
        Invoke-RestMethod -Method Post "$server/dev/device/open?cabinetNo=SWAP-C-001&cellNo=$cellNo" -TimeoutSec 10 | Out-Null
        return 200
    } catch {
        return $_.Exception.Response.StatusCode.value__
    }
}

$state0 = Get-CbState
Log "initial CB state=$state0"
Check "initial state CLOSED" ($state0 -eq "CLOSED")

# --- 1) sim down -> failures trip the breaker ---
$simPids = Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -match "swap-sim-1.0.0.jar" } | Select-Object -ExpandProperty ProcessId
Log "stopping sim pids=$($simPids -join ',')"
foreach ($procId in $simPids) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 3

$failed = 0
foreach ($cell in 1..6) { if ((Fire-Open $cell) -ne 200) { $failed++ } }
Log "downlink attempts while sim down: failed=$failed/6"
Check "downlink failures observed" ($failed -ge 5)

$state1 = Get-CbState
Check "circuit breaker OPEN after failures" ($state1 -eq "OPEN")

# --- 2) open state -> fast fail without touching device ---
Fire-Open 7 | Out-Null
$events = @(Get-CbEvents)
$notPermitted = @($events | Where-Object { $_.type -eq "NOT_PERMITTED" -and $_.circuitBreakerName -eq "deviceDownlink" })
Log "NOT_PERMITTED events=$($notPermitted.Count)"
Check "fast-fail recorded (NOT_PERMITTED event)" ($notPermitted.Count -ge 1)

# --- 3) sim up -> automatic half-open probe -> CLOSED ---
Log "restarting sim"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$local\run-sim-dual.bat`"" -WindowStyle Hidden
$simUp = $false
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    try { $h = Invoke-RestMethod "$sim/actuator/health" -TimeoutSec 2; if ($h.status -eq "UP") { $simUp = $true; break } } catch { }
    Start-Sleep -Seconds 2
}
Check "sim back UP" $simUp

$closed = $false
$deadline = (Get-Date).AddSeconds(40)
$cell = 2
while ((Get-Date) -lt $deadline) {
    $state = Get-CbState
    if ($state -eq "CLOSED") { $closed = $true; break }
    if ($state -eq "HALF_OPEN") { Fire-Open $cell | Out-Null; $cell++ }
    Start-Sleep -Seconds 2
}
Log "final CB state=$(Get-CbState)"
Check "circuit breaker recovered to CLOSED" $closed

if ($script:fail -eq 0) { Log "GATE-BREAKER PASS"; exit 0 } else { Log "GATE-BREAKER FAIL checks=$($script:fail)"; exit 1 }
