param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [Parameter(Mandatory = $true)][string]$AgeIdentityFile,
    # The smartupcms_dwh-<timestamp>.dump.age of the same set (ADR-0001); without it the DWH is left as it is.
    [string]$DwhBackupFile,
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

function Assert-Checksum([string]$Path) {
    $checksumPath = "${Path}.sha256"
    if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) { throw "Checksum of $(Split-Path -Leaf $Path) does not exist." }
    $expectedHash = ((Get-Content -LiteralPath $checksumPath -TotalCount 1) -split '\s+')[0].ToLowerInvariant()
    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($expectedHash) -or $expectedHash -ne $actualHash) {
        throw "SHA-256 verification of $(Split-Path -Leaf $Path) failed."
    }
}

function Get-DecryptArguments([string]$Path) {
    return @(
        'compose', '-f', $ComposeFile, '--env-file', $EnvFile,
        'run', '--rm', '--no-deps', '--entrypoint', 'age',
        '-v', "$(Split-Path -Parent $Path):/restore:ro", '-v', "${identityPath}:/identity.txt:ro",
        'backup', '--decrypt', '--identity', '/identity.txt', "/restore/$(Split-Path -Leaf $Path)"
    )
}

$backupPath = (Resolve-Path -LiteralPath $BackupFile).Path
$identityPath = (Resolve-Path -LiteralPath $AgeIdentityFile).Path
$dwhBackupPath = if ($DwhBackupFile) { (Resolve-Path -LiteralPath $DwhBackupFile).Path } else { $null }
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw 'Environment file does not exist.' }
Assert-Checksum $backupPath
if ($dwhBackupPath) { Assert-Checksum $dwhBackupPath }

Invoke-Compose config --quiet
$postgresId = & docker compose -f $ComposeFile --env-file $EnvFile ps -a -q postgres
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($postgresId -join ''))) {
    throw 'PostgreSQL is not running.'
}

$decryptArguments = Get-DecryptArguments $backupPath
$dwhDecryptArguments = if ($dwhBackupPath) { Get-DecryptArguments $dwhBackupPath } else { $null }

Write-Host '[1/6] Validating encrypted archives and pg_restore catalogs...' -ForegroundColor Yellow
& docker @decryptArguments | & docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres pg_restore --list | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Encrypted archive validation failed.' }
if ($dwhDecryptArguments) {
    & docker @dwhDecryptArguments | & docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres pg_restore --list | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Encrypted DWH archive validation failed.' }
}
else {
    Write-Warning 'No DWH archive given: the DWH database is left as it is.'
}

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
# The application role owns the DWH (ADR-0001): the new database is its own.
$dwhDatabaseReset = @'
case "$DWH_DB_NAME$APP_DB_USER$POSTGRES_USER" in *[!A-Za-z0-9_]*) echo "Unsafe database identifier" >&2; exit 1;; esac
previous="${DWH_DB_NAME}_pre_restore_$1"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$DWH_DB_NAME' and pid <> pg_backend_pid()"
if psql -At -U "$POSTGRES_USER" -d postgres -c "select 1 from pg_database where datname = '$DWH_DB_NAME'" | grep -q 1; then
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "alter database \"$DWH_DB_NAME\" rename to \"$previous\""
fi
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "create database \"$DWH_DB_NAME\" owner \"$APP_DB_USER\""
'@
Write-Host '[3/6] Preserving the current databases and creating clean targets...' -ForegroundColor Yellow
Invoke-Compose exec -T postgres sh -ec $databaseReset restore $timestamp
if ($dwhDecryptArguments) { Invoke-Compose exec -T postgres sh -ec $dwhDatabaseReset restore $timestamp }

Write-Host '[4/6] Streaming decrypted data directly into PostgreSQL...' -ForegroundColor Yellow
& docker @decryptArguments | & docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres sh -ec 'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
if ($LASTEXITCODE -ne 0) { throw 'Restore stream failed.' }
if ($dwhDecryptArguments) {
    # --role makes the application role own what is restored, as migrations left it.
    & docker @dwhDecryptArguments | & docker compose -f $ComposeFile --env-file $EnvFile exec -T postgres sh -ec 'exec pg_restore --exit-on-error --no-owner --no-acl --role="$APP_DB_USER" -U "$POSTGRES_USER" -d "$DWH_DB_NAME"'
    if ($LASTEXITCODE -ne 0) { throw 'DWH restore stream failed.' }
}

Write-Host '[5/6] Applying migrations and refreshing the backup role...' -ForegroundColor Yellow
Invoke-Compose run --rm migrate
Invoke-Compose run --rm backup-bootstrap

Write-Host '[6/6] Starting SmartupCMS and waiting for readiness...' -ForegroundColor Yellow
Invoke-Compose up -d --wait --wait-timeout $HealthTimeoutSeconds
Write-Host "Restore completed. Previous database suffix: _pre_restore_${timestamp}." -ForegroundColor Green
