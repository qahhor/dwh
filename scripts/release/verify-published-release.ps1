param(
    [Parameter(Mandatory = $true)][string]$ReleaseDirectory,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository,
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Channel = 'stable',
    [string]$ExpectedSourceSha = '',
    [string]$EvidenceDirectory = 'output/managed-acceptance'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$evidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) {
    [System.IO.Path]::GetFullPath($EvidenceDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDirectory))
}
if ($Version -cnotmatch '^v[A-Za-z0-9][A-Za-z0-9.-]{0,126}$') { throw 'Invalid release version.' }
$manifestPath = Join-Path $evidenceRoot "release-integrity-$Version.json"
# ADR-0040: invalidate prior evidence before checking channel, source or release assets.
if (Test-Path -LiteralPath $manifestPath -PathType Leaf) { Remove-Item -LiteralPath $manifestPath -Force }
$semVer = 'v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'
if ($Channel -cnotin @('stable', 'staging')) { throw 'Release channel must be stable or staging.' }
$versionPattern = if ($Channel -eq 'staging') { "^$semVer-rc\.[1-9][0-9]*$" } else { "^$semVer$" }
if ($Version -cnotmatch $versionPattern) { throw "Release version does not match the $Channel channel tag policy." }
if ($ExpectedSourceSha -cnotmatch '^[a-f0-9]{40}$') { throw 'ExpectedSourceSha must be an independently approved full lowercase commit SHA.' }
$workflow = if ($Channel -eq 'staging') { 'prerelease.yml' } else { 'release.yml' }
$sourceRef = "refs/tags/$Version"
$identity = "https://github.com/$Repository/.github/workflows/$workflow@$sourceRef"
$releaseRoot = (Resolve-Path -LiteralPath $ReleaseDirectory).Path
$checksumsPath = Join-Path $releaseRoot 'SHA256SUMS'
if (-not (Test-Path -LiteralPath $checksumsPath -PathType Leaf)) { throw 'SHA256SUMS is missing.' }
$signaturePath = Join-Path $releaseRoot 'SHA256SUMS.sigstore.json'
$provenancePath = Join-Path $releaseRoot 'SHA256SUMS.provenance.json'
foreach ($path in @($signaturePath, $provenancePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Signed manifest metadata is missing: $([IO.Path]::GetFileName($path))" }
}
$cosign = (Get-Command cosign -ErrorAction Stop).Source
$gh = (Get-Command gh -ErrorAction Stop).Source

function Assert-Provenance([string]$Subject, [string]$Bundle) {
    # ADR-0040: gh attestation verify checks certificate claims, not self-declared archive labels.
    & $gh attestation verify $Subject --repo $Repository --bundle $Bundle `
        --cert-identity $identity --cert-oidc-issuer 'https://token.actions.githubusercontent.com' `
        --source-ref $sourceRef --source-digest $ExpectedSourceSha --signer-digest $ExpectedSourceSha `
        --deny-self-hosted-runners | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'gh attestation verify failed for release provenance.' }
}

# ADR-0040: authenticate checksums before trusting any archive, script, SBOM or metadata bytes.
& $cosign verify-blob --bundle $signaturePath --certificate-identity $identity `
    --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' $checksumsPath | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'cosign manifest signature verification failed.' }
Assert-Provenance $checksumsPath $provenancePath

$checksummedFiles = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($line in Get-Content -LiteralPath $checksumsPath) {
    if ($line -notmatch '^([a-f0-9]{64})\s+\*?(.+)$') { throw 'SHA256SUMS contains an invalid line.' }
    $expected = $Matches[1]
    $relative = $Matches[2]
    if ($relative -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'SHA256SUMS contains an unsafe path.' }
    if (-not $checksummedFiles.Add($relative)) { throw 'SHA256SUMS contains a duplicate path.' }
    $artifactPath = Join-Path $releaseRoot $relative
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) { throw "Checksummed artifact is missing: $relative" }
    if ((Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        throw "Checksum mismatch: $relative"
    }
}

$components = @('server', 'web', 'backup', 'postgres', 'typesense', 'clamav')
$requiredArtifacts = @("smartupcms-$Version.tar.gz", "smartupcms-$Version.zip", 'release.json')
foreach ($component in $components) {
    foreach ($suffix in @('image.txt', 'spdx.json', 'cdx.json', 'provenance.json')) {
        $requiredArtifacts += "smartupcms-$component-$Version.$suffix"
    }
}
foreach ($artifact in $requiredArtifacts) {
    if (-not $checksummedFiles.Contains($artifact)) { throw "Required release artifact is missing from SHA256SUMS: $artifact" }
}
$release = Get-Content -LiteralPath (Join-Path $releaseRoot 'release.json') -Raw | ConvertFrom-Json
if ($release.schemaVersion -ne 1 -or $release.channel -cne $Channel -or $release.version -cne $Version -or
    $release.sourceSha -cne $ExpectedSourceSha) { throw 'Release identity does not match the approved channel, version and source SHA.' }

$images = [System.Collections.Generic.List[string]]::new()
$owner = $Repository.Split('/')[0].ToLowerInvariant()
foreach ($component in $components) {
    $imageFile = Join-Path $releaseRoot "smartupcms-$component-$Version.image.txt"
    $spdxFile = Join-Path $releaseRoot "smartupcms-$component-$Version.spdx.json"
    $cyclonedxFile = Join-Path $releaseRoot "smartupcms-$component-$Version.cdx.json"
    $provenanceFile = Join-Path $releaseRoot "smartupcms-$component-$Version.provenance.json"
    $image = (Get-Content -LiteralPath $imageFile -Raw).Trim()
    if ($image -cnotmatch "^ghcr\.io/$([regex]::Escape($owner))/smartupcms/$component@sha256:[a-f0-9]{64}$") {
        throw "Unexpected immutable image reference for $component."
    }
    $spdx = Get-Content -LiteralPath $spdxFile -Raw | ConvertFrom-Json
    if (-not "$($spdx.spdxVersion)".StartsWith('SPDX-')) { throw "Invalid SPDX SBOM for $component." }
    $cyclonedx = Get-Content -LiteralPath $cyclonedxFile -Raw | ConvertFrom-Json
    if ($cyclonedx.bomFormat -ne 'CycloneDX') { throw "Invalid CycloneDX SBOM for $component." }
    # Independent target-side cosign verify and gh attestation verify cover all six image digests.
    & $cosign verify --certificate-identity $identity `
        --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' $image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "cosign verify failed for $component." }
    Assert-Provenance "oci://$image" $provenanceFile
    $images.Add($image)
}
if (@($release.images).Count -ne 6 -or ($release.images -join "`n") -cne ($images -join "`n")) {
    throw 'Release image identity does not match the six verified component references.'
}

New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
$manifest = [ordered]@{
    schemaVersion = 2
    status = 'PASS'
    verifiedAt = (Get-Date).ToUniversalTime().ToString('o')
    repository = $Repository
    version = $Version
    channel = $Channel
    sourceSha = $ExpectedSourceSha
    sourceRef = $sourceRef
    workflow = $workflow
    sha256Sums = (Get-FileHash -LiteralPath $checksumsPath -Algorithm SHA256).Hash.ToLowerInvariant()
    images = $images
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
Write-Host "Published release integrity passed. Evidence: $manifestPath" -ForegroundColor Green
