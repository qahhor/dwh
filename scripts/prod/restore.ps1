param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,
    [string]$ComposeFile = "deploy/compose/docker-compose.fleet.prod.yml",
    [string]$EnvFile = ".env.production",
    [ValidateSet("instance", "cp")]
    [string]$Target = "instance",
    [ValidateRange(1, 3600)]
    [int]$HealthTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArguments)

    & docker compose -f $ComposeFile --env-file $EnvFile @ComposeArguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($ComposeArguments -join ' ')"
    }
}

if (-not (Test-Path -LiteralPath $BackupFile -PathType Leaf)) {
    throw "Backup file '$BackupFile' does not exist."
}
$checksumFile = "${BackupFile}.sha256"
if (-not (Test-Path -LiteralPath $checksumFile -PathType Leaf)) {
    throw "Required checksum '$checksumFile' does not exist."
}
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file '$EnvFile' does not exist."
}

$expectedHash = ((Get-Content -LiteralPath $checksumFile -TotalCount 1) -split '\s+')[0].ToLowerInvariant()
$actualHash = (Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($expectedHash) -or $expectedHash -ne $actualHash) {
    throw "Backup SHA-256 verification failed."
}

if ($Target -eq "instance") {
    $dbService = "db"
    $workloadService = "app"
    $migrateService = "migrate"
}
else {
    $dbService = "db-cp"
    $workloadService = "control-plane"
    $migrateService = "migrate-cp"
}

Invoke-Compose config --quiet
$containerId = & docker compose -f $ComposeFile --env-file $EnvFile ps -a -q $dbService
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($containerId -join ""))) {
    throw "Database service '$dbService' does not exist."
}

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$remoteFile = "/tmp/dwh-restore-${timestamp}.dump"

try {
    Write-Host "[1/6] Copying and validating the custom-format dump..." -ForegroundColor Yellow
    Invoke-Compose cp $BackupFile "${dbService}:${remoteFile}"
    Invoke-Compose exec -T $dbService pg_restore --list $remoteFile | Out-Null

    Write-Host "[2/6] Stopping '$workloadService' to prevent writes..." -ForegroundColor Yellow
    Invoke-Compose stop $workloadService

    Write-Host "[3/6] Preserving the current database and creating a clean target..." -ForegroundColor Yellow
    $databaseReset = @'
case "$POSTGRES_DB$POSTGRES_USER" in
  *[!A-Za-z0-9_]*) echo "Unsafe database identifier" >&2; exit 1 ;;
esac
previous="${POSTGRES_DB}_pre_restore_$1"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$POSTGRES_DB' and pid <> pg_backend_pid()"
if psql -At -U "$POSTGRES_USER" -d postgres -c "select 1 from pg_database where datname = '$POSTGRES_DB'" | grep -q 1; then
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "alter database \"$POSTGRES_DB\" rename to \"$previous\""
fi
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "create database \"$POSTGRES_DB\" owner \"$POSTGRES_USER\""
'@
    Invoke-Compose exec -T $dbService sh -ec $databaseReset restore $timestamp

    Write-Host "[4/6] Restoring data..." -ForegroundColor Yellow
    Invoke-Compose exec -T $dbService sh -ec 'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$1"' restore $remoteFile

    Write-Host "[5/6] Applying forward migrations..." -ForegroundColor Yellow
    Invoke-Compose run --rm $migrateService

    Write-Host "[6/6] Starting '$workloadService' and waiting for readiness..." -ForegroundColor Yellow
    Invoke-Compose up -d --wait --wait-timeout $HealthTimeoutSeconds $workloadService
}
finally {
    & docker compose -f $ComposeFile --env-file $EnvFile exec -T $dbService rm -f $remoteFile 2>$null
}

Write-Host "Restore completed. The pre-restore database was retained with suffix '_pre_restore_${timestamp}'." -ForegroundColor Green
