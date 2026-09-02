param(
    [string]$ProjectName = "smartupcms-v018-upgrade",
    [ValidateRange(1024, 65535)]
    [int]$HttpPort = 54211
)

$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$composeFile = Join-Path $repositoryRoot "deploy/compose/docker-compose.prod.yml"
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ("smartupcms-upgrade-" + [Guid]::NewGuid().ToString("N"))
$envFile = Join-Path $temporaryDirectory "upgrade.env"
$databasePasswordFile = Join-Path $temporaryDirectory "database-password"
$backupPasswordFile = Join-Path $temporaryDirectory "backup-database-password"
$composeArguments = @("compose", "-p", $ProjectName, "-f", $composeFile, "--env-file", $envFile)

function Invoke-Docker {
    param([string[]]$Arguments)

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: docker $($Arguments -join ' ')"
    }
}

function Invoke-Compose {
    param([string[]]$Arguments)

    Invoke-Docker -Arguments ($composeArguments + $Arguments)
}

function Get-SchemaVersion {
    $postgresId = (& docker @composeArguments ps -q postgres).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($postgresId)) {
        throw "PostgreSQL container is not running."
    }

    $version = (& docker exec $postgresId psql -U smartupcms -d smartupcms -Atc `
        "select version from flyway_schema_history where success order by installed_rank desc limit 1;").Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read the Flyway schema version."
    }
    return $version
}

try {
    Set-Location $repositoryRoot
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null

    $databasePassword = "Db!" + [Guid]::NewGuid().ToString("N") + "aA1"
    $backupPassword = "Backup!" + [Guid]::NewGuid().ToString("N") + "aA1"
    $adminPassword = "Admin!" + [Guid]::NewGuid().ToString("N") + "aA1"
    $typesenseKey = "Typesense-" + [Guid]::NewGuid().ToString("N")

    Invoke-Docker -Arguments @(
        "compose", "-f", "docker-compose.yml", "--profile", "tools", "--profile", "backup",
        "build", "server", "web", "postgres", "typesense", "backup"
    )
    $ageOutput = @(& docker run --rm --entrypoint age-keygen "smartupcms/backup:dev" 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not generate the disposable age recipient."
    }
    $recipientMatch = [regex]::Match(($ageOutput -join "`n"), 'age1[a-z0-9]+')
    if (-not $recipientMatch.Success) {
        throw "age-keygen did not return a public recipient."
    }

    [IO.File]::WriteAllText($databasePasswordFile, $databasePassword)
    [IO.File]::WriteAllText($backupPasswordFile, $backupPassword)
    $databaseSecretPath = $databasePasswordFile.Replace('\', '/')
    $backupSecretPath = $backupPasswordFile.Replace('\', '/')
    $envLines = @(
        "PROJECT_NAME=$ProjectName",
        "IMAGE_REGISTRY=smartupcms",
        "APP_VERSION=dev",
        "POSTGRES_IMAGE=smartupcms/postgres:18-alpine-hardened",
        "TYPESENSE_IMAGE=smartupcms/typesense:27.1-hardened",
        "ORGANIZATION_CODE=upgrade-test",
        "ORGANIZATION_NAME=Upgrade Test Organization",
        "DB_NAME=smartupcms",
        "DB_USER=smartupcms",
        "DB_PASSWORD=$databasePassword",
        "DB_PASSWORD_FILE=$databaseSecretPath",
        "BACKUP_DB_USER=smartupcms_backup",
        "BACKUP_DB_PASSWORD_FILE=$backupSecretPath",
        "ADMIN_LOGIN=admin",
        "ADMIN_EMAIL=admin@upgrade.test",
        "ADMIN_PASSWORD=$adminPassword",
        "TYPESENSE_API_KEY=$typesenseKey",
        "HTTP_BIND=127.0.0.1",
        "HTTP_PORT=$HttpPort",
        "DWH_PROVIDER_STORAGE=local_disk",
        "BACKUP_AGE_RECIPIENT=$($recipientMatch.Value)",
        "BACKUP_STORAGE_MODE=local",
        "BACKUP_INTERVAL_SECONDS=86400",
        "BACKUP_RETENTION_DAYS=2"
    )
    [IO.File]::WriteAllLines($envFile, $envLines)

    Invoke-Compose -Arguments @("config", "--quiet")
    Invoke-Compose -Arguments @("down", "--volumes", "--remove-orphans")
    Invoke-Compose -Arguments @("up", "-d", "--wait", "postgres", "typesense")
    Invoke-Compose -Arguments @("run", "--rm", "-e", "SPRING_FLYWAY_TARGET=018", "migrate")

    $before = Get-SchemaVersion
    if ($before -ne "018") {
        throw "Expected V018 before the upgrade, found '$before'."
    }

    Invoke-Compose -Arguments @("run", "--rm", "backup-bootstrap")
    Invoke-Compose -Arguments @("run", "--rm", "--no-deps", "-e", "BACKUP_RUN_ONCE=true", "backup")
    $backupAssertion = @'
test -n "$(find /backups -maxdepth 1 -type f -name '*.dump.age' -size +0c -print -quit)" && grep -q '"status":"SUCCESS"' /status/status.json
'@
    Invoke-Compose -Arguments @(
        "run", "--rm", "--no-deps", "--entrypoint", "sh", "backup", "-ec", $backupAssertion
    )

    Invoke-Compose -Arguments @("run", "--rm", "migrate")
    $after = Get-SchemaVersion
    if ($after -ne "019") {
        throw "Expected V019 after the upgrade, found '$after'."
    }

    Invoke-Compose -Arguments @("up", "-d", "--remove-orphans", "--wait")
    $runningServices = @(& docker @composeArguments ps --status running --services)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect production service state."
    }
    foreach ($service in @("postgres", "typesense", "server", "web", "backup")) {
        if ($runningServices -notcontains $service) {
            throw "Production service is not running after the upgrade: $service"
        }
    }

    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$HttpPort/healthz" -TimeoutSec 10
    if ($response.StatusCode -ne 200) {
        throw "Expected HTTP 200 from the unified origin, received $($response.StatusCode)."
    }

    Write-Host "V018 backup, V019 migration, production health checks, and unified-origin HTTP 200 passed." -ForegroundColor Green
}
finally {
    & docker @composeArguments down --volumes --remove-orphans *> $null
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
    Set-Location $repositoryRoot
    Write-Host "Removed disposable upgrade containers, network, volumes, and secret files." -ForegroundColor DarkGray
}
