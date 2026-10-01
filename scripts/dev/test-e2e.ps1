# ============================================================================
# SmartupCMS - Browser E2E verification for an already running local stack
# ============================================================================
# CI runs this script too (ci.yml, job e2e): -SkipInstall after its own install, -Shard to split the suite.
# The stack carries the mail stub (plan 10/10, item 0.8); start it with both Compose files:
#   docker compose -f docker-compose.yml -f scripts/dev/e2e-mail.compose.yml up -d --wait
# -CheckReadiness ends the run with scripts/dev/test-readiness-dependency.ps1, which stops and restarts postgres.
[CmdletBinding()]
param(
    [switch]$SkipInstall,
    [string]$InstanceBaseUrl = $env:INSTANCE_BASE_URL,
    [string]$InstanceHealthUrl = $env:INSTANCE_HEALTH_URL,
    # Mailpit's HTTP API, published by scripts/dev/e2e-mail.compose.yml (MAILPIT_HTTP_PORT, default 8025).
    [string]$MailpitUrl = $env:MAILPIT_URL,
    [switch]$CheckReadiness,
    # A part of the suite, as Playwright takes it: "1/2" runs the first half of the spec files.
    [ValidatePattern('^\d+/\d+$')]
    [string]$Shard
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$e2eDirectory = Join-Path $root "e2e"

if ([string]::IsNullOrWhiteSpace($InstanceBaseUrl)) { $InstanceBaseUrl = "http://localhost:4200" }
if ([string]::IsNullOrWhiteSpace($InstanceHealthUrl)) { $InstanceHealthUrl = $InstanceBaseUrl.TrimEnd('/') + '/healthz' }
if ([string]::IsNullOrWhiteSpace($MailpitUrl)) { $MailpitUrl = "http://localhost:8025" }
$env:INSTANCE_BASE_URL = $InstanceBaseUrl
$env:MAILPIT_URL = $MailpitUrl

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

Assert-HttpReady "SmartupCMS web origin" ($InstanceBaseUrl.TrimEnd('/') + '/')
Assert-HttpReady "SmartupCMS health through the web origin" $InstanceHealthUrl
Assert-HttpReady "Mailpit mail stub (scripts/dev/e2e-mail.compose.yml)" ($MailpitUrl.TrimEnd('/') + '/readyz')

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
    if ($Shard) {
        Invoke-CheckedStep "Run browser E2E suite, shard $Shard" { npm test -- "--shard=$Shard" }
    } else {
        Invoke-CheckedStep "Run browser E2E suite" { npm test }
    }
} finally {
    Pop-Location
}

if ($CheckReadiness) {
    Invoke-CheckedStep "Verify readiness follows the main database" { & (Join-Path $PSScriptRoot "test-readiness-dependency.ps1") }
}

Write-Host "`nAll browser E2E scenarios passed." -ForegroundColor Green
