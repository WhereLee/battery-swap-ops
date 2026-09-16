# P0-3e JVM story: pressure a small heap (96m) under read load, capture GC/jstat, compare with 512m
# Launch via generated .bat (quoted -Xlog:gc:file= form is the proven pattern on Windows).
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$jmx = Join-Path $repo "scripts\verify\batch16\jmeter\swap-ops-read.jmx"
$out = Join-Path $PSScriptRoot "_p03e_out.txt"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$diag = Join-Path $repo ("..\diag-archive\p03e-jvm-" + $stamp)
New-Item -ItemType Directory -Path $diag -Force | Out-Null
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }
Set-Content -Path $out -Value "== P0-3e JVM story: 96m vs 512m heap (read load, 100 threads) ==" -Encoding UTF8

function Stop-Servers {
    Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like "*swap-server*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
}
function Start-HeapServer($heapMb, $tag) {
    $gc = Join-Path $diag ("gc-$tag.log")
    $bat = Join-Path $diag ("run-$tag.bat")
    $log = Join-Path $diag ("server-$tag.out.log")
    @"
@echo off
set /p SWAP_DEV_SECRET=<"$repo\.local\dev-secret.txt"
set /p SWAP_ADMIN_TOKEN=<"$repo\.local\admin-token.txt"
set /p SWAP_PAY_SECRET=<"$repo\.local\pay-secret.txt"
start "$tag" /b javaw -Xms${heapMb}m -Xmx${heapMb}m -Xlog:gc:file="$gc":time,uptime -jar "$repo\swap-server\target\swap-server-1.0.0.jar" --swap.dev.enabled=true --swap.ratelimit.enabled=false --swap.device.mq.enabled=false --swap.device.heartbeat-timeout-seconds=5 --swap.alarm.offline-scan-interval-ms=2000 > "$log" 2>&1
"@ | Set-Content -Path $bat -Encoding ASCII
    cmd /c $bat | Out-Null
    foreach ($i in 1..40) {
        Start-Sleep -Seconds 2
        try { if ((Invoke-RestMethod "http://127.0.0.1:8400/api/actuator/health" -TimeoutSec 3).status -eq "UP") { return $true } } catch { }
    }
    return $false
}
function PauseStats($gcFile) {
    if (-not (Test-Path $gcFile)) { return $null }
    $ms = @()
    foreach ($ln in (Select-String -Path $gcFile -Pattern "Pause" -ErrorAction SilentlyContinue)) {
        $m = [regex]::Match($ln.Line, "([0-9]+[.,][0-9]+)ms")
        if ($m.Success) { $ms += [double]($m.Groups[1].Value.Replace(",", ".")) }
    }
    if ($ms.Count -eq 0) { return $null }
    return [pscustomobject]@{ pauses = $ms.Count; sumMs = [Math]::Round(($ms | Measure-Object -Sum).Sum, 1); maxMs = [Math]::Round(($ms | Measure-Object -Maximum).Maximum, 1) }
}
function RunLoad($jmx, $threads, $duration, $tag, $diag2) {
    $jtl = Join-Path $diag2 ("read-$tag.jtl")
    & jmeter @("-n", "-t", $jmx, "-Jthreads=$threads", "-Jduration=$duration", "-Jramp=5", "-l", $jtl, "-j", (Join-Path $diag2 "read-$tag-jmeter.log")) 2>&1 |
        Out-File (Join-Path $diag2 "read-$tag-console.log") -Encoding UTF8
}

foreach ($heap in @(96, 512)) {
    Stop-Servers
    $tag = "heap$heap"
    Check "server started with ${heap}m" (Start-HeapServer $heap $tag)
    $pidSrv = (Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like "*swap-server*" } | Select-Object -First 1).ProcessId
    Log "${heap}m pid=$pidSrv"
    $jstatFile = Join-Path $diag ("jstat-$heap.txt")
    if ($heap -eq 96) {
        Start-Process -FilePath "D:\java\bin\jstat.exe" -ArgumentList "-gc", $pidSrv, "1000", "25" -RedirectStandardOutput $jstatFile -WindowStyle Hidden | Out-Null
    }
    RunLoad $jmx 100 45 $tag $diag
    $rows = Import-Csv (Join-Path $diag ("read-$tag.jtl"))
    $err = @($rows | Where-Object { $_.success -ne "true" }).Count
    $st = PauseStats (Join-Path $diag ("gc-$tag.log"))
    $alive = $false
    try { $alive = ((Invoke-RestMethod "http://127.0.0.1:8400/api/actuator/health" -TimeoutSec 3).status -eq "UP") } catch { }
    if ($st) { Log "${heap}m run: samples=$($rows.Count) err=$err alive=$alive GC pauses=$($st.pauses) sumMs=$($st.sumMs) maxMs=$($st.maxMs)" }
    else { Log "${heap}m run: samples=$($rows.Count) err=$err alive=$alive GC=not-parsed" }
    if ($heap -eq 96) {
        $script:st96 = $st; $script:err96 = $err; $script:alive96 = $alive
        $oom = (Select-String -Path (Join-Path $diag "server-heap96.out.log") -Pattern "OutOfMemoryError" -ErrorAction SilentlyContinue | Measure-Object).Count
        Log "96m OOM lines=$oom"
        Check "96m pressure signal (GC sumMs high or OOM or server unstable)" ($oom -gt 0 -or -not $alive -or ($st -and $st.sumMs -ge 1500))
    } else {
        $script:st512 = $st; $script:err512 = $err
        Check "512m run clean (0 http errors)" ($err -eq 0)
    }
}
Check "contrast captured (96m vs 512m GC stats)" ($null -ne $script:st96 -and $null -ne $script:st512)
if ($script:st96 -and $script:st512) {
    $ratio = [Math]::Round($script:st96.pauses / [Math]::Max(1, $script:st512.pauses), 1)
    Log "GC pause-count ratio (96m vs 512m, same 45s load): $ratio"
}
Log "raw evidence: $diag"
if ($script:fail -eq 0) { Log "P0-3e JVM PASS" ; exit 0 } else { Log "P0-3e JVM FAIL checks=$($script:fail)" ; exit 1 }
