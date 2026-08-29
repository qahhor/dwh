# ==============================================================================
# Smartup DWH / CMS Automated Restore Script (PowerShell)
# ==============================================================================
param(
    [Parameter(Mandatory=$true)]
    [string]$BackupFile
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $BackupFile)) {
    Write-Host "[ERROR] Backup file $BackupFile does not exist!" -ForegroundColor Red
    exit 1
}

Write-Host "[1/3] Terminating active connections and recreating database..." -ForegroundColor Yellow
docker compose exec -T db psql -U dwh -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'dwh_instance' AND pid <> pg_backend_pid();"
docker compose exec -T db psql -U dwh -d postgres -c "DROP DATABASE IF EXISTS dwh_instance;"
docker compose exec -T db psql -U dwh -d postgres -c "CREATE DATABASE dwh_instance OWNER dwh;"

Write-Host "[2/3] Restoring SQL dump..." -ForegroundColor Yellow
Get-Content $BackupFile | docker compose exec -T db psql -U dwh -d dwh_instance

Write-Host "[3/3] Verifying schema version..." -ForegroundColor Yellow
docker compose run --rm migrate

Write-Host "Database restore completed successfully!" -ForegroundColor Green
