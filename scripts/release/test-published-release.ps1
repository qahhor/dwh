param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$testRoot = Join-Path $repoRoot ('.local/published-release-test-' + [guid]::NewGuid().ToString('N'))
$release = Join-Path $testRoot 'release'
$evidence = Join-Path $testRoot 'evidence'
$verifier = Join-Path $PSScriptRoot 'verify-published-release.ps1'
$failures = [System.Collections.Generic.List[string]]::new()
$cases = 0
$sourceSha = '1234567890abcdef1234567890abcdef12345678'
New-Item -ItemType Directory -Path $release, $evidence -Force | Out-Null
$global:releaseVerifierTest = @{ Root = $testRoot }

# ADR-0040: replace external cryptographic services, not the verifier or its file checks.
function Get-Command([string]$Name) {
    if ($Name -in @('cosign', 'gh')) {
        return [pscustomobject]@{ Source = (Join-Path $global:releaseVerifierTest.Root "$Name.ps1") }
    }
    Microsoft.PowerShell.Core\Get-Command $Name
}

function Write-Checksums([switch]$Trusted) {
    Get-ChildItem -LiteralPath $release -File |
        Where-Object { $_.Name -notin @('SHA256SUMS', 'SHA256SUMS.sigstore.json', 'SHA256SUMS.provenance.json') } |
        Sort-Object Name | ForEach-Object {
            (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name
        } | Set-Content -LiteralPath (Join-Path $release 'SHA256SUMS') -Encoding utf8NoBOM
    if ($Trusted) { $global:releaseVerifierTest.ManifestHash = (Get-FileHash (Join-Path $release 'SHA256SUMS')).Hash }
}

function Invoke-Case([string]$Name, [scriptblock]$Arrange, [string]$ExpectedError = '',
    [string]$Mode = 'stable', [hashtable]$Overrides = @{}) {
    $script:cases++
    Get-ChildItem -LiteralPath $release -File | Remove-Item -Force
    Get-ChildItem -LiteralPath $evidence -File | Remove-Item -Force
    $version = if ($Mode -eq 'staging') { 'v1.2.3-rc.1' } else { 'v1.2.3' }
    $workflow = if ($Mode -eq 'staging') { 'prerelease.yml' } else { 'release.yml' }
    $global:releaseVerifierTest = @{
        Root = $testRoot; Version = $version; Workflow = $workflow; SourceSha = $sourceSha
        SignatureExit = 0; ProvenanceExit = 0; ImageSignatureExit = 0; ImageProvenanceExit = 0; Calls = [System.Collections.Generic.List[object]]::new()
    }
    $images = @()
    foreach ($component in @('server', 'web', 'backup', 'postgres', 'typesense', 'clamav')) {
        $prefix = Join-Path $release "smartupcms-$component-$version"
        $image = "ghcr.io/example/smartupcms/$component@sha256:$('a' * 64)"
        $images += $image
        $image | Set-Content "$prefix.image.txt"
        '{"spdxVersion":"SPDX-2.3"}' | Set-Content "$prefix.spdx.json"
        '{"bomFormat":"CycloneDX"}' | Set-Content "$prefix.cdx.json"
        '{"mediaType":"application/vnd.dev.sigstore.bundle.v0.3+json"}' | Set-Content "$prefix.provenance.json"
    }
    'synthetic archive bytes' | Set-Content (Join-Path $release "smartupcms-$version.tar.gz")
    'synthetic archive bytes' | Set-Content (Join-Path $release "smartupcms-$version.zip")
    foreach ($file in @('SHA256SUMS.sigstore.json', 'SHA256SUMS.provenance.json')) {
        '{"mediaType":"application/vnd.dev.sigstore.bundle.v0.3+json"}' | Set-Content (Join-Path $release $file)
    }
    $metadata = @{ schemaVersion = 1; channel = $Mode; version = $version; sourceSha = $sourceSha; images = $images }
    $metadata | ConvertTo-Json | Set-Content (Join-Path $release 'release.json')
    Write-Checksums -Trusted
    & $Arrange
    $parameters = @{ ReleaseDirectory = $release; Repository = 'example/cms'; Version = $version
        EvidenceDirectory = $evidence; ExpectedSourceSha = $sourceSha }
    if ($Mode -eq 'staging') { $parameters.Channel = 'staging' }
    foreach ($key in $Overrides.Keys) { $parameters[$key] = $Overrides[$key] }
    $message = ''
    try { & $verifier @parameters *> (Join-Path $testRoot "$Name.log") }
    catch { $message = $_.Exception.Message }
    $manifests = @(Get-ChildItem -LiteralPath $evidence -Filter '*.json')
    if ($ExpectedError) {
        if ($message -notmatch $ExpectedError -or $manifests.Count -ne 0) {
            $failures.Add("$Name expected '$ExpectedError' without PASS evidence; got '$message', manifests=$($manifests.Count).")
        }
    }
    elseif ($message -or $manifests.Count -ne 1) {
        $failures.Add("$Name expected one PASS manifest; got '$message', manifests=$($manifests.Count).")
    }
    else {
        $result = Get-Content $manifests[0].FullName -Raw | ConvertFrom-Json
        if ($result.schemaVersion -ne 2 -or $result.channel -cne $Mode -or $result.sourceSha -cne $sourceSha -or
            $result.sourceRef -cne "refs/tags/$version" -or $result.workflow -cne $workflow -or
            $result.status -cne 'PASS' -or @($result.images).Count -ne 6) {
            $failures.Add("$Name emitted incomplete or incorrect trusted release identity.")
        }
        $calls = $global:releaseVerifierTest.Calls
        if (@($calls | Where-Object Tool -eq 'cosign').Count -ne 7 -or @($calls | Where-Object Tool -eq 'gh').Count -ne 7) {
            $failures.Add("$Name must verify the manifest and every image with both independent boundaries.")
        }
    }
}

try {
    @'
$state = $global:releaseVerifierTest
$state.Calls.Add(@{ Tool = 'cosign'; Arguments = @($args) })
function Value([string]$Name) {
    $at = [array]::IndexOf($script:args, $Name)
    if ($at -lt 0 -or $at + 1 -ge $script:args.Count) { return '' }
    return $script:args[$at + 1]
}
$identity = "https://github.com/example/cms/.github/workflows/$($state.Workflow)@refs/tags/$($state.Version)"
if ((Value '--certificate-identity') -cne $identity -or
    (Value '--certificate-oidc-issuer') -cne 'https://token.actions.githubusercontent.com') { exit 1 }
if ($args[0] -eq 'verify-blob') {
    if (-not (Test-Path -LiteralPath (Value '--bundle')) -or
        (Split-Path (Value '--bundle') -Leaf) -cne 'SHA256SUMS.sigstore.json' -or
        (Get-FileHash -LiteralPath $args[-1]).Hash -cne $state.ManifestHash) { exit 1 }
} elseif ($args[0] -ne 'verify' -or $args[-1] -notmatch '^ghcr\.io/example/smartupcms/[a-z]+@sha256:[a-f0-9]{64}$') { exit 1 }
if ($args[0] -eq 'verify' -and $state.ImageSignatureExit) { exit $state.ImageSignatureExit }
exit $state.SignatureExit
'@ | Set-Content (Join-Path $testRoot 'cosign.ps1')
    @'
$state = $global:releaseVerifierTest
$state.Calls.Add(@{ Tool = 'gh'; Arguments = @($args) })
function Value([string]$Name) {
    $at = [array]::IndexOf($script:args, $Name)
    if ($at -lt 0 -or $at + 1 -ge $script:args.Count) { return '' }
    return $script:args[$at + 1]
}
$identity = "https://github.com/example/cms/.github/workflows/$($state.Workflow)@refs/tags/$($state.Version)"
if ($args[0] -ne 'attestation' -or $args[1] -ne 'verify' -or
    (Value '--repo') -cne 'example/cms' -or (Value '--cert-identity') -cne $identity -or
    (Value '--source-ref') -cne "refs/tags/$($state.Version)" -or
    (Value '--source-digest') -cne $state.SourceSha -or (Value '--signer-digest') -cne $state.SourceSha -or
    $args -notcontains '--deny-self-hosted-runners' -or -not (Test-Path -LiteralPath (Value '--bundle'))) { exit 1 }
if ($args[2] -like 'oci://*') {
    if ($args[2] -notmatch '/([a-z]+)@sha256:[a-f0-9]{64}$') { exit 1 }
    if ((Split-Path (Value '--bundle') -Leaf) -cne "smartupcms-$($Matches[1])-$($state.Version).provenance.json") { exit 1 }
    if ($state.ImageProvenanceExit) { exit $state.ImageProvenanceExit }
} elseif ((Split-Path (Value '--bundle') -Leaf) -cne 'SHA256SUMS.provenance.json') { exit 1 }
if ($args[2] -notlike 'oci://*' -and (Get-FileHash -LiteralPath $args[2]).Hash -cne $state.ManifestHash) { exit 1 }
exit $state.ProvenanceExit
'@ | Set-Content (Join-Path $testRoot 'gh.ps1')
    Invoke-Case 'complete-stable-default' {}
    Invoke-Case 'complete-staging-explicit' {} '' 'staging'
    Invoke-Case 'staging-rejected-by-stable' {} 'channel|stable' 'staging' @{ Channel = 'stable' }
    Invoke-Case 'stable-rejected-by-staging' {} 'channel|staging' 'stable' @{ Channel = 'staging' }
    Invoke-Case 'missing-expected-sha' {} 'ExpectedSourceSha' 'stable' @{ ExpectedSourceSha = '' }
    Invoke-Case 'wrong-expected-sha' {} 'provenance|attestation|source' 'staging' @{ ExpectedSourceSha = ('b' * 40) }
    Invoke-Case 'zero-rc-number' {} 'version|tag' 'staging' @{ Version = 'v1.2.3-rc.0' }
    Invoke-Case 'leading-zero-rc-number' {} 'version|tag' 'staging' @{ Version = 'v1.2.3-rc.01' }
    Invoke-Case 'other-prerelease-kind' {} 'version|tag' 'staging' @{ Version = 'v1.2.3-beta.1' }
    Invoke-Case 'missing-checksums' { Remove-Item (Join-Path $release 'SHA256SUMS') } 'SHA256SUMS is missing'
    Invoke-Case 'empty-checksums' {
        '' | Set-Content (Join-Path $release 'SHA256SUMS') -NoNewline
        $global:releaseVerifierTest.ManifestHash = (Get-FileHash (Join-Path $release 'SHA256SUMS')).Hash
    } 'empty|missing from SHA256SUMS'
    Invoke-Case 'unsigned-manifest' { Remove-Item (Join-Path $release 'SHA256SUMS.sigstore.json') } 'manifest|SHA256SUMS.sigstore'
    Invoke-Case 'missing-manifest-provenance' { Remove-Item (Join-Path $release 'SHA256SUMS.provenance.json') } 'manifest|SHA256SUMS.provenance'
    Invoke-Case 'archive-replaced-and-rechecksummed' {
        'tampered Compose bundle' | Set-Content (Join-Path $release 'smartupcms-v1.2.3.tar.gz')
        Write-Checksums
    } 'cosign|signature'
    Invoke-Case 'omitted-bundle' {
        Remove-Item (Join-Path $release 'smartupcms-v1.2.3.zip')
        Write-Checksums -Trusted
    } 'missing from SHA256SUMS|artifact is missing'
    Invoke-Case 'duplicate-checksum' {
        $path = Join-Path $release 'SHA256SUMS'
        Get-Content $path -First 1 | Add-Content $path
        $global:releaseVerifierTest.ManifestHash = (Get-FileHash $path).Hash
    } 'duplicate'
    Invoke-Case 'tampered-metadata' { 'changed' | Set-Content (Join-Path $release 'smartupcms-server-v1.2.3.spdx.json') } 'Checksum mismatch'
    Invoke-Case 'missing-file' { Remove-Item (Join-Path $release 'smartupcms-server-v1.2.3.spdx.json') } 'artifact is missing'
    Invoke-Case 'unsafe-path' {
        "$('a' * 64)  ../outside" | Set-Content (Join-Path $release 'SHA256SUMS')
        $global:releaseVerifierTest.ManifestHash = (Get-FileHash (Join-Path $release 'SHA256SUMS')).Hash
    } 'unsafe path'
    Invoke-Case 'wrong-channel-metadata' {
        $path = Join-Path $release 'release.json'
        $value = Get-Content $path -Raw | ConvertFrom-Json
        $value.channel = 'staging'; $value | ConvertTo-Json | Set-Content $path
        Write-Checksums -Trusted
    } 'identity|channel'
    Invoke-Case 'wrong-source-metadata' {
        $path = Join-Path $release 'release.json'
        $value = Get-Content $path -Raw | ConvertFrom-Json
        $value.sourceSha = ('b' * 40); $value | ConvertTo-Json | Set-Content $path
        Write-Checksums -Trusted
    } 'identity|source'
    Invoke-Case 'wrong-image-metadata' {
        $path = Join-Path $release 'release.json'
        $value = Get-Content $path -Raw | ConvertFrom-Json
        $value.images[0] = $value.images[1]; $value | ConvertTo-Json | Set-Content $path
        Write-Checksums -Trusted
    } 'image|identity'
    Invoke-Case 'omitted-metadata' {
        $path = Join-Path $release 'SHA256SUMS'
        @(Get-Content $path | Where-Object { $_ -notmatch 'server.*\.spdx\.json' }) | Set-Content $path
        $global:releaseVerifierTest.ManifestHash = (Get-FileHash $path).Hash
    } 'missing from SHA256SUMS'
    Invoke-Case 'image-signature-failure' { $global:releaseVerifierTest.ImageSignatureExit = 1 } 'cosign verify failed'
    Invoke-Case 'image-provenance-failure' { $global:releaseVerifierTest.ImageProvenanceExit = 1 } 'gh attestation'
    Invoke-Case 'wrong-attested-source' { $global:releaseVerifierTest.SourceSha = ('f' * 40) } 'gh attestation' 'staging'
    Invoke-Case 'wrong-attested-tag' { $global:releaseVerifierTest.Version = 'v1.2.3-rc.2' } 'cosign|signature' 'staging'
    Invoke-Case 'signature-failure' { $global:releaseVerifierTest.SignatureExit = 1 } 'cosign|signature'
    Invoke-Case 'provenance-failure' { $global:releaseVerifierTest.ProvenanceExit = 1 } 'gh attestation|provenance'
    Invoke-Case 'staging-wrong-signer' { $global:releaseVerifierTest.Workflow = 'release.yml' } 'cosign|signature' 'staging'
    Invoke-Case 'stale-evidence' {
        '{"status":"PASS"}' | Set-Content (Join-Path $evidence 'release-integrity-v1.2.3.json')
        $global:releaseVerifierTest.SignatureExit = 1
    } 'cosign|signature'
    Invoke-Case 'stale-evidence-channel-mismatch' {
        '{"status":"PASS"}' | Set-Content (Join-Path $evidence 'release-integrity-v1.2.3-rc.1.json')
    } 'channel|stable' 'staging' @{ Channel = 'stable' }
    if ($failures.Count) { throw ($failures -join "`n") }
    Write-Host "Published release verifier: $cases regression cases passed."
}
finally {
    Remove-Variable releaseVerifierTest -Scope Global -ErrorAction SilentlyContinue
    if ((Resolve-Path $testRoot).Path.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar)) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
exit 0
