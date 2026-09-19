# =============================================================================
# _c40_ui_negative_control.ps1 - batch43 wrapper: prove the UI gate can fail
#
# Same shape as the other browser gates: serve the real build output with `vite preview`, drive
# the locally installed headless Chrome over raw CDP, then assert that each deliberately
# injected defect is DETECTED by the gate's own probe code. Evidence is written by this script
# (the batch38 lesson: a gate whose record is produced by hand cannot be reproduced).
# =============================================================================

$ErrorActionPreference = "Continue"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $scriptDir "..\..\..")
$web = Join-Path $repo "swap-web"
$local = Join-Path $repo ".local"
$dist = Join-Path $web "dist"

if (-not (Test-Path -LiteralPath (Join-Path $dist "index.html"))) {
    Write-Output "FATAL swap-web/dist/index.html missing - run: cd swap-web; npm run build"
    exit 1
}

$passFile = Join-Path $local "admin-pass.txt"
if (-not (Test-Path -LiteralPath $passFile)) { Write-Output "FATAL .local/admin-pass.txt missing"; exit 1 }
$env:ADMIN_PASS = (Get-Content -LiteralPath $passFile -Raw).Trim()
$env:ADMIN_USER = "admin"
$env:APP_URL = "http://127.0.0.1:4173"

$previewLog = Join-Path $local "b43-preview.log"
$previewErr = Join-Path $local "b43-preview.err.log"
$preview = Start-Process -FilePath "node" `
    -ArgumentList "node_modules/vite/bin/vite.js", "preview", "--port", "4173", "--strictPort", "--host", "127.0.0.1" `
    -WorkingDirectory $web -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $previewLog -RedirectStandardError $previewErr

try {
    $up = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            if ((Invoke-WebRequest "http://127.0.0.1:4173/" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200) { $up = $true; break }
        } catch { }
    }
    if (-not $up) { Write-Output "FATAL vite preview did not come up on :4173 (see $previewLog)"; exit 1 }
    Write-Output "INFO preview server serving dist/ on http://127.0.0.1:4173"

    $lines = & node (Join-Path $scriptDir "_c40_ui_negative_control.mjs") 2>&1
    $code = $LASTEXITCODE
    $lines | ForEach-Object { $_ }
    [System.IO.File]::WriteAllLines((Join-Path $scriptDir "_c40_out.txt"), ($lines | ForEach-Object { [string]$_ }), (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "INFO evidence written: scripts/verify/batch31/_c40_out.txt"
    Write-Output "INFO _c40_ui_negative_control.mjs exit code = $code"
    exit $code
} finally {
    if ($preview -and -not $preview.HasExited) { Stop-Process -Id $preview.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
}
