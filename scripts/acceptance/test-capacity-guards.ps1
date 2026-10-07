param(
    [switch]$Quick
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# The wrapper on Windows; the runner's Maven on Linux (the nightly workflow), as in ci.yml.
$mvnCmd = if ($env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'mvnw.cmd' } else { 'mvn' }
# The reactor root: the server is built with the libs it depends on (-pl apps/server -am).
$pomPath = Join-Path $repoRoot 'pom.xml'
$appYmlPath = Join-Path $repoRoot 'apps/server/src/main/resources/application.yml'
$releaseConfigScript = Join-Path $repoRoot 'scripts/prod/test-release-config.ps1'

Write-Host "=== Starting Capacity & Operations Evidence Drill (NFR-PERF-01) ===" -ForegroundColor Cyan

function Invoke-MavenTests {
    param(
        [string]$TestPattern,
        [string]$Description
    )

    Write-Host "`n--> [$Description]" -ForegroundColor Yellow
    $startTime = [System.Diagnostics.Stopwatch]::StartNew()

    # The libraries built with -am have none of these classes, hence failIfNoSpecifiedTests=false; the reports of
    # the server module below prove that every named class exists and ran.
    $since = Get-Date
    $cmd = "& `"$mvnCmd`" test -f `"$pomPath`" -pl apps/server -am -B -q `"-Dtest=$TestPattern`" `"-Dsurefire.failIfNoSpecifiedTests=false`""
    
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = Invoke-Expression $cmd 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevEap

    $startTime.Stop()

    if ($exitCode -ne 0) {
        Write-Host "FAILED in $($startTime.Elapsed.TotalSeconds.ToString('F1'))s" -ForegroundColor Red
        $output | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkRed }
        throw "Test verification failed for: $Description"
    }
    $reports = Join-Path $repoRoot 'apps/server/target/surefire-reports'
    foreach ($class in @($TestPattern -split ',' | ForEach-Object { ($_ -split '#')[0].Trim() } | Select-Object -Unique)) {
        $report = Get-ChildItem -LiteralPath $reports -Filter "TEST-*.$class.xml" -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -ge $since } | Select-Object -First 1
        if (-not $report -or -not (Select-String -LiteralPath $report.FullName -Pattern 'tests="[1-9]' -Quiet)) {
            throw "Test verification failed for: $Description ($class did not run: renamed or removed?)"
        }
    }
    Write-Host "PASSED in $($startTime.Elapsed.TotalSeconds.ToString('F1'))s" -ForegroundColor Green
}

try {
    # 1. Config Verification: application.yml contains fetch size, upload concurrency, and export max rows
    Write-Host "`n--> [Validating application.yml Capacity Settings]" -ForegroundColor Yellow
    if (-not (Test-Path $appYmlPath)) {
        throw "application.yml not found: $appYmlPath"
    }
    $appYml = Get-Content $appYmlPath -Raw
    if (-not ($appYml -match 'defaultRowFetchSize:\s*500' -and
              $appYml -match 'max-concurrent-uploads' -and
              $appYml -match 'max-rows')) {
        throw "application.yml does not contain expected capacity parameters (fetchSize, max-concurrent-uploads, max-rows)"
    }
    Write-Host "PASSED (application.yml capacity settings verified)" -ForegroundColor Green

    # 2. Release & Docker Resource Configuration (NFR-SEC-02: CPU/RAM bounds & tmpfs)
    Write-Host "`n--> [Running Release Configuration & Resource Limits Verifier]" -ForegroundColor Yellow
    & $releaseConfigScript
    if ($LASTEXITCODE -ne 0) {
        throw "Release configuration verifier failed"
    }
    Write-Host "PASSED (Container resource limits & tmpfs bounded)" -ForegroundColor Green

    # 3. Capacity Guards Integration Test (real Postgres 18)
    Invoke-MavenTests -TestPattern 'CapacityGuardsIntegrationTest' `
        -Description 'Real PostgreSQL 18 export bounds, disconnect handling, upload rate limit, audit cache'

    if (-not $Quick) {
        # 4. Report Export Integration Suite
        Invoke-MavenTests -TestPattern 'ReportExportIntegrationTest' `
            -Description 'Regression check: Report CSV export correctness, filters, and streaming'

        # 5. Audit Log & File Service Unit Test Suite
        Invoke-MavenTests -TestPattern 'AuditLogServiceTest,MfFileServiceTest,MfFileTransactionBoundaryTest' `
            -Description 'Unit checks: Audit stats caching and file upload transaction boundaries'
    }

    Write-Host "`n=== All Capacity & Operations Evidence Verified Successfully! ===" -ForegroundColor Green
}
catch {
    Write-Host "`nCapacity Verification Drill Failed: $_" -ForegroundColor Red
    exit 1
}
