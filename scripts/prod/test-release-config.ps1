param()

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$composePath = Join-Path $repoRoot 'deploy/compose/docker-compose.prod.yml'
$envPath = Join-Path $PSScriptRoot 'release-config.test.env'
$webNginxPath = Join-Path $repoRoot 'apps/web/nginx.conf'
$backupDockerfile = Join-Path $repoRoot 'deploy/images/backup/Dockerfile'
$backupBuildContext = Join-Path $repoRoot 'deploy/images/backup'
$backupImage = 'smartupcms/backup:release-config-test'
$deployShPath = Join-Path $PSScriptRoot 'deploy.sh'
$deployPsPath = Join-Path $PSScriptRoot 'deploy.ps1'
$restoreShPath = Join-Path $PSScriptRoot 'restore.sh'
$restorePsPath = Join-Path $PSScriptRoot 'restore.ps1'

function Assert-Matches([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { throw $Message }
}

function Assert-DoesNotMatch([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -match $Pattern) { throw $Message }
}

$composeSource = Get-Content -LiteralPath $composePath -Raw
$webNginx = Get-Content -LiteralPath $webNginxPath -Raw
$deploySh = Get-Content -LiteralPath $deployShPath -Raw
$deployPs = Get-Content -LiteralPath $deployPsPath -Raw
$restoreSh = Get-Content -LiteralPath $restoreShPath -Raw
$restorePs = Get-Content -LiteralPath $restorePsPath -Raw

Assert-Matches $composeSource '/server:\$\{APP_VERSION' 'Production must use the versioned SmartupCMS server image.'
Assert-Matches $composeSource '/web:\$\{APP_VERSION' 'Production must use the versioned SmartupCMS web image.'
Assert-Matches $composeSource '/backup:\$\{APP_VERSION' 'Production must use the versioned SmartupCMS backup image.'
Assert-DoesNotMatch $composeSource 'control-plane|web-cp|db-cp|migrate-cp|smartupcms/instance' 'Retired Control Plane topology remains in production Compose.'
Assert-Matches $composeSource 'internal:\s*true' 'The database network must be internal.'
Assert-Matches $composeSource 'backup-status:/var/lib/smartupcms/backup:ro' 'The server must receive backup status read-only.'
Assert-Matches $webNginx 'server:8080' 'The single web origin must proxy API traffic to server:8080.'
Assert-DoesNotMatch $webNginx 'control-plane|web-cp|app:8080' 'The web origin still references a retired runtime.'

Assert-Matches $deploySh 'scripts/prod/backup\.sh' 'Bash deployment must execute a pre-migration backup.'
Assert-Matches $deploySh 'pull' 'Bash deployment must pull immutable release images.'
Assert-Matches $deploySh 'run --rm migrate' 'Bash deployment must execute forward migrations.'
Assert-Matches $deploySh 'run --rm backup-bootstrap' 'Bash deployment must refresh the read-only backup role.'
if ($deploySh.IndexOf('scripts/prod/backup.sh') -gt $deploySh.IndexOf('run --rm migrate')) {
    throw 'Bash deployment must back up before migration.'
}
Assert-Matches $deployPs 'backup\.ps1' 'PowerShell deployment must execute a pre-migration backup.'
Assert-Matches $deployPs 'run --rm migrate' 'PowerShell deployment must execute forward migrations.'
Assert-Matches $restoreSh 'age[\s\S]*pg_restore' 'Bash restore must stream age-decrypted data to pg_restore.'
Assert-Matches $restorePs 'decryptArguments[\s\S]*pg_restore' 'PowerShell restore must stream age-decrypted data to pg_restore.'
Assert-Matches $restoreSh 'sha256sum' 'Bash restore must verify the encrypted artifact checksum.'
Assert-Matches $restorePs 'Get-FileHash' 'PowerShell restore must verify the encrypted artifact checksum.'

foreach ($scriptPath in @($deployPsPath, (Join-Path $PSScriptRoot 'backup.ps1'), $restorePsPath, (Join-Path $PSScriptRoot 'test-backup-status.ps1'))) {
    [scriptblock]::Create((Get-Content -LiteralPath $scriptPath -Raw)) | Out-Null
}

$testSecretRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('smartupcms-release-config-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testSecretRoot | Out-Null
try {
    $databasePassword = Join-Path $testSecretRoot 'database-password'
    $backupPassword = Join-Path $testSecretRoot 'backup-database-password'
    $unusedSecret = Join-Path $testSecretRoot 'unused'
    Set-Content -LiteralPath $databasePassword -Value 'obvious-test-database-value' -NoNewline
    Set-Content -LiteralPath $backupPassword -Value 'obvious-test-backup-value' -NoNewline
    Set-Content -LiteralPath $unusedSecret -Value 'unused' -NoNewline
    $previousDbPasswordFile = $env:DB_PASSWORD_FILE
    $previousBackupPasswordFile = $env:BACKUP_DB_PASSWORD_FILE
    $previousAccessKeyFile = $env:BACKUP_S3_ACCESS_KEY_ID_FILE
    $previousSecretKeyFile = $env:BACKUP_S3_SECRET_ACCESS_KEY_FILE
    $env:DB_PASSWORD_FILE = $databasePassword
    $env:BACKUP_DB_PASSWORD_FILE = $backupPassword
    $env:BACKUP_S3_ACCESS_KEY_ID_FILE = $unusedSecret
    $env:BACKUP_S3_SECRET_ACCESS_KEY_FILE = $unusedSecret

    $configJsonText = & docker compose -f $composePath --env-file $envPath --profile tools config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Production Compose failed config validation.' }
    $config = $configJsonText | ConvertFrom-Json

    $requiredServices = @('postgres', 'migrate', 'server', 'web', 'typesense', 'backup', 'backup-bootstrap')
    foreach ($service in $requiredServices) {
        if ($config.services.PSObject.Properties.Name -notcontains $service) {
            throw "Production Compose is missing service '$service'."
        }
    }
    if ($null -eq $config.services.web.ports -or @($config.services.web.ports).Count -ne 1) {
        throw 'Web must be the only service with one published port.'
    }
    foreach ($service in @('postgres', 'server', 'typesense', 'backup')) {
        if ($null -ne $config.services.$service.ports -and @($config.services.$service.ports).Count -gt 0) {
            throw "Service '$service' must not publish a host port."
        }
    }
    if (-not $config.networks.backend.internal) { throw 'Production backend network is not internal.' }
}
finally {
    $env:DB_PASSWORD_FILE = $previousDbPasswordFile
    $env:BACKUP_DB_PASSWORD_FILE = $previousBackupPasswordFile
    $env:BACKUP_S3_ACCESS_KEY_ID_FILE = $previousAccessKeyFile
    $env:BACKUP_S3_SECRET_ACCESS_KEY_FILE = $previousSecretKeyFile
    if (Test-Path -LiteralPath $testSecretRoot) { Remove-Item -LiteralPath $testSecretRoot -Recurse -Force }
}

docker build --pull --file $backupDockerfile --tag $backupImage $backupBuildContext
if ($LASTEXITCODE -ne 0) { throw 'Backup image failed to build.' }
$backupUser = & docker image inspect $backupImage --format '{{.Config.User}}'
if ($LASTEXITCODE -ne 0 -or $backupUser -ne 'backup:backup') {
    throw 'Backup image must run as backup:backup.'
}
$backupUid = & docker run --rm --entrypoint id $backupImage -u
if ($LASTEXITCODE -ne 0 -or $backupUid.Trim() -ne '10001') {
    throw 'Backup image UID must match the server data UID so 0600 status remains readable.'
}

docker run --rm `
    --add-host server:127.0.0.1 `
    -v "${webNginxPath}:/etc/nginx/conf.d/default.conf:ro" `
    nginx:1.28-alpine nginx -t
if ($LASTEXITCODE -ne 0) { throw 'Web NGINX configuration failed nginx -t.' }

$bashSyntaxCheck = @'
set -eu
mkdir -p /tmp/release-scripts /tmp/backup-scripts
for script in deploy.sh backup.sh restore.sh test-deploy-fail-closed.sh; do
    sed 's/\r$//' "/release/$script" > "/tmp/release-scripts/$script"
done
for script in write-status.sh backup-loop.sh bootstrap-role.sh; do
    sed 's/\r$//' "/backup/$script" > "/tmp/backup-scripts/$script"
done
bash -n /tmp/release-scripts/*.sh /tmp/backup-scripts/*.sh
'@
docker run --rm `
    -v "${PSScriptRoot}:/release:ro" `
    -v "$(Join-Path $repoRoot 'deploy/images/backup'):/backup:ro" `
    bash:5.2 bash -ec $bashSyntaxCheck
if ($LASTEXITCODE -ne 0) { throw 'Production Bash scripts failed syntax validation.' }

Write-Host 'Production release configuration checks passed.' -ForegroundColor Green
