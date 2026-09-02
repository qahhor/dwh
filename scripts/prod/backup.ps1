param(
    [string]$ComposeFile = 'deploy/compose/docker-compose.prod.yml',
    [string]$EnvFile = '.env.production'
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

Invoke-Compose config --quiet
$postgresId = & docker compose -f $ComposeFile --env-file $EnvFile ps -a -q postgres
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($postgresId -join ''))) {
    throw 'PostgreSQL is not running; refusing to claim a backup.'
}

Write-Host 'Creating an encrypted one-shot backup...' -ForegroundColor Yellow
Invoke-Compose run --rm --no-deps -e BACKUP_RUN_ONCE=true backup
Write-Host 'Encrypted backup completed and status was updated.' -ForegroundColor Green
