param(
    [string]$ComposeFile = "deploy/compose/docker-compose.fleet.prod.yml",
    [string]$EnvFile = ".env.production",
    [string]$BackupDir = "./backups",
    [ValidateSet("all", "instance", "cp")]
    [string]$Target = "all",
    [ValidateRange(0, 3650)]
    [int]$RetentionDays = 30
)

$ErrorActionPreference = "Stop"

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArguments)

    & docker compose -f $ComposeFile --env-file $EnvFile @ComposeArguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed: $($ComposeArguments -join ' ')"
    }
}

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file '$EnvFile' was not found."
}

if (-not (Test-Path -LiteralPath $BackupDir -PathType Container)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

Invoke-Compose config --quiet
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")

function Backup-Database([string]$Service, [string]$Prefix) {
    $containerId = & docker compose -f $ComposeFile --env-file $EnvFile ps -a -q $Service
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect database service '$Service'."
    }
    if ([string]::IsNullOrWhiteSpace(($containerId -join ""))) {
        throw "Database service '$Service' does not exist; refusing to claim a backup."
    }

    $fileName = "${Prefix}_${timestamp}.dump"
    $backupFile = Join-Path $BackupDir $fileName
    $partialFile = "${backupFile}.partial"
    $containerTemp = "/tmp/${fileName}.partial"

    Write-Host "Backing up '$Service' to '$backupFile'..." -ForegroundColor Yellow
    if (Test-Path -LiteralPath $partialFile) {
        Remove-Item -LiteralPath $partialFile -Force
    }

    try {
        Invoke-Compose exec -T $Service sh -ec 'exec pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" --format=custom --file="$1"' backup $containerTemp
        Invoke-Compose cp "${Service}:${containerTemp}" $partialFile
    }
    finally {
        & docker compose -f $ComposeFile --env-file $EnvFile exec -T $Service rm -f $containerTemp 2>$null
    }

    if (-not (Test-Path -LiteralPath $partialFile -PathType Leaf) -or (Get-Item -LiteralPath $partialFile).Length -eq 0) {
        if (Test-Path -LiteralPath $partialFile) {
            Remove-Item -LiteralPath $partialFile -Force
        }
        throw "Backup for '$Service' is empty."
    }

    Move-Item -LiteralPath $partialFile -Destination $backupFile
    $hash = (Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "${backupFile}.sha256" -Value "$hash  $fileName" -Encoding ascii
    $verifiedHash = (Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($verifiedHash -ne $hash) {
        throw "Checksum verification failed for '$backupFile'."
    }
    Write-Host "Backup verified: '$backupFile'." -ForegroundColor Green
}

if ($Target -in @("all", "instance")) {
    Backup-Database -Service "db" -Prefix "instance"
}
if ($Target -in @("all", "cp")) {
    Backup-Database -Service "db-cp" -Prefix "control-plane"
}

$cutoff = (Get-Date).AddDays(-$RetentionDays)
Get-ChildItem -LiteralPath $BackupDir -File |
    Where-Object { $_.Name -match '\.dump(?:\.sha256)?$' -and $_.LastWriteTime -lt $cutoff } |
    Remove-Item -Force

Write-Host "Backup lifecycle completed successfully." -ForegroundColor Green
