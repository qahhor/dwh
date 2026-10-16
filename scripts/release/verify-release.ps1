param(
    [string]$WorkflowPath = '.github/workflows/release.yml',
    [string]$PrereleaseWorkflowPath = '.github/workflows/prerelease.yml'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$errors = [System.Collections.Generic.List[string]]::new()

function Add-ContractError([string]$Message) {
    $errors.Add($Message)
}

function Assert-Matches([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) { Add-ContractError $Message }
}

function Assert-DoesNotMatch([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -match $Pattern) { Add-ContractError $Message }
}

$stableSemVerPattern = '^v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$'
foreach ($validRef in @('v0.1.0', 'v1.0.0', 'v12.34.56')) {
    if ($validRef -notmatch $stableSemVerPattern) {
        Add-ContractError "Stable SemVer validator rejected '$validRef'."
    }
}
foreach ($invalidRef in @('main', 'refs/tags/v1.2.3', 'v1.2', 'v01.2.3', 'v1.02.3', 'v1.2.03', 'v1.2.3-rc.1', 'v1.2.3+build')) {
    if ($invalidRef -match $stableSemVerPattern) {
        Add-ContractError "Stable SemVer validator accepted '$invalidRef'."
    }
}

$absoluteWorkflowPath = if ([System.IO.Path]::IsPathRooted($WorkflowPath)) {
    $WorkflowPath
} else {
    Join-Path $repoRoot $WorkflowPath
}
if (-not (Test-Path -LiteralPath $absoluteWorkflowPath -PathType Leaf)) {
    Add-ContractError "Missing release workflow: $WorkflowPath"
    $workflow = ''
}
else {
    $workflow = Get-Content -LiteralPath $absoluteWorkflowPath -Raw
}

Assert-Matches $workflow '(?ms)^on:\s*\r?\n\s+push:\s*\r?\n\s+tags:' 'Release workflow must be triggered by tag pushes.'
Assert-DoesNotMatch $workflow '(?m)^\s*(pull_request|pull_request_target|workflow_dispatch|schedule)\s*:' 'Release workflow must not run for pull requests, manual dispatch, or schedules.'
Assert-Matches $workflow '(?m)^permissions:\s*\{\}\s*$' 'Release workflow must deny token permissions by default.'
Assert-DoesNotMatch $workflow '\$\{\{\s*secrets\.' 'Release workflow must not depend on repository release secrets.'
Assert-Matches (Get-Content (Join-Path $PSScriptRoot 'release_policy.py') -Raw) '"merge-base", "--is-ancestor"' 'Release commit must be verified as reachable from the default branch.'
Assert-Matches (Get-Content (Join-Path $PSScriptRoot 'release_policy.py') -Raw) 'STABLE =' 'Workflow must contain an anchored stable SemVer gate.'
Assert-Matches $workflow '(?m)^concurrency:' 'Release workflow must serialize a release tag.'

$usesMatches = [regex]::Matches($workflow, '(?m)^\s*-?\s*uses:\s*([^\s#]+)')
$node24ActionPins = @{
    'actions/checkout'          = 'd23441a48e516b6c34aea4fa41551a30e30af803'
    'actions/setup-node'        = '249970729cb0ef3589644e2896645e5dc5ba9c38'
    'actions/setup-java'        = '03ad4de0992f5dab5e18fcb136590ce7c4a0ac95'
    'actions/upload-artifact'   = 'b7c566a772e6b6bfb58ed0dc250532a479d7789f'
    'actions/download-artifact' = '37930b1c2abaa49bbe596cd826c3c89aef350131'
}
foreach ($workflowFile in Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github/workflows') -Filter '*.yml') {
    $candidateWorkflow = Get-Content -LiteralPath $workflowFile.FullName -Raw
    foreach ($usesMatch in [regex]::Matches($candidateWorkflow, '(?m)^\s*-?\s*uses:\s*([^\s#]+)')) {
        $reference = $usesMatch.Groups[1].Value
        if ($reference.StartsWith('./')) { continue }
        if ($reference -notmatch '@[0-9a-f]{40}$') {
            Add-ContractError "External action is not pinned to a full commit SHA in $($workflowFile.Name): $reference"
        }
        $referenceParts = $reference -split '@', 2
        if ($referenceParts.Count -eq 2 -and $node24ActionPins.ContainsKey($referenceParts[0]) -and
            $referenceParts[1] -ne $node24ActionPins[$referenceParts[0]]) {
            Add-ContractError "Official action is not pinned to the approved Node 24 release in $($workflowFile.Name): $reference"
        }
    }
}

if ($workflow -and $usesMatches.Count -eq 0) {
    Add-ContractError 'Release workflow does not contain pinned actions.'
}

foreach ($image in @('server', 'web', 'backup', 'postgres', 'typesense', 'clamav')) {
    Assert-Matches $workflow "(?m)^\s*-?\s*image:\s*$image\s*$" "Release matrix is missing image '$image'."
    Assert-Matches $workflow ([regex]::Escape("smartupcms/$image")) "Release workflow is missing the versioned '$image' image reference."
}

Assert-Matches $workflow 'linux/amd64,linux/arm64' 'Release images must target linux/amd64 and linux/arm64.'
# Plan 10/10, item 1.9: build -> scan -> tag by digest. The image is pushed by digest without a tag, and only a
# scanned, attested and signed digest receives the version tag, so a failed scan leaves no tag in the registry.
Assert-Matches $workflow 'push-by-digest=true' 'Release build must push the image by digest, without a tag.'
Assert-DoesNotMatch $workflow '(?m)^\s*tags:\s*\$\{\{\s*steps\.reference' 'Release build must not tag the image before it is scanned.'
Assert-Matches $workflow '(?i)scan-type:\s*image' 'Release workflow must scan published image digests with Trivy.'
Assert-Matches $workflow 'aquasecurity/trivy-action@a9c7b0f06e461e9d4b4d1711f154ee024b8d7ab8' 'Release workflow must use pinned Trivy action.'
$scanAt = $workflow.IndexOf('scan-type: image')
$signAt = $workflow.IndexOf('cosign sign --yes')
$tagAt = $workflow.IndexOf('imagetools create --tag')
if ($tagAt -lt 0 -or $scanAt -lt 0 -or $signAt -lt 0 -or $tagAt -lt $scanAt -or $tagAt -lt $signAt) {
    Add-ContractError 'Release workflow must tag the digest (imagetools create --tag) only after the Trivy scan and the Cosign signature.'
}
# The release runs the same jobs as CI (secret scanning included) instead of a reduced copy of them.
Assert-Matches $workflow '(?m)^\s*uses:\s*\./\.github/workflows/ci\.yml\s*$' 'Release workflow must run ci.yml on the release commit (workflow_call).'
$ciWorkflow = Get-Content -LiteralPath (Join-Path $repoRoot '.github/workflows/ci.yml') -Raw
Assert-Matches $ciWorkflow '(?m)^\s*workflow_call:' 'ci.yml must be callable by the release workflow.'
Assert-Matches $ciWorkflow 'gitleaks/gitleaks-action@' 'ci.yml must scan the Git history for secrets.'
$bundle = Get-Content (Join-Path $PSScriptRoot 'build_release_bundle.py') -Raw
foreach ($required in @('rollback.ps1', 'test-recovery.ps1', 'restore-combined.ps1',
    'invoke-managed-preflight.ps1', 'invoke-managed-host-check.ps1', 'verify-published-release.ps1',
    'test-release-gates.ps1', 'deploy/compose/.env.example', '.env.managed.example',
    '.env.staging.example', '.env.acceptance.example', 'CODE_OF_CONDUCT.md', 'release.json', 'REVISION', 'IMAGES.txt')) {
    Assert-Matches $bundle ([regex]::Escape($required)) "Shared release bundle is missing $required."
}
Assert-Matches $bundle '"docs"' 'Release bundle must contain tracked public documentation.'
Assert-Matches $bundle 'git\(root, "ls-files", "-z"\)' 'Release bundle must copy only tracked sources.'
Assert-Matches $bundle 'sourceSha=source_sha' 'Release metadata must bind the explicit source SHA.'
Assert-Matches (Get-Content (Join-Path $repoRoot 'scripts/security/scan-runtime-images.ps1') -Raw) 'deploy/images/clamav' 'Runtime image scanner must include ClamAV.'

function Get-Job([string]$Text, [string]$Name) {
    $match = [regex]::Match($Text, '(?ms)^  ' + $Name + ':\r?\n(.*?)(?=^  [a-z][a-z0-9_-]*:|\z)')
    if (-not $match.Success) { Add-ContractError "Release workflow is missing job $Name." }
    return $match.Groups[1].Value
}

function Assert-JobPermissions([string]$Job, [string[]]$Expected, [string]$Label) {
    $match = [regex]::Match($Job, '(?ms)^    permissions:\r?\n((?:      [a-z-]+: [a-z]+\r?\n)+)')
    $actual = @($match.Groups[1].Value.Trim() -split '\r?\n' | ForEach-Object { $_.Trim() })
    if (@(Compare-Object ($actual | Sort-Object) ($Expected | Sort-Object)).Count -ne 0) {
        Add-ContractError "$Label has unexpected token permissions."
    }
}

function Assert-Channel([string]$Text, [string]$Channel) {
    $Text = $Text.Replace("`r`n", "`n")
    $gate = Get-Job $Text 'gate'
    $ci = Get-Job $Text 'ci'
    $build = Get-Job $Text 'build'
    $publish = Get-Job $Text 'publish'
    Assert-Matches $Text '(?m)^permissions:\s*\{\}\s*$' "$Channel must deny token permissions by default."
    Assert-DoesNotMatch $Text '(?m)^\s*(pull_request|pull_request_target|workflow_dispatch|schedule|workflow_call)\s*:' "$Channel must run only on tag push."
    Assert-DoesNotMatch $Text 'continue-on-error|:latest|if:\s*always\(' "$Channel may not bypass failed gates or publish latest tags."
    Assert-DoesNotMatch $Text '\$\{\{\s*secrets\.' "$Channel must use keyless credentials."
    Assert-Matches $Text '(?m)^  cancel-in-progress: false$' "$Channel must not interrupt an in-flight publication."
    $triggers = [regex]::Match($Text, '(?ms)^on:\r?\n  push:\r?\n    tags:\r?\n((?:      - [^\r\n]+\r?\n)+)').Groups[1].Value.Trim()
    $expected = if ($Channel -eq 'stable') { "- 'v*.*.*'`n      - '!v*.*.*-*'" } else { "- 'v*.*.*-rc.*'" }
    if ($triggers.Replace("`r", '') -ne $expected) { Add-ContractError "$Channel has an unsafe tag trigger." }
    Assert-Matches $gate ('release_policy\.py --channel ' + $Channel + '(?:\r?\n|$)') "$Channel gate must execute the source/ref/DCO policy."
    Assert-Matches $gate 'fetch-depth: 0' "$Channel gate needs complete Git history."
    Assert-Matches $gate '\$\{\{ github.event.repository.default_branch \}\}' "$Channel DCO baseline must be the actual default branch."
    Assert-Matches $gate 'test-threat-model-freshness\.ps1 -MaxAgeDays 30 -AsOf' "$Channel must enforce threat-model freshness."
    Assert-Matches $ci '(?m)^    needs: gate$' "$Channel CI must depend on its gate."
    Assert-Matches $ci '(?m)^    uses: \./\.github/workflows/ci\.yml$' "$Channel must reuse complete CI."
    Assert-Matches $build '(?m)^    needs: \[gate, ci\]$' "$Channel builds require gate and complete CI."
    Assert-Matches $publish '(?m)^    needs: build$' "$Channel publication requires all image builds."
    Assert-JobPermissions $gate @('contents: read') "$Channel gate"
    Assert-JobPermissions $ci @('actions: read', 'contents: read') "$Channel CI"
    Assert-JobPermissions $build @('contents: read', 'packages: write', 'id-token: write', 'attestations: write') "$Channel build"
    Assert-JobPermissions $publish @('actions: read', 'contents: write', 'id-token: write', 'attestations: write') "$Channel publish"
    foreach ($job in @($build, $publish)) {
        Assert-Matches $job ('release_policy\.py --channel ' + $Channel + ' --write-only(?:\r?\n|$)') "$Channel write job must refuse reruns before writes."
        if ($Channel -eq 'staging') {
            Assert-Matches $job '(?m)^    environment: staging-release$' 'Every staging writer requires the protected staging-release environment.'
            Assert-Matches $job 'STAGING_APPROVED_SOURCE_SHA: \$\{\{ vars\.STAGING_APPROVED_SOURCE_SHA \}\}' 'Staging writer must receive the approved SHA from environment configuration.'
            Assert-Matches $job 'STAGING_APPROVED_VERSION: \$\{\{ vars\.STAGING_APPROVED_VERSION \}\}' 'Staging writer must receive the approved version from environment configuration.'
        }
    }
    foreach ($component in @('server', 'web', 'backup', 'postgres', 'typesense', 'clamav')) {
        Assert-Matches $build ('(?m)^          - image: ' + $component + '$') "$Channel image matrix is incomplete."
    }
    Assert-Matches $build 'platforms: linux/amd64,linux/arm64' "$Channel images require both architectures."
    Assert-Matches $build 'push-by-digest=true' "$Channel images must initially have no mutable tag."
    Assert-Matches $build 'scan-type: image' "$Channel images require vulnerability scanning."
    Assert-Matches $build 'severity: CRITICAL,HIGH' "$Channel scanner may not weaken severity."
    Assert-Matches $build "exit-code: '1'" "$Channel scanner must fail closed."
    Assert-Matches $build 'cosign sign --yes' "$Channel image signing is required."
    Assert-Matches $build 'attest-build-provenance@' "$Channel image provenance is required."
    Assert-Matches $build 'spdx-json' "$Channel SPDX is required."
    Assert-Matches $build 'cyclonedx-json' "$Channel CycloneDX is required."
    $guardAt = $build.IndexOf(' --write-only')
    $pushAt = $build.IndexOf('push-by-digest=true')
    $scanAt = $build.IndexOf('scan-type: image')
    $signAt = $build.IndexOf('cosign sign --yes')
    $tagAt = $build.IndexOf('imagetools create --tag')
    if ($guardAt -lt 0 -or $pushAt -lt $guardAt -or $scanAt -lt $pushAt -or $signAt -lt $scanAt -or $tagAt -lt $signAt) {
        Add-ContractError "$Channel must guard, build, scan, sign and only then tag."
    }
    Assert-Matches $publish ('build_release_bundle\.py --channel ' + $Channel) "$Channel must use the shared bundle builder."
    Assert-Matches $publish '\-\-source-sha "\$SOURCE_SHA"' "$Channel must bind bundle source SHA."
    Assert-Matches $publish 'cosign sign-blob --yes --bundle dist/SHA256SUMS\.sigstore\.json dist/SHA256SUMS' "$Channel must authenticate all bundle checksums."
    Assert-Matches $publish 'subject-path: dist/SHA256SUMS(?:\r?\n|$)' "$Channel must attest the checksum manifest."
    Assert-Matches $publish 'dist/SHA256SUMS\.provenance\.json' "$Channel must save checksum provenance."
    Assert-Matches $publish ('publish_release\.py --channel ' + $Channel) "$Channel must use the reviewed publication boundary."
    $bundleAt = $publish.IndexOf('build_release_bundle.py')
    $signAt = $publish.IndexOf('cosign sign-blob')
    $attestAt = $publish.IndexOf('subject-path: dist/SHA256SUMS')
    $publishAt = $publish.IndexOf('publish_release.py')
    if ($publish.IndexOf(' --write-only') -gt $bundleAt -or $signAt -lt $bundleAt -or $attestAt -lt $signAt -or $publishAt -lt $attestAt) {
        Add-ContractError "$Channel must guard, build, authenticate and attest the manifest before publishing."
    }
}

Assert-Channel $workflow 'stable'
$prereleasePath = if ([IO.Path]::IsPathRooted($PrereleaseWorkflowPath)) { $PrereleaseWorkflowPath } else { Join-Path $repoRoot $PrereleaseWorkflowPath }
if (Test-Path -LiteralPath $prereleasePath -PathType Leaf) {
    Assert-Channel (Get-Content -LiteralPath $prereleasePath -Raw) 'staging'
} else { Add-ContractError 'The separate staging prerelease workflow is missing.' }

$dockerfiles = @(
    'Dockerfile',
    'apps/web/Dockerfile',
    'deploy/images/backup/Dockerfile',
    'deploy/images/postgres/Dockerfile',
    'deploy/images/typesense/Dockerfile',
    'deploy/images/clamav/Dockerfile'
)
foreach ($dockerfile in $dockerfiles) {
    $absoluteDockerfile = Join-Path $repoRoot $dockerfile
    $source = Get-Content -LiteralPath $absoluteDockerfile -Raw
    foreach ($from in [regex]::Matches($source, '(?im)^FROM\s+([^\s]+)')) {
        $base = $from.Groups[1].Value
        if ($base -match '^\$\{([^}]+)\}$') {
            $argument = [regex]::Escape($Matches[1])
            if ($source -notmatch "(?im)^ARG\s+$argument=[^\s]+@sha256:[0-9a-f]{64}\s*$") {
                Add-ContractError "Unpinned build argument '$($Matches[1])' in $dockerfile."
            }
        }
        elseif ($base -notmatch '@sha256:[0-9a-f]{64}$') {
            Add-ContractError "Unpinned base image '$base' in $dockerfile."
        }
    }
}

$composePath = Join-Path $repoRoot 'deploy/compose/docker-compose.prod.yml'
$compose = Get-Content -LiteralPath $composePath -Raw
foreach ($image in @('server', 'web', 'backup', 'postgres', 'typesense', 'clamav')) {
    Assert-Matches $compose ([regex]::Escape("/${image}:" + '${APP_VERSION')) "Production Compose does not pin '$image' to APP_VERSION."
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Host 'Release supply-chain contract passed: tag gate, pinned actions/bases, six signed multi-arch images, SBOM, provenance, checksums, and bundle.'
