param()

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$composePath = Join-Path $repoRoot 'deploy/compose/docker-compose.prod.yml'
$envPath = Join-Path $PSScriptRoot 'release-config.test.env'
$webNginxPath = Join-Path $repoRoot 'apps/web/nginx.conf'
$webNginxSnippets = Join-Path $repoRoot 'apps/web/nginx'
$backupDockerfile = Join-Path $repoRoot 'deploy/images/backup/Dockerfile'
$backupBuildContext = Join-Path $repoRoot 'deploy/images/backup'
$backupImage = 'smartupcms/backup:release-config-test'
$deployShPath = Join-Path $PSScriptRoot 'deploy.sh'
$deployPsPath = Join-Path $PSScriptRoot 'deploy.ps1'
$restoreShPath = Join-Path $PSScriptRoot 'restore.sh'
$restorePsPath = Join-Path $PSScriptRoot 'restore.ps1'
$backupObjectsPsPath = Join-Path $PSScriptRoot 'backup-objects.ps1'
$restoreCombinedPsPath = Join-Path $PSScriptRoot 'restore-combined.ps1'
$rollbackShPath = Join-Path $PSScriptRoot 'rollback.sh'
$rollbackPsPath = Join-Path $PSScriptRoot 'rollback.ps1'
$testRecoveryPsPath = Join-Path $PSScriptRoot 'test-recovery.ps1'

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
Assert-Matches $composeSource '/postgres:\$\{APP_VERSION' 'Production must use the versioned SmartupCMS PostgreSQL image.'
Assert-Matches $composeSource '/typesense:\$\{APP_VERSION' 'Production must use the versioned SmartupCMS Typesense image.'
Assert-Matches $composeSource 'clamav/clamav-debian:1\.5\.4@sha256:[a-f0-9]{64}' 'Production must pin the official ClamAV image by version and digest.'
Assert-DoesNotMatch $composeSource 'control-plane|web-cp|db-cp|migrate-cp|smartupcms/instance' 'Retired Control Plane topology remains in production Compose.'
Assert-Matches $composeSource 'internal:\s*true' 'The database network must be internal.'
Assert-Matches $composeSource 'backup-status:/var/lib/smartupcms/backup:ro' 'The server must receive backup status read-only.'
Assert-Matches $composeSource '/tmp:rw,nosuid,nodev,noexec' 'The generic server temporary directory must remain noexec.'
Assert-Matches $composeSource '/tmp:rw,nosuid,nodev,noexec,size=1024m' 'The generic server temporary directory must be size-bounded.'
Assert-Matches $composeSource 'resources:\s*limits:\s*cpus:\s*"2\.0"\s*memory:\s*1536M' 'The server common template must enforce 2 CPU and 1536M hard limit.'
Assert-Matches $composeSource 'postgres:[\s\S]*resources:\s*limits:\s*cpus:\s*"2\.0"\s*memory:\s*1024M' 'PostgreSQL must enforce 2 CPU and 1024M hard limit.'
Assert-Matches $composeSource 'clamav:[\s\S]*resources:\s*limits:\s*cpus:\s*"1\.0"\s*memory:\s*1536M' 'ClamAV must enforce 1 CPU and 1536M hard limit.'
Assert-Matches $composeSource '/opt/smartupcms/jna:rw,nosuid,nodev,exec,size=16m,uid=10001,gid=10001,mode=0700' 'Argon2/JNA must have a private executable tmpfs owned by the non-root server user.'
Assert-Matches $composeSource '/var/lib/nginx/tmp:rw,nosuid,nodev,noexec,uid=10001,gid=10001,mode=0700' 'NGINX temporary files must use a private non-executable tmpfs owned by the web user.'
Assert-Matches $composeSource '/run/nginx:rw,nosuid,nodev,noexec,uid=10001,gid=10001,mode=0750' 'NGINX PID files must use a private non-executable tmpfs owned by the web user.'
Assert-Matches $composeSource 'SMC_WEBHOOKS_ENABLED:\s*\$\{SMC_WEBHOOKS_ENABLED:-false\}' 'Outbound webhooks must be disabled by default.'
Assert-Matches $composeSource 'SMC_WEBHOOKS_ALLOWED_HOSTS:\s*\$\{SMC_WEBHOOKS_ALLOWED_HOSTS:-\}' 'Outbound webhooks must require an explicit host allow-list.'
Assert-Matches $composeSource 'SMC_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES:\s*\$\{SMC_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES:-false\}' 'Private webhook destinations must require an explicit opt-in.'
Assert-DoesNotMatch $composeSource '(?<![A-Z0-9_])(?:APP_)?DWH_[A-Z0-9_]+' 'Compose must use only the configuration names of ADR-0027 (SMC_*, WAREHOUSE_*).'
foreach ($variable in @('SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'SMTP_PASSWORD', 'SMTP_STARTTLS', 'SMC_MAIL_FROM',
        'TELEGRAM_BOT_TOKEN', 'SMC_PROVIDER_MAIL', 'SMC_PROVIDER_MESSENGER')) {
    Assert-Matches $composeSource ([regex]::Escape($variable) + ':\s*\$\{' + [regex]::Escape($variable) + ':-')
        "Production Compose must pass $variable to the server (password reset and two-factor delivery)."
}
Assert-Matches $composeSource 'SMC_DELIVERY_ENFORCE:\s*\$\{SMC_DELIVERY_ENFORCE:-true\}' 'Production must refuse to start while two-factor users depend on a stub channel.'
Assert-Matches $composeSource 'SMC_PUBLIC_URL:\s*\$\{SMC_PUBLIC_URL:\?' 'Production must require the public address invitation and reset links are built from.'
Assert-Matches $composeSource 'SMC_SECRETS_KEY:\s*\$\{SMC_SECRETS_KEY:\?' 'Production must require the key of the secrets kept in the database (ADR-0029).'
Assert-DoesNotMatch $composeSource 'max-size:\s*"50m"' 'Container logs rotate at 100 MB (decision of 2026-09-27).'
Assert-Matches $composeSource 'max-size:\s*"100m",\s*max-file:\s*"5",\s*compress:\s*"true"' 'Container logs must rotate at 100 MB and be compressed.'
Assert-Matches $composeSource 'SMC_LOG_FILE:\s*\$\{SMC_LOG_FILE:-/var/lib/smartupcms/logs/server\.log\}' 'The server must write its weekly archived log file on the data volume.'
foreach ($variable in @('SMC_AUDIT_ARCHIVE_TARGET', 'SMC_AUDIT_ARCHIVE_LOCAL_PATH', 'SMC_AUDIT_ARCHIVE_RETENTION',
        'SMC_AUDIT_ARCHIVE_DELETE_AFTER_ARCHIVE', 'SMC_AUDIT_ARCHIVE_S3_BUCKET', 'SMC_AUDIT_ARCHIVE_S3_SECRET_KEY')) {
    Assert-Matches $composeSource ([regex]::Escape($variable) + ':\s*\$\{' + [regex]::Escape($variable) + ':-')
        "Production Compose must pass $variable to the server (audit log archive)."
}
Assert-Matches $composeSource 'SMC_AUDIT_ARCHIVE_DELETE_AFTER_ARCHIVE:\s*\$\{SMC_AUDIT_ARCHIVE_DELETE_AFTER_ARCHIVE:-false\}' 'Deleting archived audit partitions must be an explicit opt-in.'
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

foreach ($scriptPath in @($deployPsPath, (Join-Path $PSScriptRoot 'backup.ps1'), $restorePsPath,
        $backupObjectsPsPath, $restoreCombinedPsPath, $rollbackPsPath, $testRecoveryPsPath,
        (Join-Path $PSScriptRoot 'test-backup-status.ps1'),
        (Join-Path $PSScriptRoot 'init-production-env.ps1'),
        (Join-Path $repoRoot 'scripts/release/test-release-gates.ps1'),
        (Join-Path $repoRoot 'scripts/security/test-secret-scan.ps1'))) {
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

    $requiredServices = @('postgres', 'migrate', 'server', 'web', 'typesense', 'clamav', 'backup', 'backup-bootstrap')
    foreach ($service in $requiredServices) {
        if ($config.services.PSObject.Properties.Name -notcontains $service) {
            throw "Production Compose is missing service '$service'."
        }
    }
    if ($null -eq $config.services.web.ports -or @($config.services.web.ports).Count -ne 1) {
        throw 'Web must be the only service with one published port.'
    }
    foreach ($service in @('postgres', 'server', 'typesense', 'clamav', 'backup')) {
        if ($null -ne $config.services.$service.ports -and @($config.services.$service.ports).Count -gt 0) {
            throw "Service '$service' must not publish a host port."
        }
    }
    if (-not $config.networks.backend.internal) { throw 'Production backend network is not internal.' }
    # ADR-0034: X-Forwarded-For is believed only from an explicit list, by default the pinned frontend network.
    $frontendSubnet = "$(@($config.networks.frontend.ipam.config)[0].subnet)"
    if (-not $frontendSubnet) { throw 'The production frontend network must have a pinned subnet for the trusted-proxy lists.' }
    if ("$($config.services.server.environment.SMC_SECURITY_TRUSTED_PROXIES)" -ne $frontendSubnet) {
        throw 'The server must trust X-Forwarded-For only from the frontend network by default.'
    }
    if ("$($config.services.web.environment.SMC_WEB_TRUSTED_PROXIES)" -ne $frontendSubnet) {
        throw 'The web origin must trust X-Forwarded-For only from the frontend network by default.'
    }
    if ("$($config.services.web.environment.SMC_WEB_FRAME_SOURCES)" -ne '') {
        throw 'Embedded frame sources must be an explicit operator choice (empty by default).'
    }
    if ("$($config.services.server.environment.SMC_FILE_SCANNER_REQUIRED)" -ne 'true') {
        throw 'Production server must require a malware scanner by default.'
    }
    if ("$($config.services.server.environment.SMC_FILE_SCANNER_CLAMAV_ENABLED)" -ne 'true') {
        throw 'Production server must activate the bundled ClamAV provider.'
    }
    if ("$($config.services.server.depends_on.clamav.condition)" -ne 'service_healthy') {
        throw 'Production server must wait for a healthy ClamAV service.'
    }
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

# The image copies the header snippets to /etc/nginx/smc and runs 40-smc-edge-config.sh at start (ADR-0034);
# the same layout here, with an operator's frame source and proxy list, must pass nginx -t.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$nginxConfigOutput = docker run --rm `
    --add-host server:127.0.0.1 `
    -e 'SMC_WEB_FRAME_SOURCES=https://bi.example.test' `
    -e 'SMC_WEB_TRUSTED_PROXIES=172.30.80.0/24' `
    -v "${webNginxPath}:/etc/nginx/conf.d/default.conf:ro" `
    -v "${webNginxSnippets}:/etc/nginx/smc:ro" `
    --entrypoint sh `
    nginx:1.28-alpine -ec 'sh /etc/nginx/smc/40-smc-edge-config.sh && nginx -T' 2>&1
$ErrorActionPreference = $prevEap
if ($LASTEXITCODE -ne 0) { throw "Web NGINX configuration failed nginx -t: $($nginxConfigOutput -join [Environment]::NewLine)" }
$nginxConfigText = $nginxConfigOutput -join [Environment]::NewLine
Assert-Matches $nginxConfigText 'client_max_body_size\s+51m' `
    'Web NGINX must allow a 50 MiB file plus bounded multipart overhead.'
Assert-Matches $nginxConfigText "frame-src 'self'\`$smc_frame_sources" 'The SPA CSP must take its frame sources from the validated runtime list.'
Assert-Matches $nginxConfigText 'default " https://bi\.example\.test"' 'The start-up step must pass a valid frame source through.'
Assert-Matches $nginxConfigText 'set_real_ip_from 172\.30\.80\.0/24;' 'The start-up step must trust exactly the listed proxies.'
foreach ($bad in @('SMC_WEB_FRAME_SOURCES=https:', 'SMC_WEB_TRUSTED_PROXIES=0.0.0.0/0', 'SMC_WEB_TRUSTED_PROXIES=proxy.example.test')) {
    $ErrorActionPreference = 'Continue'
    $null = docker run --rm -e $bad -v "${webNginxSnippets}:/smc:ro" --entrypoint sh nginx:1.28-alpine /smc/40-smc-edge-config.sh 2>&1
    $badExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($badExit -eq 0) { throw "The web start-up step must refuse $bad." }
}

# ADR-0034, plan 10/10, item 7.4: nginx drops outer add_header directives in a location with its own, so the
# headers live in snippets that every location includes; /api/ wins over the asset regex.
Assert-Matches $webNginx 'location \^~ /api/' 'The API location must be a prefix match (^~) so the asset regex cannot take /api/*.js.'
Assert-Matches $webNginx 'server_tokens off;' 'The web origin must not reveal the nginx version.'
$serverLevel = ($webNginx -split 'location ')[0]
Assert-DoesNotMatch $serverLevel '(?m)^\s*add_header\s' 'Server-level add_header is lost in every location with its own; use the snippets.'
foreach ($location in @(($webNginx -split '(?m)^\s*location ') | Select-Object -Skip 1)) {
    $name = ($location -split '\{')[0].Trim()
    if ($location -notmatch 'include /etc/nginx/smc/security-headers\.conf;') { throw "NGINX location '$name' does not include the security headers." }
    if ($location -notmatch 'include /etc/nginx/smc/(spa|api)-csp\.conf;') { throw "NGINX location '$name' does not include a CSP." }
}
$webHeaders = Get-Content -LiteralPath (Join-Path $webNginxSnippets 'security-headers.conf') -Raw
if ($webHeaders -notmatch 'Strict-Transport-Security "max-age=(\d+); includeSubDomains"' -or [long]$Matches[1] -lt 31536000) {
    throw 'HSTS must last at least a year and cover subdomains.'
}
foreach ($header in @('X-Content-Type-Options "nosniff"', 'X-Frame-Options "DENY"', 'Referrer-Policy', 'Permissions-Policy')) {
    Assert-Matches $webHeaders ([regex]::Escape($header)) "The web security headers must set $header."
}
$spaPolicy = Get-Content -LiteralPath (Join-Path $webNginxSnippets 'spa-csp.conf') -Raw
Assert-DoesNotMatch $spaPolicy 'fonts\.googleapis\.com|fonts\.gstatic\.com' 'Fonts are local: the CSP must not allow third-party font hosts.'
Assert-DoesNotMatch $spaPolicy 'frame-src[^;"]*\s(http:|https:|data:|blob:|\*)[\s;"]' 'frame-src must list origins, never whole schemes.'
Assert-DoesNotMatch $spaPolicy "script-src[^;]*'unsafe-(inline|eval)'" 'script-src must not allow inline or eval scripts.'
$webDockerfile = Get-Content -LiteralPath (Join-Path $repoRoot 'apps/web/Dockerfile') -Raw
Assert-Matches $webDockerfile 'nginx/40-smc-edge-config\.sh /docker-entrypoint\.d/' 'The web image must run the edge start-up step.'
if (Test-Path -LiteralPath (Join-Path $repoRoot 'deploy/nginx')) {
    throw 'deploy/nginx is not part of the runtime: the web image is the only origin (ADR-0034).'
}

$bashSyntaxCheck = @'
set -eu
mkdir -p /tmp/release-scripts /tmp/backup-scripts
for script in deploy.sh backup.sh restore.sh rollback.sh test-deploy-fail-closed.sh init-production-env.sh; do
    sed 's/\r$//' "/release/$script" > "/tmp/release-scripts/$script"
done
for script in write-status.sh backup-loop.sh bootstrap-role.sh; do
    sed 's/\r$//' "/backup/$script" > "/tmp/backup-scripts/$script"
done
bash -n /tmp/release-scripts/*.sh /tmp/backup-scripts/*.sh
'@
$bashSyntaxCheck = $bashSyntaxCheck.Replace("`r", '')
docker run --rm `
    -v "${PSScriptRoot}:/release:ro" `
    -v "$(Join-Path $repoRoot 'deploy/images/backup'):/backup:ro" `
    bash:5.2 bash -ec $bashSyntaxCheck
if ($LASTEXITCODE -ne 0) { throw 'Production Bash scripts failed syntax validation.' }

Write-Host 'Production release configuration checks passed.' -ForegroundColor Green
