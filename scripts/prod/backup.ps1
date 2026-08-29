# ==============================================================================
# Smartup DWH / CMS Automated Backup Script (PowerShell)
# ==============================================================================
param(
    [string]$BackupDir = "./backups",
    [int]$RetentionDays = 30
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$backupFile = Join-Path $BackupDir "dwh_instance_$timestamp.sql"

Write-Host "Creating PostgreSQL backup to $backupFile..." -ForegroundColor Yellow
docker compose exec -T db pg_dump -U dwh dwh_instance > $backupFile

$hash = (Get-FileHash $backupFile -Algorithm SHA256).Hash
Set-Content -Path "$backupFile.sha256" -Value $hash

Write-Host "Backup completed successfully! SHA256: $hash" -ForegroundColor Green
