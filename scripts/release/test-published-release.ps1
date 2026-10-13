param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$testRoot = Join-Path $repoRoot ('.local/published-release-test-' + [guid]::NewGuid().ToString('N'))
$release = Join-Path $testRoot 'release'
$evidence = Join-Path $testRoot 'evidence'
$verifier = Join-Path $PSScriptRoot 'verify-published-release.ps1'
$version = 'v1.2.3'
$failures = [System.Collections.Generic.List[string]]::new()
$cases = 0
New-Item -ItemType Directory -Path $release, $evidence -Force | Out-Null
$global:releaseVerifierTest = @{ Root = $testRoot; SignatureExit = 0; ProvenanceExit = 0 }

# NFR-SEC-07: replace only the external signature/attestation boundaries; run the actual verifier.
function Get-Command([string]$Name) {
    if ($Name -in @('cosign', 'gh')) {
        return [pscustomobject]@{ Source = (Join-Path $global:releaseVerifierTest.Root "$Name.ps1") }
    }
    Microsoft.PowerShell.Core\Get-Command $Name
}

function Write-Checksums {
    Get-ChildItem -LiteralPath $release -File | Where-Object Name -ne 'SHA256SUMS' | Sort-Object Name |
        ForEach-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name } |
        Set-Content -LiteralPath (Join-Path $release 'SHA256SUMS') -Encoding utf8NoBOM
}

function Invoke-Case([string]$Name, [scriptblock]$Arrange, [string]$ExpectedError = '') {
    $script:cases++
    Get-ChildItem -LiteralPath $release -File | Remove-Item -Force
    Get-ChildItem -LiteralPath $evidence -File | Remove-Item -Force
    $global:releaseVerifierTest.SignatureExit = 0
    $global:releaseVerifierTest.ProvenanceExit = 0
    foreach ($component in @('server', 'web', 'backup', 'postgres', 'typesense', 'clamav')) {
        $prefix = Join-Path $release "smartupcms-$component-$version"
        "ghcr.io/example/smartupcms/$component@sha256:$('a' * 64)" | Set-Content "$prefix.image.txt"
        '{"spdxVersion":"SPDX-2.3"}' | Set-Content "$prefix.spdx.json"
        '{"bomFormat":"CycloneDX"}' | Set-Content "$prefix.cdx.json"
        '{"mediaType":"application/vnd.dev.sigstore.bundle.v0.3+json"}' | Set-Content "$prefix.provenance.json"
    }
    'synthetic archive bytes' | Set-Content (Join-Path $release "smartupcms-$version.tar.gz")
    'synthetic archive bytes' | Set-Content (Join-Path $release "smartupcms-$version.zip")
    Write-Checksums
    & $Arrange
    $message = ''
    try {
        & $verifier -ReleaseDirectory $release -Repository 'example/cms' -Version $version -EvidenceDirectory $evidence *> (Join-Path $testRoot "$Name.log")
    }
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
    elseif ($Name -eq 'complete-bundle') {
        $arguments = $global:releaseVerifierTest.CosignArgs
        $pattern = $arguments[[array]::IndexOf($arguments, '--certificate-identity-regexp') + 1]
        $expected = 'https://github.com/example/cms/.github/workflows/release.yml@refs/tags/v1.2.3'
        if ($expected -cnotmatch $pattern -or $expected.Replace('v1.2.3', 'v1x2x3') -cmatch $pattern -or
            $expected.Replace('release.yml', 'releaseXyml') -cmatch $pattern) {
            $failures.Add('complete-bundle must bind the signature to the exact release workflow and version.')
        }
    }
}

try {
    '$global:releaseVerifierTest.CosignArgs = @($args); exit $global:releaseVerifierTest.SignatureExit' |
        Set-Content (Join-Path $testRoot 'cosign.ps1')
    'exit $global:releaseVerifierTest.ProvenanceExit' | Set-Content (Join-Path $testRoot 'gh.ps1')
    Invoke-Case 'complete-bundle' {}
    Invoke-Case 'missing-checksums' { Remove-Item (Join-Path $release 'SHA256SUMS') } 'SHA256SUMS is missing'
    Invoke-Case 'empty-checksums' { '' | Set-Content (Join-Path $release 'SHA256SUMS') -NoNewline } 'empty|missing from SHA256SUMS'
    Invoke-Case 'omitted-metadata' {
        $path = Join-Path $release 'SHA256SUMS'
        @(Get-Content $path | Where-Object { $_ -notmatch 'server.*\.spdx\.json' }) | Set-Content $path
    } 'missing from SHA256SUMS'
    Invoke-Case 'omitted-bundle' {
        Remove-Item (Join-Path $release "smartupcms-$version.zip")
        Write-Checksums
    } 'missing from SHA256SUMS|artifact is missing'
    Invoke-Case 'duplicate-checksum' {
        $path = Join-Path $release 'SHA256SUMS'
        Get-Content $path -First 1 | Add-Content $path
    } 'duplicate'
    Invoke-Case 'tampered-metadata' { 'changed' | Set-Content (Join-Path $release "smartupcms-server-$version.spdx.json") } 'Checksum mismatch'
    Invoke-Case 'missing-file' { Remove-Item (Join-Path $release "smartupcms-server-$version.spdx.json") } 'artifact is missing'
    Invoke-Case 'unsafe-path' { "$('a' * 64)  ../outside" | Set-Content (Join-Path $release 'SHA256SUMS') } 'unsafe path'
    Invoke-Case 'signature-failure' { $global:releaseVerifierTest.SignatureExit = 1 } 'cosign verify failed'
    Invoke-Case 'provenance-failure' { $global:releaseVerifierTest.ProvenanceExit = 1 } 'gh attestation verify failed'
    Invoke-Case 'stale-evidence' {
        '{"status":"PASS"}' | Set-Content (Join-Path $evidence "release-integrity-$version.json")
        $global:releaseVerifierTest.SignatureExit = 1
    } 'cosign verify failed'
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
