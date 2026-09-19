# =============================================================================
# _c36_xff.ps1 - batch33 gate: login brute-force limiter cannot be bypassed by header
#
# Why this script exists:
#   The admin login endpoint's only brute-force defence is @RateLimit(dimension = IP).
#   The old implementation derived the bucket key from the LEFTMOST X-Forwarded-For
#   entry, which the client fully controls: rotating that header per request hands the
#   attacker a fresh token bucket every time, so "5 per 5s" never triggers. This script
#   fires N bad-credential logins, each with a different forged XFF, and asserts the
#   throttle STILL engages - i.e. all attempts landed in one bucket.
#
# What it proves:
#   1. rotating X-Forwarded-For does not evade the limiter (>=1 HTTP 429 within the burst)
#   2. the limiter is a window, not a lockout: after the window it admits traffic again
#   3. XFF does not appear in the decision at all when no trusted proxy is configured
#      (the platform is started with the default empty trusted-proxies list)
#
# Conventions: English only; no secrets printed; the password used here is deliberately
# wrong so the run never creates a valid session. Token file is not needed (login is
# whitelisted in the security chain).
# =============================================================================

$ErrorActionPreference = "Continue"
$base = "http://127.0.0.1:8400/api"
$script:pass = 0
$script:fail = 0

function Check([string]$name, [bool]$cond, [string]$detail) {
    if ($cond) { $script:pass++; Write-Output "PASS  $name ($detail)" }
    else { $script:fail++; Write-Output "FAIL  $name ($detail)" }
}

function Try-Login([string]$forgedIp) {
    $body = '{"username":"admin","password":"definitely-not-the-password"}'
    $headers = @{ "X-Forwarded-For" = $forgedIp }
    try {
        $r = Invoke-WebRequest "$base/admin/auth/login" -Method Post -Headers $headers `
            -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 10
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode.value__ }
        return -1
    }
}

Write-Output "=== C36 batch33: login limiter vs forged X-Forwarded-For ==="

try {
    $health = Invoke-WebRequest "$base/actuator/health" -UseBasicParsing -TimeoutSec 10
    Check "P00 platform health UP" ($health.StatusCode -eq 200) "status=$($health.StatusCode)"
} catch {
    Write-Output "FATAL platform not reachable at $base"
    Write-Output "GATE-XFF FAIL"
    exit 1
}

# The limiter is 5 permits / 5s on this endpoint (AdminAuthController#login).
$burst = 10
$codes = @()
$started = Get-Date
for ($i = 1; $i -le $burst; $i++) {
    # Every attempt forges a DIFFERENT client address: under the old implementation this
    # was a new bucket per request, so no 429 could ever appear.
    $codes += Try-Login ("203.0.113.{0}" -f $i)
}
$elapsed = ((Get-Date) - $started).TotalMilliseconds
$throttled = @($codes | Where-Object { $_ -eq 429 }).Count

Write-Output ("INFO  burst codes: {0} (in {1:N0} ms)" -f ($codes -join ","), $elapsed)
Check "P01 burst all reached the login endpoint (no transport errors)" `
    ((@($codes | Where-Object { $_ -eq -1 }).Count) -eq 0) "codes=$($codes -join ',')"
Check "P02 rotating X-Forwarded-For does NOT evade the limiter (throttled=$throttled of $burst)" `
    ($throttled -ge 1) "throttled=$throttled"
Check "P03 brute-force attempts beyond the permit count are all rejected" `
    ($throttled -ge ($burst - 5)) "expected>=$($burst - 5) throttled=$throttled"

# Window semantics: a burst must not turn into a permanent lockout for the real operator.
Write-Output "INFO  waiting 6s for the 5s window to refill..."
Start-Sleep -Seconds 6
$afterWindow = Try-Login "198.51.100.77"
Check "P04 limiter is a window, not a lockout (admits traffic again after the window)" `
    ($afterWindow -ne 429) "code=$afterWindow"

Write-Output ""
Write-Output "=== C36 summary: PASS=$($script:pass) FAIL=$($script:fail) ==="
if ($script:fail -gt 0) {
    Write-Output "GATE-XFF FAIL"
    exit 1
}
Write-Output "GATE-XFF PASS"
