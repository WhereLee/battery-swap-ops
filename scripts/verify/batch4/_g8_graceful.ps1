# S3.8 gate: graceful shutdown evidence (WP0)
# Prereq: server :8400 started with --management.endpoint.shutdown.enabled=true
# Evidence: actuator shutdown accepted, port down within budget, log shows
#           "Commencing graceful shutdown" + "Graceful shutdown complete" (scheduling awaited)
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$local = Join-Path $PSScriptRoot "..\..\..\.local"
$out = Join-Path $PSScriptRoot "_g8_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
function Read-LogText($path) {
    if (!(Test-Path $path)) { return "" }
    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try { $sr = New-Object System.IO.StreamReader($fs); return $sr.ReadToEnd() } finally { $sr.Close(); $fs.Close() }
}
Set-Content -Path $out -Value "== S3.8 gate: graceful shutdown ==" -Encoding UTF8

$before = Read-LogText (Join-Path $local "server.out.log")
try {
    $r = Invoke-RestMethod -Method Post "$server/actuator/shutdown" -ContentType "application/json" -Body "{}" -TimeoutSec 10
    Check "actuator shutdown accepted" ($r -ne $null)
} catch { Check "actuator shutdown accepted" $false }

$down = $false
$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline) {
    if (-not (Get-NetTCPConnection -State Listen -LocalPort 8400 -ErrorAction SilentlyContinue)) { $down = $true; break }
    Start-Sleep -Seconds 1
}
Check "port 8400 released within 40s" $down

$after = Read-LogText (Join-Path $local "server.out.log")
$tail = $after.Substring([Math]::Max(0, $after.Length - 20000))
Check "log: Commencing graceful shutdown" ($tail -match "Commencing graceful shutdown")
Check "log: Graceful shutdown complete" ($tail -match "Graceful shutdown complete")

if ($script:fail -eq 0) { Log "GATE-8 PASS"; exit 0 } else { Log "GATE-8 FAIL checks=$($script:fail)"; exit 1 }
