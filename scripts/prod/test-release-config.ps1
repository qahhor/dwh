param()

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$nginxPath = Join-Path $repoRoot "deploy/nginx/nginx.prod.conf"
$composePath = Join-Path $repoRoot "deploy/compose/docker-compose.fleet.prod.yml"
$proxyDockerfilePath = Join-Path $repoRoot "deploy/images/nginx-proxy/Dockerfile"
$proxyImage = "smartupcms/nginx-proxy:release-config-test"
$envPath = Join-Path $PSScriptRoot "release-config.test.env"
$deployShPath = Join-Path $PSScriptRoot "deploy.sh"
$deployPsPath = Join-Path $PSScriptRoot "deploy.ps1"
$restoreShPath = Join-Path $PSScriptRoot "restore.sh"
$restorePsPath = Join-Path $PSScriptRoot "restore.ps1"

function Assert-Matches([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) {
        throw $Message
    }
}

function Assert-DoesNotMatch([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -match $Pattern) {
        throw $Message
    }
}

$nginx = Get-Content -Raw $nginxPath
$compose = Get-Content -Raw $composePath
$deploySh = Get-Content -Raw $deployShPath
$deployPs = Get-Content -Raw $deployPsPath
$restoreSh = Get-Content -Raw $restoreShPath
$restorePs = Get-Content -Raw $restorePsPath

Assert-Matches $nginx 'server\s+web:8080;' "Production proxy must route to the non-root web container on port 8080."
Assert-Matches $nginx 'server\s+web-cp:8080;' "Production proxy must route to the non-root control-plane UI on port 8080."
Assert-Matches $compose '\$\{PROXY_BIND:-127\.0\.0\.1\}:\$\{HTTP_PORT:-8088\}:8080' "Plain HTTP proxy must bind to loopback for an external TLS terminator."
Assert-DoesNotMatch $compose 'HTTPS_PORT' "Compose must not publish a fake HTTPS port without certificates and a TLS listener."
Assert-DoesNotMatch $compose 'typesense-server.*--health' "Typesense healthcheck must probe HTTP; --health is not a supported server command."

Assert-Matches $deploySh 'backup\.sh' "Bash deployment must execute a pre-deploy backup."
Assert-DoesNotMatch $deploySh 'backup\.sh\s*\|\|' "Bash deployment must fail closed when an existing database backup fails."
Assert-Matches $deploySh 'pull --ignore-buildable' "Bash deployment must refresh external runtime images."
Assert-Matches $deploySh 'build --pull db typesense proxy' "Bash deployment must rebuild hardened runtime images."
Assert-Matches $deploySh 'up\s+-d\s+--remove-orphans\s+--wait\s+--wait-timeout' "Bash deployment must wait for readiness before reporting success."

Assert-Matches $deployPs 'backup\.ps1' "PowerShell deployment must execute a pre-deploy backup."
Assert-Matches $deployPs 'pull --ignore-buildable' "PowerShell deployment must refresh external runtime images."
Assert-Matches $deployPs 'build --pull db typesense proxy' "PowerShell deployment must rebuild hardened runtime images."
Assert-Matches $deployPs '--wait' "PowerShell deployment must wait for readiness before reporting success."
Assert-Matches $deployPs '\$LASTEXITCODE' "PowerShell deployment must propagate native command failures."

Assert-Matches $restoreSh 'pg_restore' "Bash restore must support the custom-format dumps created by backup.sh."
Assert-Matches $restoreSh 'sha256' "Bash restore must verify the backup checksum."
Assert-Matches $restorePs 'pg_restore' "PowerShell restore must support the custom-format dumps created by backup.ps1."
Assert-Matches $restorePs 'sha256' "PowerShell restore must verify the backup checksum."

foreach ($scriptPath in @($deployPsPath, (Join-Path $PSScriptRoot "backup.ps1"), $restorePsPath)) {
    [scriptblock]::Create((Get-Content -Raw $scriptPath)) | Out-Null
}

docker compose -f $composePath --env-file $envPath config --quiet
if ($LASTEXITCODE -ne 0) {
    throw "Production fleet compose failed docker compose config validation."
}

docker build --pull --file $proxyDockerfilePath --tag $proxyImage $repoRoot
if ($LASTEXITCODE -ne 0) {
    throw "Hardened production proxy image failed to build."
}

$proxyUser = & docker image inspect $proxyImage --format "{{.Config.User}}"
if ($LASTEXITCODE -ne 0 -or $proxyUser -ne "nginx:nginx") {
    throw "Production proxy image must run as nginx:nginx."
}

docker run --rm `
    --add-host app:127.0.0.1 `
    --add-host control-plane:127.0.0.1 `
    --add-host web:127.0.0.1 `
    --add-host web-cp:127.0.0.1 `
    -v "${nginxPath}:/etc/nginx/nginx.conf:ro" `
    $proxyImage nginx -t
if ($LASTEXITCODE -ne 0) {
    throw "Production NGINX configuration failed nginx -t."
}

docker run --rm `
    -v "${PSScriptRoot}:/scripts:ro" `
    bash:5.2 bash -n /scripts/deploy.sh /scripts/backup.sh /scripts/restore.sh /scripts/test-deploy-fail-closed.sh
if ($LASTEXITCODE -ne 0) {
    throw "Production Bash scripts failed syntax validation."
}

Write-Host "Production release configuration checks passed." -ForegroundColor Green
