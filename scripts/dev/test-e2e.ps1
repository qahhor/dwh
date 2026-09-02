# ============================================================================
# SmartupCMS - Browser E2E verification for an already running local stack
# ============================================================================
[CmdletBinding()]
param(
    [switch]$SkipInstall,
    [string]$InstanceBaseUrl = $env:INSTANCE_BASE_URL,
    [string]$InstanceHealthUrl = $env:INSTANCE_HEALTH_URL
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$e2eDirectory = Join-Path $root "e2e"

if ([string]::IsNullOrWhiteSpace($InstanceBaseUrl)) { $InstanceBaseUrl = "http://localhost:4200" }
if ([string]::IsNullOrWhiteSpace($InstanceHealthUrl)) { $InstanceHealthUrl = "http://localhost:9190/actuator/health" }
$env:INSTANCE_BASE_URL = $InstanceBaseUrl

function Invoke-CheckedStep {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    Write-Host "`n$Name" -ForegroundColor Yellow
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Assert-HttpReady {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Url
    )

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 10
        if ($response.StatusCode -ne 200) {
            throw "HTTP $($response.StatusCode)"
        }
        Write-Host "  [OK] $Name" -ForegroundColor Green
    } catch {
        throw "$Name is not ready at $Url. Start the clean Compose stack first. $($_.Exception.Message)"
    }
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  SmartupCMS - Browser E2E Suite                            " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

Assert-HttpReady "Instance UI" ($InstanceBaseUrl.TrimEnd('/') + '/')
Assert-HttpReady "Instance API" $InstanceHealthUrl

Write-Host "`nValidate PowerShell dotenv parser" -ForegroundColor Yellow
& (Join-Path $PSScriptRoot "test-dotenv-parser.ps1")

Push-Location $e2eDirectory
try {
    if (-not $SkipInstall) {
        Invoke-CheckedStep "Install pinned E2E dependencies" { npm ci }
        Invoke-CheckedStep "Install pinned Playwright Chromium" { npx playwright install chromium }
    }

    Invoke-CheckedStep "Validate E2E configuration contract" { npm run test:config }
    Invoke-CheckedStep "Type-check E2E sources" { npm run typecheck }
    Invoke-CheckedStep "Verify credential artifact redaction" { npm run test:artifact-security }
    Invoke-CheckedStep "Run browser E2E suite" { npm test }
} finally {
    Pop-Location
}

Write-Host "`nAll browser E2E scenarios passed." -ForegroundColor Green
