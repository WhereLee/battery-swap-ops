# =============================================================================
# _c35_console.ps1 - batch31 gate wrapper: build output + real browser walkthrough
#
# Steps:
#   1. serve the real build output (swap-web/dist) with `vite preview` on :4173,
#      which reverse-proxies /api to the platform - the closest local stand-in for
#      the nginx deployment
#   2. run _c35_console.mjs: headless Chrome/Edge over raw CDP drives the login form
#      and walks every console page, failing on console errors, HTTP >= 400 and
#      missing rows
#   3. stop the preview server, tee everything into _c35_out.txt
#
# Credentials are read from .local (gitignored) and passed through the environment -
# they are never printed and never written into the evidence file.
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
$env:SHOT_DIR = Join-Path $local "b31-shots"

$previewLog = Join-Path $local "b31-preview.log"
$previewErr = Join-Path $local "b31-preview.err.log"
$preview = Start-Process -FilePath "node" `
    -ArgumentList "node_modules/vite/bin/vite.js", "preview", "--port", "4173", "--strictPort", "--host", "127.0.0.1" `
    -WorkingDirectory $web -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $previewLog -RedirectStandardError $previewErr

try {
    $up = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $r = Invoke-WebRequest "http://127.0.0.1:4173/" -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { $up = $true; break }
        } catch { }
    }
    if (-not $up) {
        Write-Output "FATAL vite preview did not come up on :4173 (see $previewLog)"
        exit 1
    }
    Write-Output "INFO preview server serving dist/ on http://127.0.0.1:4173"
    node (Join-Path $scriptDir "_c35_console.mjs")
    $code = $LASTEXITCODE
    Write-Output "INFO _c35_console.mjs exit code = $code"
    exit $code
} finally {
    if ($preview -and -not $preview.HasExited) { Stop-Process -Id $preview.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
}
