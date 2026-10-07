param(
    [switch]$Quick
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# The wrapper on Windows; the runner's Maven on Linux (the nightly workflow), as in ci.yml.
$mvnCmd = if ($env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'mvnw.cmd' } else { 'mvn' }
# The reactor root: the server is built with the libs it depends on (-pl apps/server -am).
$pomPath = Join-Path $repoRoot 'pom.xml'
$migrationFile = Join-Path $repoRoot 'apps/server/src/main/resources/db/migration/V034__task_revision_and_drop_duplicate_indexes.sql'

Write-Host "=== Starting Measured Database / Concurrency Improvements Drill (ADR-0024) ===" -ForegroundColor Cyan

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
    # 1. Migration Verification: V034 exists and contains revision & drop index statements
    Write-Host "`n--> [Validating Flyway V034 Migration File]" -ForegroundColor Yellow
    if (-not (Test-Path $migrationFile)) {
        throw "Migration file not found: $migrationFile"
    }
    $migrationContent = Get-Content $migrationFile -Raw
    if (-not ($migrationContent -match 'drop index if exists idx_ms_tasks_status_id' -and
              $migrationContent -match 'drop index if exists idx_ms_tasks_project_id' -and
              $migrationContent -match 'add column if not exists revision bigint not null default 1')) {
        throw "Migration file $migrationFile does not contain expected DDL statements"
    }
    Write-Host "PASSED (V034 syntax verified)" -ForegroundColor Green

    # 2. Duplicate Index Drop & Auth Coalescing & OCC Integration Suite
    Invoke-MavenTests -TestPattern 'TaskConcurrencyIntegrationTest' `
        -Description 'Real PostgreSQL 18 OCC, duplicate index drops, and write coalescing'

    # 3. Auth Filter DB Outage Resilience (DataAccessException propagation)
    Invoke-MavenTests -TestPattern 'KauthAuthenticationFilterTest' `
        -Description 'Auth filter propagates DataAccessException without false 401 logouts'

    if (-not $Quick) {
        # 4. Existing Task Service and Patch Regression Suite
        Invoke-MavenTests -TestPattern 'MsTaskEntityIntegrationTest,MsTaskContractTest,MsTaskCommentServiceTest' `
            -Description 'Regression check: Task patch, hierarchy, permissions, and comments compatibility'
    }

    Write-Host "`n=== All Measured Database / Concurrency Improvements Verified Successfully! ===" -ForegroundColor Green
}
catch {
    Write-Host "`nVerification Drill Failed: $_" -ForegroundColor Red
    exit 1
}
