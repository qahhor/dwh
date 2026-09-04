param(
    [Parameter(Mandatory = $true)][string]$ConfigFile,
    [string]$ComposeFile = 'deploy/compose/docker-compose.prod.yml',
    [string]$EnvFile = '.env.production',
    [string]$EvidenceDirectory = 'output/managed-acceptance'
)

$ErrorActionPreference = 'Stop'

function Read-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Configuration not found: $Path" }
    $values = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { throw 'Acceptance configuration contains an invalid line.' }
        $key = $line.Substring(0, $separator).Trim()
        if ($values.ContainsKey($key)) { throw "Duplicate configuration key '$key'." }
        $values[$key] = $line.Substring($separator + 1).Trim()
    }
    return $values
}

function Get-Required([hashtable]$Values, [string]$Name) {
    $value = "$($Values[$Name])".Trim()
    if (-not $value -or $value.Contains('CHANGE_ME')) { throw "Required value '$Name' is not configured." }
    return $value
}

function Get-RedactedIdentifier([string]$Value) {
    $hash = [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($Value))
    return 'sha256:' + ([Convert]::ToHexString($hash).ToLowerInvariant().Substring(0, 16))
}

function Invoke-Compose([string[]]$Arguments) {
    $output = & docker compose -f $ComposeFile --env-file $EnvFile @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
    return $output
}

function Assert-RestrictedFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'A required environment or secret file is missing.' }
    if ($IsLinux) {
        $mode = (& stat -c '%a' -- $Path).Trim()
        if ($LASTEXITCODE -ne 0 -or @('400', '600') -notcontains $mode) {
            throw 'Environment and secret files must use mode 0400 or 0600.'
        }
    }
}

$config = Read-DotEnv $ConfigFile
$profileId = Get-Required $config 'ACCEPTANCE_PROFILE_ID'
$hostProfile = Get-Required $config 'ACCEPTANCE_HOST_PROFILE'
$expectedImages = [ordered]@{
    server = Get-Required $config 'ACCEPTANCE_EXPECTED_SERVER_IMAGE'
    web = Get-Required $config 'ACCEPTANCE_EXPECTED_WEB_IMAGE'
    backup = Get-Required $config 'ACCEPTANCE_EXPECTED_BACKUP_IMAGE'
    postgres = Get-Required $config 'ACCEPTANCE_EXPECTED_POSTGRES_IMAGE'
    typesense = Get-Required $config 'ACCEPTANCE_EXPECTED_TYPESENSE_IMAGE'
}
foreach ($entry in $expectedImages.GetEnumerator()) {
    if ($entry.Value -notmatch '^ghcr\.io/[a-z0-9_.-]+/smartupcms/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$') {
        throw "Expected $($entry.Key) image is not a complete GHCR digest reference."
    }
}

$composePath = (Resolve-Path -LiteralPath $ComposeFile).Path
$environmentPath = (Resolve-Path -LiteralPath $EnvFile).Path
Assert-RestrictedFile $environmentPath
$renderedJson = (Invoke-Compose @('--profile', 'tools', 'config', '--format', 'json')) -join "`n"
$rendered = $renderedJson | ConvertFrom-Json

$publishedServices = @($rendered.services.PSObject.Properties | Where-Object {
    $null -ne $_.Value.ports -and @($_.Value.ports).Count -gt 0
})
if ($publishedServices.Count -ne 1 -or $publishedServices[0].Name -ne 'web') {
    throw 'Only the web service may publish a host port.'
}
$webPort = @($rendered.services.web.ports)
if ($webPort.Count -ne 1 -or "$($webPort[0].host_ip)" -notin @('127.0.0.1', '::1')) {
    throw 'The Compose web origin must bind only to loopback.'
}
if (-not $rendered.networks.backend.internal) { throw 'The backend network must remain internal.' }
if ("$($rendered.services.server.environment.DWH_PROVIDER_STORAGE)" -ne 's3') {
    throw 'Smartup-managed host must use the S3-compatible R2 provider.'
}
if ("$($rendered.services.server.environment.DWH_FILE_SCANNER_REQUIRED)" -ne 'true' -or
    "$($rendered.services.server.environment.DWH_FILE_SCANNER_CLAMAV_ENABLED)" -ne 'true') {
    throw 'Smartup-managed host must enforce the ClamAV scanner.'
}

foreach ($secret in $rendered.secrets.PSObject.Properties) {
    if ($secret.Value.file) { Assert-RestrictedFile "$($secret.Value.file)" }
}

$serviceStates = [System.Collections.Generic.List[object]]::new()
foreach ($service in @('postgres', 'server', 'web', 'typesense', 'clamav', 'backup')) {
    $containerId = ((Invoke-Compose @('ps', '-a', '-q', $service)) -join '').Trim()
    if (-not $containerId) { throw "Required service '$service' has no container." }
    $stateText = (& docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.Config.Image}}' $containerId).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect service '$service'." }
    $stateParts = $stateText.Split('|', 3)
    if ($stateParts[0] -ne 'running') { throw "Service '$service' is not running." }
    if ($service -in @('postgres', 'server', 'web', 'typesense', 'clamav') -and $stateParts[1] -ne 'healthy') {
        throw "Service '$service' is not healthy."
    }
    if ($expectedImages.Contains($service) -and $stateParts[2] -ne $expectedImages[$service]) {
        throw "Running '$service' container is not bound to the accepted digest reference."
    }
    $serviceStates.Add([ordered]@{
        service = $service
        status = $stateParts[0]
        health = $stateParts[1]
        image = if ($expectedImages.Contains($service)) { $expectedImages[$service] } else { $stateParts[2] }
    })
}

$readiness = (& docker compose -f $composePath --env-file $environmentPath exec -T server `
    curl -fsS http://127.0.0.1:9090/actuator/health/readiness) -join "`n"
if ($LASTEXITCODE -ne 0 -or $readiness -notmatch '"status"\s*:\s*"UP"') {
    throw 'Internal server readiness probe is not UP.'
}

$dockerInfo = (& docker info --format '{{json .}}') -join "`n" | ConvertFrom-Json
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$gitSha = (& git -C $repoRoot rev-parse HEAD).Trim()
$evidence = [ordered]@{
    schemaVersion = 1
    status = 'PASS'
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    gitSha = $gitSha
    profileId = $profileId
    hostProfile = Get-RedactedIdentifier $hostProfile
    composeFileSha256 = (Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant()
    expectedImages = $expectedImages
    services = $serviceStates
    hostCapacity = [ordered]@{
        architecture = $dockerInfo.Architecture
        operatingSystem = $dockerInfo.OperatingSystem
        dockerVersion = $dockerInfo.ServerVersion
        cpus = $dockerInfo.NCPU
        memoryBytes = $dockerInfo.MemTotal
    }
    controls = @(
        'only-web-loopback-published',
        'backend-network-internal',
        'r2-provider-enabled',
        'scanner-required',
        'restricted-config-files',
        'all-services-running',
        'healthchecks-green',
        'five-release-images-by-digest',
        'internal-readiness-up'
    )
}
$evidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) {
    [System.IO.Path]::GetFullPath($EvidenceDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDirectory))
}
New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
$evidencePath = Join-Path $evidenceRoot ("managed-host-{0}.json" -f (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding utf8NoBOM
Write-Host "Managed host check passed. Evidence: $evidencePath" -ForegroundColor Green
