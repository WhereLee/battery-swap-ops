# batch21 P1-7: parameter validation -> unified HTTP 400 for bad input; legal requests pass through.
# Prereq: server :8400 (load profile) up; admin token at .local/admin-token.txt
$ErrorActionPreference = "Continue"
$server = "http://127.0.0.1:8400/api"
$out = Join-Path $PSScriptRoot "_c25_out.txt"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$adminToken = ((Get-Content (Join-Path $repo ".local\admin-token.txt") -Raw).Trim())
$AH = @{ "X-Admin-Token" = $adminToken }
$script:fail = 0
function Log($m) { $l = "$(Get-Date -Format HH:mm:ss) $m"; Write-Host $l; Add-Content -Path $out -Value $l -Encoding UTF8 }
function Check($name, $cond) { if ($cond) { Log "PASS $name" } else { $script:fail++; Log "FAIL $name" } }

# Raw HTTP post returning {code, body}; body decoded as UTF-8 (china-proof) via temp file / stream reader.
function PostJson($url, $headers, $body) {
    $tmp = Join-Path $env:TEMP ("c25_" + [guid]::NewGuid().ToString("N") + ".json")
    try {
        $r = Invoke-WebRequest -Method Post $url -Headers $headers -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 10 -OutFile $tmp
        $b = Get-Content $tmp -Raw -Encoding UTF8
        $code = 200
        try { $sc = $r.StatusCode; if ($sc) { $code = [int]$sc } } catch { }
        return @{ code = $code; body = [string]$b }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $b = ""
            try { $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8); $b = $sr.ReadToEnd() } catch { $b = $_.Exception.Message }
            return @{ code = [int]$resp.StatusCode; body = [string]$b }
        }
        return @{ code = -1; body = $_.Exception.Message }
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}
Set-Content -Path $out -Value "== batch21: P1-7 validation ==" -Encoding UTF8

$userToken = (Invoke-RestMethod -Method Post "$server/user/login" -ContentType "application/json" -Body '{"phone":"13800000003"}' -TimeoutSec 5).data.token
$UH = @{ "X-User-Token" = $userToken }
Log "user 13800000003 login ok"

# 1) create order: blank type -> bean validation 400 + unified message
$r = PostJson "$server/user/order" ($UH + @{ "Idempotency-Key" = "c25-blank-type" }) '{"type":"","cabinetNo":"SWAP-C-001"}'
Log "blank type -> http $($r.code)"
Check "blank type rejected (400)" ($r.code -eq 400)
Check "blank type unified validation message" ($r.body -match "参数校验失败")

# 2) create order: illegal enum value -> 400
$r = PostJson "$server/user/order" ($UH + @{ "Idempotency-Key" = "c25-bad-type" }) '{"type":"FOO"}'
Log "illegal type -> http $($r.code)"
Check "illegal type rejected (400)" ($r.code -eq 400)
Check "illegal type unified validation message" ($r.body -match "参数校验失败")

# 3) create order: malformed json -> 400 with json message
$r = PostJson "$server/user/order" ($UH + @{ "Idempotency-Key" = "c25-bad-json" }) '{not-json'
Log "malformed json -> http $($r.code)"
Check "malformed json rejected (400)" ($r.code -eq 400)
Check "malformed json message mentions JSON" ($r.body -match "JSON")

# 4) recharge: zero / negative / missing amount -> 400
$r = PostJson "$server/user/wallet/recharge" $UH '{"amountFen":0}'
Log "recharge zero -> http $($r.code)"
Check "recharge zero rejected (400)" ($r.code -eq 400)
Check "recharge zero unified validation message" ($r.body -match "参数校验失败")

$r = PostJson "$server/user/wallet/recharge" $UH '{"amountFen":-5}'
Log "recharge negative -> http $($r.code)"
Check "recharge negative rejected (400)" ($r.code -eq 400)

$r = PostJson "$server/user/wallet/recharge" $UH '{}'
Log "recharge missing amount -> http $($r.code)"
Check "recharge missing amount rejected (400)" ($r.code -eq 400)

# 5) admin refund: request param @Min violation -> 400 (ConstraintViolation path)
$r = PostJson "$server/admin/refund/SWAP-NO-EXIST25?amountFen=0" $AH '{}'
Log "refund amountFen=0 -> http $($r.code)"
Check "refund param 0 rejected (400)" ($r.code -eq 400)
Check "refund param 0 unified validation message" ($r.body -match "参数校验失败")

# 6) legal requests must pass validation and reach business layer
$r = PostJson "$server/user/wallet/recharge" $UH '{"amountFen":100}'
Log "valid recharge -> http $($r.code)"
Check "valid recharge passes (200)" ($r.code -eq 200)
Check "valid recharge business code=0" ($r.body -match '"code":0')

$r = PostJson "$server/user/order" $UH '{"type":"TAKE"}'
Log "valid form w/o idem-key -> http $($r.code)"
Check "valid order form reaches business layer (idem-key business error, not validation)" ($r.code -eq 400 -and $r.body -match "幂等")

$r = PostJson "$server/admin/refund/SWAP-NO-EXIST25" $AH '{}'
Log "refund w/o amount -> http $($r.code)"
Check "refund w/o amount reaches business layer (order-not-found, not validation)" ($r.code -eq 400 -and $r.body -match "订单不存在")

if ($script:fail -eq 0) { Log "BATCH21-VALIDATION PASS"; exit 0 } else { Log "BATCH21-VALIDATION FAIL checks=$($script:fail)"; exit 1 }
