# =============================================================================
# _c38_ui_probe.ps1 - batch38 wrapper: instrumented layout/contrast probe
#
# Same shape as _c35: serve the real build output with `vite preview`, drive the locally
# installed headless Chrome over raw CDP, then measure DOM layout/contrast facts (table
# horizontal scroll, clipped text, tag contrast, viewport/document size) instead of
# eyeballing screenshots. Evidence: _c38_out.txt (probe output) + _c38_report.json.
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

$previewLog = Join-Path $local "b38-preview.log"
$previewErr = Join-Path $local "b38-preview.err.log"
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

    $raw = & node (Join-Path $scriptDir "_c38_ui_probe.mjs") 2>&1
    $code = $LASTEXITCODE
    $raw | ForEach-Object { $_ }
    # Same contract as _c35: the wrapper owns the evidence file, so the record and the script
    # that produced it can never drift apart.
    [System.IO.File]::WriteAllLines((Join-Path $scriptDir "_c38_out.txt"), ($raw | ForEach-Object { [string]$_ }), (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "INFO evidence written: scripts/verify/batch31/_c38_out.txt"

    $jsonLines = @()
    $inJson = $false
    foreach ($line in $raw) {
        if ($line -eq "JSON-BEGIN") { $inJson = $true; continue }
        if ($line -eq "JSON-END") { $inJson = $false; continue }
        if ($inJson) { $jsonLines += $line }
    }
    if ($jsonLines.Count -gt 0) {
        [System.IO.File]::WriteAllText((Join-Path $scriptDir "_c38_report.json"), ($jsonLines -join "`n"), (New-Object System.Text.UTF8Encoding($false)))
        Write-Output "INFO report written: scripts/verify/batch31/_c38_report.json"
    }
    Write-Output "INFO _c38_ui_probe.mjs exit code = $code"
    exit $code
} finally {
    if ($preview -and -not $preview.HasExited) { Stop-Process -Id $preview.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
}
