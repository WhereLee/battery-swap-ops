# S3 gate script: replay / out-of-order / cross-generation guard (S3.1)
# Prereq: server :8400 with swap.dev.enabled=true; cabinet SWAP-C-001 registered; secret in .local/dev-secret.txt
# Evidence: accepted baseline, same-generation replay rejected, out-of-order rejected,
#           new generation accepted, cross-generation replay rejected (state unchanged).
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_g4_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
Set-Content -Path $out -Value "== S3 gate: replay/ordering/cross-generation ==" -Encoding UTF8

$secretPath = Join-Path $PSScriptRoot "..\..\..\.local\dev-secret.txt"
$secret = (Get-Content $secretPath -Raw).Trim()
$cabinet = "SWAP-C-001"

function Sign-Event($canonical) {
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    return (-join ($hash | ForEach-Object { $_.ToString("x2") }))
}

function Send-Event($bootId, $seq, $eventType = "DOOR_OPENED", $cellNo = 1) {
    $canonical = "$cabinet|$eventType|$cellNo||$bootId|$seq"
    $sign = Sign-Event $canonical
    $body = @{ cabinetNo = $cabinet; eventType = $eventType; cellNo = $cellNo; bootId = $bootId; eventSeq = $seq } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post "$server/device/event" -Headers @{ "X-Device-No" = $cabinet; "X-Device-Sign" = $sign } -ContentType "application/json" -Body $body -TimeoutSec 5 | Out-Null
        return $true
    } catch {
        Log "send failed status=$($_.Exception.Response.StatusCode.value__) msg=$($_.Exception.Message)"
        return $false
    }
}

function Get-State {
    return (Invoke-RestMethod "$server/dev/device/cabinet?cabinetNo=$cabinet" -TimeoutSec 5).data
}

$state0 = Get-State
$seq0 = if ($state0.lastEventSeq -ne $null) { [int64]$state0.lastEventSeq } else { 0L }
Log "baseline cabinetNo=$cabinet bootId=$($state0.lastBootId) lastEventSeq=$seq0"

$genA = "boot-a-" + (Get-Date -Format "HHmmss")
$genB = "boot-b-" + (Get-Date -Format "HHmmss")

# 1) baseline event accepted (new generation A)
Check "baseline event accepted" (Send-Event $genA ($seq0 + 1))
$s1 = Get-State
Check "state advanced to genA" ($s1.lastBootId -eq $genA -and [int64]$s1.lastEventSeq -eq ($seq0 + 1))

# 2) exact replay (same generation, same seq) must be idempotently rejected
Check "replay delivered (HTTP ok)" (Send-Event $genA ($seq0 + 1))
$s2 = Get-State
Check "same-generation replay rejected (state unchanged)" ([int64]$s2.lastEventSeq -eq ($seq0 + 1) -and $s2.lastBootId -eq $genA)

# 3) out-of-order (same generation, lower seq) rejected
Check "out-of-order delivered" (Send-Event $genA $seq0)
$s3 = Get-State
Check "out-of-order rejected (state unchanged)" ([int64]$s3.lastEventSeq -eq ($seq0 + 1))

# 4) new generation B accepted (any seq, order guard accepts boot change)
Check "new generation accepted" (Send-Event $genB 5000)
$s4 = Get-State
Check "state advanced to genB" ($s4.lastBootId -eq $genB -and [int64]$s4.lastEventSeq -eq 5000)

# 5) cross-generation replay: genA seen before (history), now bootId=genB -> must be rejected by S3.1 guard
Check "cross-generation replay delivered" (Send-Event $genA 6000)
$s5 = Get-State
Check "cross-generation replay rejected (state stays genB/5000)" ($s5.lastBootId -eq $genB -and [int64]$s5.lastEventSeq -eq 5000)

if ($script:fail -eq 0) { Log "GATE-4 PASS"; exit 0 } else { Log "GATE-4 FAIL checks=$($script:fail)"; exit 1 }
