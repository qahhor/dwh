param(
    [Parameter(Mandatory = $true)][string]$ConfigFile,
    [Parameter(Mandatory = $true)][ValidateSet('scanner', 'database', 'backup')][string]$Scenario,
    [string]$ComposeFile = 'deploy/compose/docker-compose.prod.yml',
    [string]$EnvFile = '.env.production',
    [string]$EvidenceDirectory = 'output/managed-acceptance',
    [switch]$AcknowledgeServiceDisruption
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
        $values[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
    }
    return $values
}

function Get-Required([hashtable]$Values, [string]$Name) {
    $value = "$($Values[$Name])".Trim()
    if (-not $value -or $value.Contains('CHANGE_ME')) { throw "Required value '$Name' is not configured." }
    return $value
}

function Invoke-Compose([string[]]$Arguments, [switch]$AllowFailure) {
    & docker compose -f $ComposeFile --env-file $EnvFile @Arguments
    $code = $LASTEXITCODE
    if (-not $AllowFailure -and $code -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
    return $code
}

function Invoke-Api([string]$Path, [string]$Method = 'Get', [hashtable]$Form) {
    $parameters = @{
        Uri = [uri]::new($script:origin, $Path)
        Method = $Method
        Headers = @{ Authorization = "Bearer $script:apiToken"; Accept = 'application/json' }
        TimeoutSec = 30
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Form) { $parameters.Form = $Form }
    return Invoke-WebRequest @parameters
}

if (-not $AcknowledgeServiceDisruption) {
    throw 'Failure drills interrupt staging services. Re-run with -AcknowledgeServiceDisruption after confirming the target.'
}
$config = Read-DotEnv $ConfigFile
if ((Get-Required $config 'ACCEPTANCE_TARGET_ENVIRONMENT') -ne 'staging') {
    throw 'Failure drills are restricted to an explicitly labelled staging environment.'
}
$script:origin = [uri](Get-Required $config 'ACCEPTANCE_PUBLIC_ORIGIN')
if ($script:origin.Scheme -ne 'https') { throw 'Failure drills require the external HTTPS origin.' }
$script:apiToken = [Environment]::GetEnvironmentVariable('ACCEPTANCE_API_TOKEN')
if ($Scenario -ne 'backup' -and [string]::IsNullOrWhiteSpace($script:apiToken)) {
    throw 'ACCEPTANCE_API_TOKEN is required for the scanner and database drills.'
}
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw "Environment file not found: $EnvFile" }

$startedAt = (Get-Date).ToUniversalTime()
$checks = [System.Collections.Generic.List[object]]::new()
$failure = $null
$serviceToRecover = $null
$fixtureRoot = $null
function Add-Result([string]$Id, [string]$Status, [string]$Detail) {
    $checks.Add([ordered]@{ id = $Id; status = $Status; detail = $Detail })
}

try {
    Invoke-Compose @('config', '--quiet') | Out-Null
    if ($Scenario -in @('scanner', 'database')) {
        $baseline = Invoke-Api '/api/v1/auth/me'
        if ([int]$baseline.StatusCode -ne 200) { throw 'Authenticated baseline request did not return HTTP 200.' }
        Add-Result 'baseline' 'PASS' 'Authenticated API returned HTTP 200.'
    }

    if ($Scenario -eq 'scanner') {
        $fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('smartupcms-scanner-drill-' + [guid]::NewGuid())
        New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
        $fixture = Join-Path $fixtureRoot 'scanner-drill.pdf'
        [System.IO.File]::WriteAllBytes($fixture, [System.Text.Encoding]::ASCII.GetBytes("%PDF-1.4`nscanner outage drill`n"))
        Invoke-Compose @('stop', 'clamav') | Out-Null
        $serviceToRecover = 'clamav'
        $response = Invoke-Api '/api/v1/files/upload' 'Post' @{ file = Get-Item -LiteralPath $fixture }
        if ([int]$response.StatusCode -ne 503 -or "$($response.Content)" -notmatch 'file_scan_failed') {
            throw "Scanner outage did not fail closed with 503 file_scan_failed; received HTTP $([int]$response.StatusCode)."
        }
        Add-Result 'scanner-outage' 'PASS' 'Upload failed closed with HTTP 503 file_scan_failed.'
        Invoke-Compose @('up', '-d', '--wait', 'clamav') | Out-Null
        $serviceToRecover = $null
        $recovered = Invoke-Api '/api/v1/files/upload' 'Post' @{ file = Get-Item -LiteralPath $fixture }
        if ([int]$recovered.StatusCode -ne 201) { throw 'Upload did not recover after ClamAV became healthy.' }
        Add-Result 'scanner-recovery' 'PASS' 'Upload returned HTTP 201 after scanner recovery.'
    }
    elseif ($Scenario -eq 'database') {
        Invoke-Compose @('stop', 'postgres') | Out-Null
        $serviceToRecover = 'postgres'
        $response = Invoke-Api '/api/v1/auth/me'
        if ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 400) {
            throw 'Authenticated API remained successful while PostgreSQL was stopped.'
        }
        Add-Result 'database-outage' 'PASS' "Authenticated API failed with HTTP $([int]$response.StatusCode)."
        Invoke-Compose @('up', '-d', '--wait', 'postgres', 'server') | Out-Null
        $serviceToRecover = $null
        $recovered = Invoke-Api '/api/v1/auth/me'
        if ([int]$recovered.StatusCode -ne 200) { throw 'Authenticated API did not recover after PostgreSQL became healthy.' }
        Add-Result 'database-recovery' 'PASS' 'Authenticated API returned HTTP 200 after database recovery.'
    }
    else {
        $exitCode = Invoke-Compose @('run', '--rm', '--no-deps', '-e', 'BACKUP_RUN_ONCE=true',
            '-e', 'PGPASSWORD_FILE=/run/secrets/deliberately-missing', 'backup') -AllowFailure
        if ($exitCode -eq 0) { throw 'Backup unexpectedly succeeded with a deliberately missing credential.' }
        Add-Result 'backup-fail-closed' 'PASS' 'One-shot backup returned non-zero with a missing credential.'
        & (Join-Path $PSScriptRoot '../prod/backup.ps1') -ComposeFile $ComposeFile -EnvFile $EnvFile
        if ($LASTEXITCODE -ne 0) { throw 'Normal encrypted backup did not recover after the negative drill.' }
        Add-Result 'backup-recovery' 'PASS' 'Normal encrypted backup succeeded after the negative drill.'
    }

    $alertUri = [uri](Get-Required $config 'ACCEPTANCE_ALERT_TEST_URL')
    $alert = Invoke-WebRequest -Uri $alertUri -Method Post -TimeoutSec 20 -SkipHttpErrorCheck
    if ([int]$alert.StatusCode -lt 200 -or [int]$alert.StatusCode -ge 300) { throw 'Alert drill endpoint did not accept the event.' }
    Add-Result 'alert-delivery' 'PASS' 'Alert drill endpoint accepted the scenario event.'
}
catch {
    $failure = $_.Exception.Message
}
finally {
    if ($serviceToRecover) {
        try { Invoke-Compose @('up', '-d', '--wait', $serviceToRecover, 'server') | Out-Null }
        catch { Add-Result 'automatic-recovery' 'FAIL' $_.Exception.Message }
    }
    if ($fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue }
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
    $evidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) {
        [System.IO.Path]::GetFullPath($EvidenceDirectory)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDirectory))
    }
    New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
    $evidence = [ordered]@{
        schemaVersion = 1
        status = if ($failure -or @($checks | Where-Object status -eq 'FAIL').Count -gt 0) { 'UNVERIFIED' } else { 'PASS' }
        scenario = $Scenario
        startedAt = $startedAt.ToString('o')
        finishedAt = (Get-Date).ToUniversalTime().ToString('o')
        gitSha = (& git -C $repoRoot rev-parse HEAD).Trim()
        checks = $checks
        error = $failure
    }
    $evidenceFile = Join-Path $evidenceRoot ("failure-$Scenario-{0}.json" -f $startedAt.ToString('yyyyMMddTHHmmssZ'))
    $evidence | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $evidenceFile -Encoding utf8NoBOM
    Write-Host "Failure-drill evidence: $evidenceFile"
}

if ($failure) { throw $failure }
if (@($checks | Where-Object status -eq 'FAIL').Count -gt 0) { throw 'Automatic service recovery failed.' }
Write-Host "Failure scenario '$Scenario' passed." -ForegroundColor Green
