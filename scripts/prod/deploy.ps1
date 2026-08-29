# ==============================================================================
# Smartup DWH / CMS Production Zero-Touch Deployment Orchestrator (PowerShell)
# ==============================================================================
param(
    [string]$ComposeFile = "deploy/compose/docker-compose.fleet.prod.yml",
    [string]$EnvFile = ".env.production"
)

$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Smartup DWH / CMS - Production Deployment Orchestrator    " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

if (-not (Test-Path $EnvFile)) {
    Write-Host "[ERROR] Environment file $EnvFile not found! Copy .env.prod.example to $EnvFile." -ForegroundColor Red
    exit 1
}

Write-Host "[1/5] Validating Docker Engine..." -ForegroundColor Yellow
docker compose version

Write-Host "[2/5] Executing Database Migrations (Instance & Control Plane)..." -ForegroundColor Yellow
docker compose -f $ComposeFile --env-file $EnvFile run --rm migrate
docker compose -f $ComposeFile --env-file $EnvFile run --rm migrate-cp

Write-Host "[3/5] Starting Fleet Services..." -ForegroundColor Yellow
docker compose -f $ComposeFile --env-file $EnvFile up -d --remove-orphans

Write-Host "[4/5] Verifying Service Health..." -ForegroundColor Yellow
Start-Sleep -Seconds 10
$status = docker compose -f $ComposeFile ps
Write-Host $status

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  Deployment Completed Successfully!                        " -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
