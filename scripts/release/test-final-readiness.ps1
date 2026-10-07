param(
    [switch]$Quick
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# The wrapper on Windows; the runner's Maven on Linux (the nightly workflow), as in ci.yml.
$mvnCmd = if ($env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'mvnw.cmd' } else { 'mvn' }
# The reactor root: the server is built with the libs it depends on (-pl apps/server -am).
$pomPath = Join-Path $repoRoot 'pom.xml'

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " Running Master Final Release Readiness Quality Gates Drill" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

function Run-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host "`n--> [$Name]" -ForegroundColor Yellow
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $Action
        $sw.Stop()
        Write-Host "PASSED in $($sw.Elapsed.TotalSeconds.ToString('F1'))s" -ForegroundColor Green
    }
    catch {
        $sw.Stop()
        Write-Host "FAILED in $($sw.Elapsed.TotalSeconds.ToString('F1'))s: $_" -ForegroundColor Red
        throw $_
    }
}

try {
    # 1. Documentation Contract
    Run-Step "Public Documentation & Tracked Links Contract" {
        & (Join-Path $repoRoot 'scripts/docs/test-public-docs.ps1')
    }

    # 2. Repository Hygiene Contract
    Run-Step "Repository Hygiene & Cleanliness Contract" {
        & (Join-Path $repoRoot 'scripts/docs/test-repository-hygiene.ps1')
    }

    # 3. Production Compose Resource Limits & Config Verification
    Run-Step "Production Resource Limits & Release Configuration (NFR-SEC-02)" {
        & (Join-Path $repoRoot 'scripts/prod/test-release-config.ps1')
    }

    # 4. Git History Secret Scanning Contract
    Run-Step "Git History Secret Scanning (Gitleaks Zero-Finding Contract)" {
        & (Join-Path $repoRoot 'scripts/security/test-secret-scan.ps1')
    }

    # 5. Release Supply Chain Verification Contract
    Run-Step "Release Supply Chain Verification (Provenance & Signatures Gate)" {
        & (Join-Path $repoRoot 'scripts/release/verify-release.ps1')
    }

    # 6. OpenAPI contract: the committed docs/api/openapi.json matches the code
    Run-Step "OpenAPI 3.1.0 Contract & Core Route Catalog" {
        # The libraries built with -am have no such class, hence failIfNoSpecifiedTests=false; the report of the
        # server module proves the class exists and ran, so a renamed or removed test turns this step red.
        $report = Join-Path $repoRoot 'apps/server/target/surefire-reports/TEST-com.smartup24.cms.instance.config.openapi.OpenApiContractTest.xml'
        if (Test-Path -LiteralPath $report) { Remove-Item -LiteralPath $report -Force }
        $cmd = "& `"$mvnCmd`" test -f `"$pomPath`" -pl apps/server -am -B -q `"-Dtest=OpenApiContractTest`" `"-Dsurefire.failIfNoSpecifiedTests=false`""
        $output = Invoke-Expression $cmd 2>&1
        if ($LASTEXITCODE -ne 0) {
            $output | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkRed }
            throw "OpenApiContractTest failed"
        }
        if (-not (Test-Path -LiteralPath $report)) {
            throw "OpenApiContractTest did not run: no report at $report"
        }
    }

    # 7. Search Reliability & Transactional Outbox (FR-SEARCH-01)
    Run-Step "Search Reliability & Outbox Synchronization Drill (FR-SEARCH-01)" {
        $script = Join-Path $repoRoot 'scripts/search/test-search-reliability.ps1'
        if ($Quick) { & $script -Quick } else { & $script }
    }

    # 8. Database Concurrency, Duplicate Indexes & OCC (ADR-0024)
    Run-Step "Database Concurrency, OCC & Index Cleanup Drill (ADR-0024)" {
        $script = Join-Path $repoRoot 'scripts/db/test-db-concurrency.ps1'
        if ($Quick) { & $script -Quick } else { & $script }
    }

    # 9. Capacity Guards & Operations Evidence (NFR-PERF-01)
    Run-Step "Capacity Guards, Upload Limiter & Export Bounds Drill (NFR-PERF-01)" {
        $script = Join-Path $repoRoot 'scripts/acceptance/test-capacity-guards.ps1'
        if ($Quick) { & $script -Quick } else { & $script }
    }

    Write-Host "`n=================================================================" -ForegroundColor Green
    Write-Host " ALL RELEASE HARDENING GATES VERIFIED SUCCESSFULLY! " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Green
}
catch {
    Write-Host "`nFinal Release Readiness Verification Drill Failed: $_" -ForegroundColor Red
    exit 1
}
