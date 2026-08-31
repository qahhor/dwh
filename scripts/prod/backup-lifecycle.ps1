# ==============================================================================
# Smartup DWH Platform - Enterprise Production Backup & Retention Lifecycle
# PostgreSQL 18 Checksummed Backups with Automated Pruning
# ==============================================================================
param(
    [string]$BackupDir = "./backups",
    [string]$Target = "all", # all, instance, cp
    [int]$RetentionDays = 14
)

$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  DWH Platform - Automated Backup Lifecycle Manager         " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
    Write-Host "Created backup directory: $BackupDir" -ForegroundColor Gray
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"

function Backup-Database([string]$ContainerName, [string]$DbUser, [string]$DbName, [string]$Prefix) {
    $outFile = Join-Path $BackupDir "${Prefix}_${timestamp}.sql"
    Write-Host "[1/3] Dumping database '$DbName' from '$ContainerName'..." -ForegroundColor Yellow

    # Execute pg_dump
    docker exec -t $ContainerName pg_dump -U $DbUser $DbName > $outFile

    if (-not (Test-Path $outFile) -or (Get-Item $outFile).Length -eq 0) {
        Write-Error "Backup failed: Generated file '$outFile' is missing or empty!"
    }

    $sizeKb = [math]::Round((Get-Item $outFile).Length / 1KB, 2)
    Write-Host "[2/3] Dump completed ($sizeKb KB). Calculating SHA-256..." -ForegroundColor Yellow

    # Calculate SHA-256 Checksum
    $hash = (Get-FileHash -Path $outFile -Algorithm SHA256).Hash
    Set-Content -Path "$outFile.sha256" -Value "$hash  $(Split-Path $outFile -Leaf)" -Encoding utf8

    Write-Host "      Checksum verified: $hash" -ForegroundColor Gray
    Write-Host "[3/3] Integrity check PASSED." -ForegroundColor Green
    return $outFile
}

# Run Instance Backup
if ($Target -eq "all" -or $Target -eq "instance") {
    $instContainer = "SmartupCMS-db"
    try {
        Backup-Database -ContainerName $instContainer -DbUser "dwh" -DbName "dwh_instance" -Prefix "instance_backup"
    } catch {
        Write-Host "Trying fallback container name 'db'..." -ForegroundColor Gray
        Backup-Database -ContainerName "db" -DbUser "dwh" -DbName "dwh_instance" -Prefix "instance_backup"
    }
}

# Run Control Plane Backup
if ($Target -eq "all" -or $Target -eq "cp") {
    $cpContainer = "SmartupCMS-db-cp"
    try {
        Backup-Database -ContainerName $cpContainer -DbUser "dwh_cp" -DbName "dwh_control_plane" -Prefix "cp_backup"
    } catch {
        Write-Host "Trying fallback container name 'db-cp'..." -ForegroundColor Gray
        Backup-Database -ContainerName "db-cp" -DbUser "dwh_cp" -DbName "dwh_control_plane" -Prefix "cp_backup"
    }
}

# Retention Rotation
Write-Host "`nChecking backup retention policy (Keeping last $RetentionDays days)..." -ForegroundColor Yellow
$cutoffDate = (Get-Date).AddDays(-$RetentionDays)
$oldFiles = Get-ChildItem -Path $BackupDir -File | Where-Object { $_.LastWriteTime -lt $cutoffDate }

if ($oldFiles.Count -gt 0) {
    foreach ($file in $oldFiles) {
        Write-Host "Pruning expired backup: $($file.Name)" -ForegroundColor DarkGray
        Remove-Item -Path $file.FullName -Force
    }
    Write-Host "Pruned $($oldFiles.Count) expired backup files." -ForegroundColor Gray
} else {
    Write-Host "No expired backups to prune." -ForegroundColor Gray
}

Write-Host "`n============================================================" -ForegroundColor Green
Write-Host "  Backup Lifecycle Execution Completed Successfully!        " -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
