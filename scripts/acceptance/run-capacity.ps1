param(
    [Parameter(Mandatory = $true)][string]$ConfigFile,
    [Parameter(Mandatory = $true)][string]$UsersFile,
    [Parameter(Mandatory = $true)][string]$RuntimeMetricsFile,
    [ValidateSet('interactive', 'upload', 'soak', 'all')][string]$Profile = 'all',
    [string]$EvidenceDirectory = 'output/managed-acceptance',
    [ValidateRange(0, 3600)][int]$MetricsWaitSeconds = 300,
    [switch]$AcknowledgeTargetLoad
)

$ErrorActionPreference = 'Stop'
$k6Image = 'docker.io/grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d'

function Read-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Configuration not found: $Path" }
    $values = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { throw 'Acceptance configuration contains an invalid line.' }
        $values[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
    }
    return $values
}

function Get-Required([hashtable]$Values, [string]$Name) {
    $value = "$($Values[$Name])".Trim()
    if (-not $value -or $value.Contains('CHANGE_ME')) { throw "Required value '$Name' is not configured." }
    return $value
}

function Get-Threshold([hashtable]$Values, [string]$Name, [double]$Minimum, [double]$Maximum) {
    $raw = Get-Required $Values $Name
    $parsed = 0.0
    if (-not [double]::TryParse($raw, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
        throw "Threshold '$Name' is not numeric."
    }
    if ([double]::IsNaN($parsed) -or [double]::IsInfinity($parsed)) {
        throw "Threshold '$Name' must be finite."
    }
    if ($parsed -lt $Minimum -or $parsed -gt $Maximum) { throw "Threshold '$Name' is outside the accepted range." }
    return $parsed
}

if (-not $AcknowledgeTargetLoad) {
    throw 'Capacity tests can modify staging data. Re-run with -AcknowledgeTargetLoad after confirming the target.'
}

$config = Read-DotEnv $ConfigFile
if ((Get-Required $config 'ACCEPTANCE_TARGET_ENVIRONMENT') -ne 'staging') {
    throw 'Capacity acceptance is restricted to an explicitly labelled staging environment.'
}
if ((Get-Required $config 'ACCEPTANCE_LOAD_WRITES') -ne 'true') {
    throw 'ACCEPTANCE_LOAD_WRITES=true is required to exercise the approved read/write profile.'
}

$thresholdNames = @(
    'ACCEPTANCE_MAX_P95_MS', 'ACCEPTANCE_MAX_P99_MS', 'ACCEPTANCE_MAX_ERROR_RATE',
    'ACCEPTANCE_MAX_HIKARI_PENDING', 'ACCEPTANCE_MAX_HEAP_PERCENT',
    'ACCEPTANCE_MIN_FREE_DISK_GIB', 'ACCEPTANCE_MAX_TEMP_GROWTH_MIB',
    'ACCEPTANCE_MAX_SCANNER_P95_MS', 'ACCEPTANCE_MAX_R2_P95_MS',
    'ACCEPTANCE_MAX_ORPHAN_OBJECTS'
)
$thresholds = @{}
foreach ($name in $thresholdNames) {
    $maximum = if ($name -eq 'ACCEPTANCE_MAX_ERROR_RATE') { 1.0 } else { [double]::MaxValue }
    $minimum = if ($name -eq 'ACCEPTANCE_MIN_FREE_DISK_GIB') { 0.001 } else { 0.0 }
    $thresholds[$name] = Get-Threshold $config $name $minimum $maximum
}

$usersPath = (Resolve-Path -LiteralPath $UsersFile).Path
$users = Get-Content -LiteralPath $usersPath -Raw | ConvertFrom-Json
if (@($users).Count -lt 100) { throw 'At least 100 distinct load-test API tokens are required.' }
$uniqueTokens = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($user in @($users)) {
    $token = "$($user.token)".Trim()
    if ($token.Length -lt 20) { throw 'Every load-test user requires a non-empty API token.' }
    if (-not $uniqueTokens.Add($token)) { throw 'Every load-test user requires a distinct API token.' }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$loadDirectory = (Resolve-Path (Join-Path $PSScriptRoot 'load')).Path
$evidencePath = if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) {
    [System.IO.Path]::GetFullPath($EvidenceDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDirectory))
}
New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('smartupcms-capacity-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
$fixturePath = Join-Path $fixtureRoot 'near-limit.pdf'
$fixtureSize = 50MB - 64KB
$stream = [System.IO.File]::Open($fixturePath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
try {
    $header = [System.Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n")
    $stream.Write($header, 0, $header.Length)
    $stream.SetLength($fixtureSize)
}
finally { $stream.Dispose() }

$profiles = if ($Profile -eq 'all') { @('interactive', 'upload', 'soak') } else { @($Profile) }
$results = [System.Collections.Generic.List[object]]::new()
$loadStartedAt = (Get-Date).ToUniversalTime()
try {
    foreach ($selectedProfile in $profiles) {
        $scriptName = if ($selectedProfile -eq 'upload') { 'upload-contention.js' } else { "$selectedProfile.js" }
        $summaryName = "$selectedProfile-summary.json"
        $environment = @{
            ACCEPTANCE_PUBLIC_ORIGIN = Get-Required $config 'ACCEPTANCE_PUBLIC_ORIGIN'
            ACCEPTANCE_MAX_P95_MS = "$($thresholds['ACCEPTANCE_MAX_P95_MS'])"
            ACCEPTANCE_MAX_P99_MS = "$($thresholds['ACCEPTANCE_MAX_P99_MS'])"
            ACCEPTANCE_MAX_ERROR_RATE = "$($thresholds['ACCEPTANCE_MAX_ERROR_RATE'])"
            ACCEPTANCE_LOAD_WRITES = Get-Required $config 'ACCEPTANCE_LOAD_WRITES'
            ACTIVE_USERS = '100'
            UPLOAD_USERS = '20'
            INTERACTIVE_DURATION = Get-Required $config 'ACCEPTANCE_INTERACTIVE_DURATION'
            SOAK_DURATION = '4h'
            LOAD_USERS_FILE = "/secrets/$([System.IO.Path]::GetFileName($usersPath))"
            UPLOAD_FIXTURE = '/fixtures/near-limit.pdf'
        }
        $arguments = @(
            'run', '--rm',
            '--mount', "type=bind,src=$loadDirectory,dst=/scripts,readonly",
            '--mount', "type=bind,src=$(Split-Path -Parent $usersPath),dst=/secrets,readonly",
            '--mount', "type=bind,src=$fixtureRoot,dst=/fixtures,readonly",
            '--mount', "type=bind,src=$evidencePath,dst=/evidence"
        )
        foreach ($entry in $environment.GetEnumerator()) { $arguments += @('-e', "$($entry.Key)=$($entry.Value)") }
        $arguments += @($k6Image, 'run', "--summary-export=/evidence/$summaryName", "/scripts/$scriptName")
        & docker @arguments
        $exitCode = $LASTEXITCODE
        $results.Add([ordered]@{ profile = $selectedProfile; exitCode = $exitCode; summary = $summaryName })
        if ($exitCode -ne 0) { throw "k6 profile '$selectedProfile' failed its thresholds." }
    }
}
finally {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$loadFinishedAt = (Get-Date).ToUniversalTime()
$metricsDeadline = $loadFinishedAt.AddSeconds($MetricsWaitSeconds)
while (-not (Test-Path -LiteralPath $RuntimeMetricsFile -PathType Leaf) -and (Get-Date).ToUniversalTime() -lt $metricsDeadline) {
    Start-Sleep -Seconds 5
}
if (-not (Test-Path -LiteralPath $RuntimeMetricsFile -PathType Leaf)) {
    throw 'The monitoring collector did not publish RuntimeMetricsFile after the load window.'
}
$metricsPath = (Resolve-Path -LiteralPath $RuntimeMetricsFile).Path
$metrics = Get-Content -LiteralPath $metricsPath -Raw | ConvertFrom-Json
if ($metrics.schemaVersion -ne 1 -or -not $metrics.windowStart -or -not $metrics.windowEnd -or
    -not $metrics.sourceDashboard -or -not $metrics.collector) {
    throw 'Runtime metrics provenance is incomplete.'
}
$metricsWindowStart = [datetime]::Parse("$($metrics.windowStart)").ToUniversalTime()
$metricsWindowEnd = [datetime]::Parse("$($metrics.windowEnd)").ToUniversalTime()
if ($metricsWindowStart -gt $loadStartedAt -or $metricsWindowEnd -lt $loadFinishedAt) {
    throw 'Runtime metrics do not cover the complete k6 load window.'
}
$metricContracts = @(
    @{ Name = 'hikariPendingMax'; Threshold = 'ACCEPTANCE_MAX_HIKARI_PENDING'; Direction = 'max' },
    @{ Name = 'heapPercentMax'; Threshold = 'ACCEPTANCE_MAX_HEAP_PERCENT'; Direction = 'max' },
    @{ Name = 'freeDiskGiBMin'; Threshold = 'ACCEPTANCE_MIN_FREE_DISK_GIB'; Direction = 'min' },
    @{ Name = 'tempGrowthMiB'; Threshold = 'ACCEPTANCE_MAX_TEMP_GROWTH_MIB'; Direction = 'max' },
    @{ Name = 'scannerP95Ms'; Threshold = 'ACCEPTANCE_MAX_SCANNER_P95_MS'; Direction = 'max' },
    @{ Name = 'r2P95Ms'; Threshold = 'ACCEPTANCE_MAX_R2_P95_MS'; Direction = 'max' },
    @{ Name = 'orphanObjects'; Threshold = 'ACCEPTANCE_MAX_ORPHAN_OBJECTS'; Direction = 'max' }
)
foreach ($contract in $metricContracts) {
    $property = $metrics.PSObject.Properties[$contract.Name]
    if ($null -eq $property -or $null -eq $property.Value) { throw "Runtime metrics are missing '$($contract.Name)'." }
    $actual = [double]$property.Value
    $limit = [double]$thresholds[$contract.Threshold]
    if (($contract.Direction -eq 'max' -and $actual -gt $limit) -or
        ($contract.Direction -eq 'min' -and $actual -lt $limit)) {
        throw "Runtime metric '$($contract.Name)' violates its approved threshold."
    }
}

$manifest = [ordered]@{
    schemaVersion = 1
    status = 'PASS'
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    gitSha = (& git -C $repoRoot rev-parse HEAD).Trim()
    k6Image = $k6Image
    activeUsers = 100
    concurrentNearLimitUploads = 20
    soakDuration = '4h'
    loadStartedAt = $loadStartedAt.ToString('o')
    loadFinishedAt = $loadFinishedAt.ToString('o')
    thresholds = $thresholds
    runtimeMetrics = $metrics
    profiles = $results
}
$manifestFile = Join-Path $evidencePath ("capacity-{0}.json" -f (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestFile -Encoding utf8NoBOM
Write-Host "Capacity acceptance passed. Evidence: $manifestFile" -ForegroundColor Green
