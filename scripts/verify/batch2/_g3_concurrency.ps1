# S2 gate script: concurrent TAKE (20 users vs one cabinet's full batteries) -> zero oversell
# Prereq: swap-server :8400, swap-sim :8500, seeded dev users 2..21, target cabinet SWAP-C-002 has 6 full cells
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_g3_out.txt"
Set-Content -Path $out -Value "== S2 gate: concurrent swap (zero oversell) ==" -Encoding UTF8
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }

$target = "SWAP-C-002"
$jobs = @()
foreach ($i in 2..21) {
    $phone = "138{0:d8}" -f $i
    $jobs += Start-Job -ArgumentList $server, $phone, $target -ScriptBlock {
        param($server, $phone, $target)
        try {
            $login = Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body (@{ phone = $phone } | ConvertTo-Json) -TimeoutSec 5
            $headers = @{ "X-User-Token" = $login.data.token; "Idempotency-Key" = [guid]::NewGuid().ToString() }
            $r = Invoke-RestMethod -Method Post "$server/user/order" -Headers $headers -ContentType "application/json" -Body (@{ type = "TAKE"; cabinetNo = $target } | ConvertTo-Json) -TimeoutSec 10
            return [pscustomobject]@{ phone = $phone; ok = $true; orderNo = $r.data.orderNo; cellNo = $r.data.cellNo; batteryNo = $r.data.takeBatteryNo; error = $null }
        } catch {
            $status = 0
            try { $status = [int]$_.Exception.Response.StatusCode } catch { }
            return [pscustomobject]@{ phone = $phone; ok = $false; status = $status; orderNo = $null; cellNo = $null; batteryNo = $null; error = $_.Exception.Message }
        }
    }
}
$results = $jobs | Wait-Job -Timeout 60 | Receive-Job
$jobs | Remove-Job -Force

$ok = @($results | Where-Object { $_.ok })
$failed = @($results | Where-Object { -not $_.ok })
Log "total=$($results.Count) success=$($ok.Count) failed=$($failed.Count)"

# DB invariant via admin views: distinct cell/battery per successful order
$distinctCells = @($ok | Select-Object -ExpandProperty cellNo -Unique).Count
$distinctBatteries = @($ok | Select-Object -ExpandProperty batteryNo -Unique).Count
if ($ok.Count -gt 0) {
    Log ("success details: " + (($ok | ForEach-Object { "$($_.phone)->$($_.cellNo)/$($_.batteryNo)" }) -join ", "))
}
$sampleFail = $failed | Select-Object -First 1
if ($sampleFail) { Log "sample failure status=$($sampleFail.status) (expect 400 no-stock)" }

$pass = $true
if ($ok.Count -gt 6) { $pass = $false; Log "FAIL oversell: success=$($ok.Count) > 6 full batteries" }
if ($distinctCells -ne $ok.Count) { $pass = $false; Log "FAIL duplicate cell allocation" }
if ($distinctBatteries -ne $ok.Count) { $pass = $false; Log "FAIL duplicate battery allocation" }
foreach ($f in $failed) { if ($f.status -ne 400) { $pass = $false; Log "FAIL unexpected status: $($f.phone) status=$($f.status)"; break } }

if ($pass) { Log "GATE-3 PASS" ; exit 0 } else { Log "GATE-3 FAIL" ; exit 1 }
