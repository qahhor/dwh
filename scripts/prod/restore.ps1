param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [Parameter(Mandatory = $true)][string]$AgeIdentityFile,
    [string]$ComposeFile = 'deploy/compose/docker-compose.prod.yml',
    [string]$EnvFile = '.env.production',
    [ValidateRange(1, 3600)][int]$HealthTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArguments)
    & docker compose -f $ComposeFile --env-file $EnvFile @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($ComposeArguments -join ' ')" }
}

$backupPath = (Resolve-Path -LiteralPath $BackupFile).Path
$identityPath = (Resolve-Path -LiteralPath $AgeIdentityFile).Path
$checksumPath = "${backupPath}.sha256"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) { throw 'Backup checksum does not exist.' }
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw 'Environment file does not exist.' }

$expectedHash = ((Get-Content -LiteralPath $checksumPath -TotalCount 1) -split '\s+')[0].ToLowerInvariant()
$actualHash = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($expectedHash) -or $expectedHash -ne $actualHash) {
    throw 'Encrypted backup SHA-256 verification failed.'
}

Invoke-Compose config --quiet
$postgresId = & docker compose -f $ComposeFile --env-file $EnvFile ps -a -q postgres
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($postgresId -join ''))) {
    throw 'PostgreSQL is not running.'
}

$archiveDirectory = Split-Path -Parent $backupPath
$archiveName = Split-Path -Leaf $backupPath
$decryptArguments = @(
    'compose', '-f', $ComposeFile, '--env-file', $EnvFile,
    'run', '--rm', '--no-deps', '--entrypoint', 'age',
    '-v', "${archiveDirectory}:/restore:ro", '-v', "${identityPath}:/identity.txt:ro",
    'backup', '--decrypt', '--identity', '/identity.txt', "/restore/${archiveName}"
)

Write-Host '[1/6] Validating encrypted archive and pg_restore catalog...' -ForegroundColor Yellow
& docker @decryptArguments | & docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres pg_restore --list | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Encrypted archive validation failed.' }

Write-Host '[2/6] Stopping the server to prevent writes...' -ForegroundColor Yellow
Invoke-Compose stop server

$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$databaseReset = @'
case "$POSTGRES_DB$POSTGRES_USER" in *[!A-Za-z0-9_]*) echo "Unsafe database identifier" >&2; exit 1;; esac
previous="${POSTGRES_DB}_pre_restore_$1"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$POSTGRES_DB' and pid <> pg_backend_pid()"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "alter database \"$POSTGRES_DB\" rename to \"$previous\""
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "create database \"$POSTGRES_DB\" owner \"$POSTGRES_USER\""
'@
Write-Host '[3/6] Preserving the current database and creating a clean target...' -ForegroundColor Yellow
Invoke-Compose exec -T postgres sh -ec $databaseReset restore $timestamp

Write-Host '[4/6] Streaming decrypted data directly into PostgreSQL...' -ForegroundColor Yellow
& docker @decryptArguments | & docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres sh -ec 'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
if ($LASTEXITCODE -ne 0) { throw 'Restore stream failed.' }

Write-Host '[5/6] Applying migrations and refreshing the backup role...' -ForegroundColor Yellow
Invoke-Compose run --rm migrate
Invoke-Compose run --rm backup-bootstrap

Write-Host '[6/6] Starting SmartupCMS and waiting for readiness...' -ForegroundColor Yellow
Invoke-Compose up -d --wait --wait-timeout $HealthTimeoutSeconds
Write-Host "Restore completed. Previous database suffix: _pre_restore_${timestamp}." -ForegroundColor Green
