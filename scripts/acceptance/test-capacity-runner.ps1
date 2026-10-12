param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner = Join-Path $PSScriptRoot 'run-capacity.ps1'
$testRoot = Join-Path $repoRoot ('.local/capacity-runner-test-' + [guid]::NewGuid().ToString('N'))
$dockerCommand = (Get-Command docker -CommandType Application | Select-Object -First 1).Source
$failures = [System.Collections.Generic.List[string]]::new()
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$usersPath = Join-Path $testRoot 'users.json'
$configPath = Join-Path $testRoot 'acceptance.env'
$metricsPath = Join-Path $testRoot 'metrics.json'
$evidencePath = Join-Path $testRoot 'evidence'
$cases = 0
$originalCulture = [Globalization.CultureInfo]::CurrentCulture
$global:capacityRunnerTest = @{
    LoadCalls = 0; Sample = $null; MetricsPath = $metricsPath
    Now = [datetime]'2026-10-12T12:00:00Z'
}

function Get-Date { $global:capacityRunnerTest.Now }

# NFR-PERF-01: simulate only the load boundary; execute the real acceptance runner and evidence validation.
function docker {
    $global:capacityRunnerTest.LoadCalls++
    $global:capacityRunnerTest.Sample | ConvertTo-Json | Set-Content -LiteralPath $global:capacityRunnerTest.MetricsPath -Encoding utf8NoBOM
    $global:LASTEXITCODE = 0
}

function Invoke-Case([string]$Name, [scriptblock]$Arrange, [string]$ExpectedError = '') {
    $script:cases++
    $global:capacityRunnerTest.LoadCalls = 0
    $global:capacityRunnerTest.Sample = [ordered]@{
        schemaVersion = 1
        windowStart = '2000-01-01T00:00:00Z'
        windowEnd = '2099-01-01T00:00:00Z'
        hikariPendingMax = 0
        heapPercentMax = 80
        freeDiskGiBMin = 5
        tempGrowthMiB = 0
        scannerP95Ms = 100
        r2P95Ms = 100
        orphanObjects = 0
        sourceDashboard = 'https://monitoring.example.test/capacity'
        collector = 'synthetic-runner-regression'
    }
    & $Arrange
    if (Test-Path -LiteralPath $evidencePath) { Remove-Item -LiteralPath $evidencePath -Recurse -Force }
    $message = ''
    try {
        & $runner -ConfigFile $configPath -UsersFile $usersPath -RuntimeMetricsFile $metricsPath `
            -Profile interactive -EvidenceDirectory $evidencePath -MetricsWaitSeconds 0 -AcknowledgeTargetLoad `
            *> (Join-Path $testRoot "$Name.log")
    }
    catch { $message = $_.Exception.Message }
    $manifests = @(Get-ChildItem -LiteralPath $evidencePath -Filter 'capacity-*.json' -ErrorAction SilentlyContinue)
    if ($ExpectedError) {
        if ($message -notmatch $ExpectedError -or $manifests.Count -ne 0) {
            $failures.Add("$Name expected rejection '$ExpectedError' without PASS evidence; got '$message', manifests=$($manifests.Count).")
        }
    }
    elseif ($message -or $manifests.Count -ne 1 -or $global:capacityRunnerTest.LoadCalls -ne 1) {
        $failures.Add("$Name expected one accepted profile; got '$message', manifests=$($manifests.Count), loads=$($global:capacityRunnerTest.LoadCalls).")
    }
}

try {
    @(1..100 | ForEach-Object { @{ token = [guid]::NewGuid().ToString('N') } }) |
        ConvertTo-Json | Set-Content -LiteralPath $usersPath -Encoding utf8NoBOM
    @'
ACCEPTANCE_TARGET_ENVIRONMENT=staging
ACCEPTANCE_PUBLIC_ORIGIN=https://unused.example.test
ACCEPTANCE_LOAD_WRITES=true
ACCEPTANCE_INTERACTIVE_DURATION=1s
ACCEPTANCE_MAX_P95_MS=300
ACCEPTANCE_MAX_P99_MS=1000
ACCEPTANCE_MAX_ERROR_RATE=0
ACCEPTANCE_MAX_HIKARI_PENDING=0
ACCEPTANCE_MAX_HEAP_PERCENT=80
ACCEPTANCE_MIN_FREE_DISK_GIB=5
ACCEPTANCE_MAX_TEMP_GROWTH_MIB=0
ACCEPTANCE_MAX_SCANNER_P95_MS=100
ACCEPTANCE_MAX_R2_P95_MS=100
ACCEPTANCE_MAX_ORPHAN_OBJECTS=0
'@ | Set-Content -LiteralPath $configPath -Encoding utf8NoBOM

    foreach ($culture in @('en-US', 'ru-RU')) {
        [Globalization.CultureInfo]::CurrentCulture = [Globalization.CultureInfo]::new($culture)
        Invoke-Case "utc-window-$culture" {
            $global:capacityRunnerTest.Sample.windowStart = $global:capacityRunnerTest.Now.AddMinutes(-1).ToString('o')
            $global:capacityRunnerTest.Sample.windowEnd = $global:capacityRunnerTest.Now.AddMinutes(1).ToString('o')
        }
    }
    [Globalization.CultureInfo]::CurrentCulture = $originalCulture
    Invoke-Case 'exact-thresholds' {}
    Invoke-Case 'temporary-files-shrank' { $global:capacityRunnerTest.Sample.tempGrowthMiB = -10 }
    Invoke-Case 'incomplete-window' {
        $global:capacityRunnerTest.Sample.windowStart = $global:capacityRunnerTest.Now.AddMinutes(10).ToString('o')
    } 'complete k6 load window'
    foreach ($metric in @('hikariPendingMax', 'heapPercentMax', 'freeDiskGiBMin', 'tempGrowthMiB', 'scannerP95Ms', 'r2P95Ms', 'orphanObjects')) {
        Invoke-Case "nonfinite-$metric" { $global:capacityRunnerTest.Sample[$metric] = 'NaN' } 'must be .*finite'
    }
    Invoke-Case 'missing-metric' { $global:capacityRunnerTest.Sample.Remove('scannerP95Ms') } 'missing'
    Invoke-Case 'null-metric' { $global:capacityRunnerTest.Sample.r2P95Ms = $null } 'missing'
    Invoke-Case 'pool-over-limit' { $global:capacityRunnerTest.Sample.hikariPendingMax = 1 } 'violates'
    Invoke-Case 'disk-under-limit' { $global:capacityRunnerTest.Sample.freeDiskGiBMin = 4 } 'violates'
    Invoke-Case 'negative-pool' { $global:capacityRunnerTest.Sample.hikariPendingMax = -1 } 'must be non-negative'
    Invoke-Case 'empty-metric' { $global:capacityRunnerTest.Sample.hikariPendingMax = '' } 'must be .*finite'
    Invoke-Case 'blank-metric' { $global:capacityRunnerTest.Sample.scannerP95Ms = ' ' } 'must be .*finite'

    # Use the real pinned k6 threshold evaluator without any HTTP request or staging connection.
    $image = 'docker.io/grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d'
    foreach ($probe in @(
        @{ Name = 'boundary'; Latency = '300'; Failed = 'false'; Exit = 0 },
        @{ Name = 'latency-over'; Latency = '301'; Failed = 'false'; Exit = 99 },
        @{ Name = 'request-failed'; Latency = '300'; Failed = 'true'; Exit = 99 }
    )) {
        $cases++
        & $dockerCommand run --rm --network none --mount "type=bind,src=$PSScriptRoot,dst=/scripts,readonly" `
            --mount "type=bind,src=$testRoot,dst=/fixtures,readonly" `
            -e ACCEPTANCE_PUBLIC_ORIGIN=https://unused.example.test -e LOAD_USERS_FILE=/fixtures/users.json `
            -e ACCEPTANCE_MAX_P95_MS=300 -e ACCEPTANCE_MAX_P99_MS=300 -e ACCEPTANCE_MAX_ERROR_RATE=0 `
            -e "PROBE_LATENCY_MS=$($probe.Latency)" -e "PROBE_FAILED=$($probe.Failed)" `
            $image run --quiet /scripts/tests/thresholds.js *> (Join-Path $testRoot "$($probe.Name)-k6.log")
        if ($LASTEXITCODE -ne $probe.Exit) {
            $failures.Add("k6 $($probe.Name): expected exit $($probe.Exit), got $LASTEXITCODE.")
        }
    }
    if ($failures.Count) { throw "Capacity runner regressions failed:`n - $($failures -join "`n - ")" }
    Write-Host "Capacity runner regressions passed ($cases cases; synthetic load boundary, real k6 threshold evaluation)."
}
finally {
    [Globalization.CultureInfo]::CurrentCulture = $originalCulture
    $expectedParent = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '.local'))
    if ([System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($testRoot)) -ne $expectedParent) {
        throw 'Refusing to clean a test directory outside .local.'
    }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
    Remove-Variable -Name capacityRunnerTest -Scope Global
}
exit 0
