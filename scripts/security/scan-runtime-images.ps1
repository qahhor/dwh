param(
    [string]$ImageRegistry = "smartupcms",
    [string]$AppVersion = "dev",
    [string]$ClamavImage = "smartupcms/clamav:1.5.4-hardened",
    [string]$TrivyImage = "aquasec/trivy:0.74.0",
    [string]$TrivyCacheVolume = "smc-trivy-cache"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$clamavContext = Join-Path $repoRoot "deploy/images/clamav"

$images = @(
    "${ImageRegistry}/server:${AppVersion}",
    "${ImageRegistry}/web:${AppVersion}",
    "${ImageRegistry}/backup:${AppVersion}",
    "smartupcms/postgres:18-alpine-hardened",
    "smartupcms/typesense:27.1-hardened",
    $ClamavImage
)

# The development Compose file has no ClamAV service: the hardened image is built here from its Dockerfile, so the
# scan never reads a stale local tag.
Write-Host "Building runtime image: $ClamavImage" -ForegroundColor Yellow
& docker build --pull --tag $ClamavImage $clamavContext
if ($LASTEXITCODE -ne 0) {
    throw "Required runtime image could not be built: $ClamavImage"
}

foreach ($image in $images) {
    & docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Required runtime image was not built: $image"
    }

    Write-Host "Scanning runtime image: $image" -ForegroundColor Yellow
    $json = & docker run --rm `
        -v /var/run/docker.sock:/var/run/docker.sock `
        -v "${TrivyCacheVolume}:/root/.cache/" `
        $TrivyImage image `
        --scanners vuln `
        --severity HIGH,CRITICAL `
        --ignore-unfixed `
        --timeout 10m `
        --skip-version-check `
        --no-progress `
        --format json `
        $image

    if ($LASTEXITCODE -ne 0) {
        throw "Trivy could not scan runtime image: $image"
    }

    $report = $json | ConvertFrom-Json
    $vulnerabilities = @(
        $report.Results |
            ForEach-Object { $_.Vulnerabilities } |
            Where-Object { $null -ne $_ }
    )
    Write-Host "HIGH/CRITICAL findings: $($vulnerabilities.Count)"

    if ($vulnerabilities.Count -gt 0) {
        $vulnerabilities |
            Select-Object Severity, VulnerabilityID, PkgName, InstalledVersion, FixedVersion |
            Format-Table -AutoSize
        throw "Runtime image security gate failed: $image"
    }
}

Write-Host "Runtime image security checks passed." -ForegroundColor Green
