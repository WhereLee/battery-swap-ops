# battery-swap-ops cloud deployment orchestrator (local -> 124.223.36.154)
# Prereq: jars built (mvn package), .local/cloud-redis-pass.txt exists (gitignored),
#         ssh key auth to ubuntu@124.223.36.154 working.
# Secrets never printed; staging dir removed after transfer.
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$staging = Join-Path $env:TEMP "swapdeploy-stage"
$remote = "ubuntu@124.223.36.154"
$remoteTmp = "/tmp/swapdeploy"

function Stage-Files {
    if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
    New-Item -ItemType Directory -Path (Join-Path $staging "db") -Force | Out-Null
    Copy-Item (Join-Path $root "swap-server\target\swap-server-1.0.0.jar") $staging
    Copy-Item (Join-Path $root "swap-sim\target\swap-sim-1.0.0.jar") $staging
    Copy-Item (Join-Path $root "db\*.sql") (Join-Path $staging "db")
    Copy-Item (Join-Path $PSScriptRoot "swap-server.service") $staging
    Copy-Item (Join-Path $PSScriptRoot "swap-sim.service") $staging
    Copy-Item (Join-Path $PSScriptRoot "deploy-remote.sh") $staging
    Copy-Item (Join-Path $root ".local\cloud-redis-pass.txt") (Join-Path $staging "redis-pass.txt")
    Write-Host "staging ready: $staging"
}

if (-not (Test-Path (Join-Path $root ".local\cloud-redis-pass.txt"))) {
    Write-Host "FATAL: .local/cloud-redis-pass.txt missing (redis requirepass, gitignored)"
    exit 1
}
Stage-Files
Write-Host "uploading (jars ~126MB over 3Mbps, may take minutes)..."
scp -r $staging ${remote}:${remoteTmp}/ | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "FATAL scp failed"; exit 1 }
Write-Host "upload ok, running remote deploy script..."
ssh $remote "chmod +x /tmp/swapdeploy/deploy-remote.sh && bash /tmp/swapdeploy/deploy-remote.sh"
if ($LASTEXITCODE -ne 0) { Write-Host "FATAL remote deploy failed (exit $LASTEXITCODE)"; exit 1 }
Write-Host "deploy done; cleaning staging..."
Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
ssh $remote "rm -rf /tmp/swapdeploy"
