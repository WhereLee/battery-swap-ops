# S8 batch31 console deployment orchestrator (local -> 124.223.36.154)
#
# Stages: server jar + swap-web dist + db/16,17 + nginx site -> scp -> rollout-web.sh.
# Prereq: mvn package done, swap-web/dist built (npm run build), ssh key auth working.
# Secrets are never staged or printed; the remote script reads them from swap.env.
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$staging = Join-Path $env:TEMP "swapweb-stage"
$remote = "ubuntu@124.223.36.154"
$remoteTmp = "/tmp/swapdeploy"

$jar = Join-Path $root "swap-server\target\swap-server-1.0.0.jar"
$dist = Join-Path $root "swap-web\dist\index.html"
foreach ($required in @($jar, $dist)) {
    if (-not (Test-Path $required)) { Write-Host "FATAL missing $required"; exit 1 }
}

if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $staging "web") -Force | Out-Null
Copy-Item $jar $staging
Copy-Item (Join-Path $root "swap-web\dist\*") (Join-Path $staging "web") -Recurse
Copy-Item (Join-Path $root "db\16-data-scope.sql") $staging
Copy-Item (Join-Path $root "db\17-work-order-scope.sql") $staging
Copy-Item (Join-Path $PSScriptRoot "nginx-swap.conf") $staging
Copy-Item (Join-Path $PSScriptRoot "rollout-web.sh") $staging
Write-Host "staging ready: $staging (jar + dist + db/16,17 + nginx site)"

Write-Host "uploading (jar ~60MB + dist over 3Mbps)..."
ssh $remote "rm -rf $remoteTmp; mkdir -p $remoteTmp"
scp -r "$staging\*" "${remote}:${remoteTmp}/" | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "FATAL scp failed"; exit 1 }

Write-Host "running remote rollout..."
ssh $remote "chmod +x $remoteTmp/rollout-web.sh && bash $remoteTmp/rollout-web.sh"
$code = $LASTEXITCODE
Write-Host "remote rollout exit=$code; cleaning staging..."
Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
ssh $remote "rm -rf $remoteTmp /tmp/swapdeploy/probe.json"
if ($code -ne 0) { exit $code }
