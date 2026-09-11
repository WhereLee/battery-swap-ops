# S3.8 gate: Redis ops baseline (WP8) - big keys / slowlog / persistence / memory
# Prereq: local Redis (F:\Redis, port 6379); data volume small (dev)
# Evidence: RDB persistence OK, slowlog readable, big-key scan completes, memory snapshot recorded.
$ErrorActionPreference = "Continue"
$redisCli = "F:\Redis\redis-cli.exe"
$out = Join-Path $PSScriptRoot "_c6_out.txt"
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) {
    if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" }
}
function Redis($a) { return (& $redisCli @a 2>&1) -join "`n" }
Set-Content -Path $out -Value "== S3.8 gate: Redis ops baseline ==" -Encoding UTF8

Check "redis reachable (PING)" ((Redis @("PING")) -match "PONG")

# ---- persistence status ----
$persistence = Redis @("INFO", "persistence")
$rdbOk = $persistence -match "rdb_last_bgsave_status:ok"
$rdbConfigured = (Redis @("CONFIG", "GET", "save")) -notmatch "save\r?\n?\s*$"
Log "rdb_bgsave_ok=$rdbOk appendonly=$((Redis @("CONFIG", "GET", "appendonly")) -replace "`n", " ")"
Check "RDB last bgsave status ok" $rdbOk

# ---- memory snapshot ----
$memory = Redis @("INFO", "memory")
$usedLine = ($memory -split "`n" | Where-Object { $_ -match "^used_memory_human:" }) -join ""
$policyLine = (Redis @("CONFIG", "GET", "maxmemory-policy")) -join " "
Log "memory: $usedLine; $policyLine"

# ---- key volume by namespace (scan, bounded) ----
$swapKeys = & $redisCli --scan --pattern "swap:*" 2>$null
$keyCount = @($swapKeys).Count
Log "swap:* keys (scanned, bounded sample) = $keyCount"

# ---- big keys scan (offline-safe for small dev data) ----
$bigKeys = Redis @("--bigkeys")
$bigDone = $bigKeys -match "Sampled" -or $bigKeys -notmatch "error"
Log "bigkeys scan completed=$bigDone"
Check "big keys scan runs" $bigDone

# ---- slowlog ----
$slowLen = Redis @("SLOWLOG", "LEN")
Log "slowlog length = $slowLen"
Check "slowlog readable" ($slowLen -match "^\d+")

# ---- hotkeys probe (may be unavailable without LFU) ----
$hot = Redis @("--hotkeys")
$hotAvailable = $hot -notmatch "maxmemory-policy"
Log "hotkeys available = $hotAvailable (LFU required; otherwise noted)"

if ($script:fail -eq 0) { Log "GATE-REDIS-OPS PASS"; exit 0 } else { Log "GATE-REDIS-OPS FAIL checks=$($script:fail)"; exit 1 }
