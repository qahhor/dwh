param(
    [switch]$Quick
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
# The wrapper on Windows; the runner's Maven on Linux (the nightly workflow), as in ci.yml.
$mvnCmd = if ($env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'mvnw.cmd' } else { 'mvn' }
# The reactor root: the server is built with the libs it depends on (-pl apps/server -am).
$pomPath = Join-Path $repoRoot 'pom.xml'

Write-Host "=== Starting Search Reliability Verification Drill (FR-SEARCH-01) ===" -ForegroundColor Cyan

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
    # 1. Architectural Boundary Verification: External modules cannot touch TypesenseClient directly
    Invoke-MavenTests -TestPattern 'ModularArchitectureTest#externalModulesShouldNotDependOnTypesenseClientDirectly' `
        -Description 'Verifying Architectural Boundary: TypesenseClient isolation'

    # 2. Contract 1: Transactional Mutation Safety (No index before commit, rollback atomicity)
    Invoke-MavenTests -TestPattern 'SearchRevisionIntegrationTest,SearchDeliveryIntegrationTest#workerCannotSeeTaskCreatedInsideHeldBusinessTransaction' `
        -Description 'Contract 1: Transactional mutation isolation (No indexing before commit, rollback cleans up revision)'

    # 3. Contract 2: Durable Bounded Retry (Exponential backoff, jitter, attempt bound)
    Invoke-MavenTests -TestPattern 'SearchDeliveryIntegrationTest#eighthFailureIsTerminalForThatRevisionAndNewRevisionResetsFailures,SearchDeliveryIntegrationTest#nextCycleReleasesOnlyItsOwnClaimLeftAfterDatabaseCheckpointFailure' `
        -Description 'Contract 2: Durable bounded retry (Exponential backoff, 8-attempt bound, checkpoint release)'

    # 4. Contract 3: Non-blocking Startup & Outage Recovery
    Invoke-MavenTests -TestPattern 'SearchBootstrapIntegrationTest#startupOutageRecoversAndMissingCollectionsUseOneGeneratedInitialGeneration,SearchBootstrapIntegrationTest#backgroundNetworkStartsOnlyAfterCommittedBootstrapAndDoesNotBlockApplicationRunner' `
        -Description 'Contract 3: Startup resilience (ApplicationRunner never blocked, catch-up after Typesense recovery)'

    if (-not $Quick) {
        # 5. Reconciliation & Index Convergence (Rebuild catchup, Rollback safety, Memory-bounded stream)
        Invoke-MavenTests -TestPattern 'SearchReconciliationTest,SearchRebuildIntegrationTest,SearchRollbackIntegrationTest' `
            -Description 'Reconciliation convergence: Bounded memory streaming, rebuild catchup, rollback safety'
    }

    Write-Host "`n=== All Search Reliability Contracts Verified Successfully ===" -ForegroundColor Green
}
catch {
    Write-Host "`n=== Search Reliability Verification FAILED ===" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
