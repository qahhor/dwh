param(
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

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file '$EnvFile' was not found."
}

try {
    Write-Host '[1/7] Validating the unified production configuration...' -ForegroundColor Yellow
    & docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose is unavailable.' }
    Invoke-Compose config --quiet

    Write-Host '[2/7] Pulling immutable release images...' -ForegroundColor Yellow
    Invoke-Compose pull

    Write-Host '[3/7] Creating the mandatory pre-migration backup when data exists...' -ForegroundColor Yellow
    $postgresId = & docker compose -f $ComposeFile --env-file $EnvFile ps -a -q postgres
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect PostgreSQL.' }
    if (-not [string]::IsNullOrWhiteSpace(($postgresId -join ''))) {
        & (Join-Path $PSScriptRoot 'backup.ps1') -ComposeFile $ComposeFile -EnvFile $EnvFile
        if ($LASTEXITCODE -ne 0) { throw 'Pre-migration backup failed.' }
    }
    else {
        Write-Host 'No existing PostgreSQL container found; treating this as an initial deployment.'
    }

    Write-Host '[4/7] Starting dependencies...' -ForegroundColor Yellow
    Invoke-Compose up -d --wait --wait-timeout $HealthTimeoutSeconds postgres typesense

    Write-Host '[5/7] Applying forward-only database migrations...' -ForegroundColor Yellow
    Invoke-Compose run --rm migrate

    Write-Host '[6/7] Creating or refreshing the dedicated read-only backup role...' -ForegroundColor Yellow
    Invoke-Compose run --rm backup-bootstrap

    Write-Host '[7/7] Starting SmartupCMS and waiting for readiness...' -ForegroundColor Yellow
    Invoke-Compose up -d --remove-orphans --wait --wait-timeout $HealthTimeoutSeconds
    Invoke-Compose ps
}
catch {
    Write-Error $_
    & docker compose -f $ComposeFile --env-file $EnvFile ps
    exit 1
}

Write-Host 'Deployment completed successfully.' -ForegroundColor Green
