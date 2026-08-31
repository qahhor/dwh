param(
    [string]$ComposeFile = "deploy/compose/docker-compose.fleet.prod.yml",
    [string]$EnvFile = ".env.production",
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

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file '$EnvFile' was not found."
}

try {
    Write-Host "[1/8] Validating Docker Compose and production configuration..." -ForegroundColor Yellow
    & docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose is unavailable."
    }
    Invoke-Compose config --quiet

    Write-Host "[2/8] Pulling immutable application and external runtime images..." -ForegroundColor Yellow
    Invoke-Compose pull --ignore-buildable

    Write-Host "[3/8] Building the hardened PostgreSQL runtime..." -ForegroundColor Yellow
    Invoke-Compose build --pull db typesense proxy

    Write-Host "[4/8] Creating mandatory backup for an existing fleet..." -ForegroundColor Yellow
    $instanceDbId = & docker compose -f $ComposeFile --env-file $EnvFile ps -a -q db
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect instance database service." }
    $cpDbId = & docker compose -f $ComposeFile --env-file $EnvFile ps -a -q db-cp
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect control-plane database service." }

    if (-not [string]::IsNullOrWhiteSpace((@($instanceDbId, $cpDbId) -join ""))) {
        & (Join-Path $PSScriptRoot "backup.ps1") -ComposeFile $ComposeFile -EnvFile $EnvFile
    }
    else {
        Write-Host "No existing database containers found; treating this as an initial deployment."
    }

    Write-Host "[5/8] Applying instance database migrations..." -ForegroundColor Yellow
    Invoke-Compose run --rm migrate

    Write-Host "[6/8] Applying control-plane database migrations..." -ForegroundColor Yellow
    Invoke-Compose run --rm migrate-cp

    Write-Host "[7/8] Starting the fleet and waiting for readiness..." -ForegroundColor Yellow
    Invoke-Compose up -d --remove-orphans --wait --wait-timeout $HealthTimeoutSeconds

    Write-Host "[8/8] Recording final service state..." -ForegroundColor Yellow
    Invoke-Compose ps
}
catch {
    Write-Error $_
    & docker compose -f $ComposeFile --env-file $EnvFile ps
    exit 1
}

Write-Host "Deployment completed successfully: migrations passed and all services are ready." -ForegroundColor Green
