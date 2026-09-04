param(
    [Parameter(Mandatory = $true)][string]$ReleaseDirectory,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository,
    [Parameter(Mandatory = $true)][ValidatePattern('^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$')][string]$Version,
    [string]$EvidenceDirectory = 'output/managed-acceptance'
)

$ErrorActionPreference = 'Stop'
$releaseRoot = (Resolve-Path -LiteralPath $ReleaseDirectory).Path
$checksumsPath = Join-Path $releaseRoot 'SHA256SUMS'
if (-not (Test-Path -LiteralPath $checksumsPath -PathType Leaf)) { throw 'SHA256SUMS is missing.' }

foreach ($line in Get-Content -LiteralPath $checksumsPath) {
    if ($line -notmatch '^([a-f0-9]{64})\s+\*?(.+)$') { throw 'SHA256SUMS contains an invalid line.' }
    $expected = $Matches[1]
    $relative = $Matches[2].TrimStart('.', '/', '\')
    if (-not $relative -or $relative.Split('/', '\') -contains '..') { throw 'SHA256SUMS contains an unsafe path.' }
    $artifactPath = [System.IO.Path]::GetFullPath((Join-Path $releaseRoot $relative))
    if (-not $artifactPath.StartsWith($releaseRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'SHA256SUMS path escapes the release directory.'
    }
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) { throw "Checksummed artifact is missing: $relative" }
    if ((Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        throw "Checksum mismatch: $relative"
    }
}

$cosign = (Get-Command cosign -ErrorAction Stop).Source
$gh = (Get-Command gh -ErrorAction Stop).Source
$images = [System.Collections.Generic.List[string]]::new()
$owner = $Repository.Split('/')[0].ToLowerInvariant()
foreach ($component in @('server', 'web', 'backup', 'postgres', 'typesense')) {
    $imageFile = Join-Path $releaseRoot "smartupcms-$component-$Version.image.txt"
    $spdxFile = Join-Path $releaseRoot "smartupcms-$component-$Version.spdx.json"
    $cyclonedxFile = Join-Path $releaseRoot "smartupcms-$component-$Version.cdx.json"
    $provenanceFile = Join-Path $releaseRoot "smartupcms-$component-$Version.provenance.json"
    foreach ($required in @($imageFile, $spdxFile, $cyclonedxFile, $provenanceFile)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Release metadata is missing: $([System.IO.Path]::GetFileName($required))" }
    }
    $image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
    if ($image -notmatch "^ghcr\.io/$([regex]::Escape($owner))/smartupcms/$component@sha256:[a-f0-9]{64}$") {
        throw "Unexpected immutable image reference for $component."
    }
    $spdx = Get-Content -LiteralPath $spdxFile -Raw | ConvertFrom-Json
    if (-not "$($spdx.spdxVersion)".StartsWith('SPDX-')) { throw "Invalid SPDX SBOM for $component." }
    $cyclonedx = Get-Content -LiteralPath $cyclonedxFile -Raw | ConvertFrom-Json
    if ($cyclonedx.bomFormat -ne 'CycloneDX') { throw "Invalid CycloneDX SBOM for $component." }

    # Independent target-side commands: cosign verify and gh attestation verify.
    & $cosign verify --certificate-identity-regexp "^https://github.com/$Repository/.github/workflows/release.yml@refs/tags/$Version$" `
        --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' $image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "cosign verify failed for $component." }
    & $gh attestation verify "oci://$image" --repo $Repository | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "gh attestation verify failed for $component." }
    $images.Add($image)
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) {
    [System.IO.Path]::GetFullPath($EvidenceDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDirectory))
}
New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
$manifest = [ordered]@{
    schemaVersion = 1
    status = 'PASS'
    verifiedAt = (Get-Date).ToUniversalTime().ToString('o')
    repository = $Repository
    version = $Version
    sha256Sums = (Get-FileHash -LiteralPath $checksumsPath -Algorithm SHA256).Hash.ToLowerInvariant()
    images = $images
}
$manifestPath = Join-Path $evidenceRoot "release-integrity-$Version.json"
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
Write-Host "Published release integrity passed. Evidence: $manifestPath" -ForegroundColor Green
