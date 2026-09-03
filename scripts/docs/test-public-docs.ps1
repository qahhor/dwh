$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$requiredFiles = @(
    'README.md',
    'LICENSE',
    'NOTICE',
    'RELICENSE.md',
    'CONTRIBUTING.md',
    'CODE_OF_CONDUCT.md',
    'DCO',
    'SECURITY.md',
    'GOVERNANCE.md',
    'SUPPORT.md',
    'CHANGELOG.md',
    'docs/README.md',
    'docs/technical-specification.md',
    'docs/adr/ADR-0014-unified-open-source-runtime.md',
    '.github/pull_request_template.md',
    '.github/ISSUE_TEMPLATE/bug_report.yml',
    '.github/ISSUE_TEMPLATE/feature_request.yml',
    '.github/workflows/dco.yml'
)

$errors = [System.Collections.Generic.List[string]]::new()

foreach ($relativePath in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath))) {
        $errors.Add("Missing public project file: $relativePath")
    }
}

$activeDocs = @(
    'README.md',
    'CONTRIBUTING.md',
    'docs/README.md',
    'docs/technical-specification.md',
    'docs/onboarding.md',
    'docs/architecture/biruni-smartup-conventions.md',
    'docs/architecture/monorepo-structure.md',
    'docs/guidelines/database-migrations.md',
    'docs/guidelines/module-development-guide.md',
    'docs/guidelines/testing-strategy.md',
    'docs/security/threat-model.md',
    'docs/ops/architecture-overview.md',
    'docs/ops/deployment-guide.md',
    'docs/ops/maintenance-guide.md',
    'docs/ops/operations-runbook.md',
    'docs/ops/production-launch-checklist.md',
    'docs/ops/rollback.md',
    'docs/runbooks/RB-04-migration-failure-triage.md'
)

$retiredTerms = '(?i)apps/(?:instance|control-plane|web-instance|web-cp)|docker-compose\.fleet|migrate-cp|DWH_CP_|TRD-\d|TZ-\d'
foreach ($relativePath in $activeDocs) {
    $absolutePath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath)) {
        $errors.Add("Missing active document: $relativePath")
        continue
    }

    $content = Get-Content -LiteralPath $absolutePath -Raw
    if ($content -match $retiredTerms) {
        $errors.Add("Retired platform terminology remains in active document: $relativePath")
    }
}

$supersededAdrs = @(
    'docs/adr/ADR-0004-deployment-model.md',
    'docs/adr/ADR-0007-fleet-strategy.md'
)
foreach ($relativePath in $supersededAdrs) {
    $content = Get-Content -LiteralPath (Join-Path $repoRoot $relativePath) -Raw
    if ($content -notmatch 'Заменено ADR-0014') {
        $errors.Add("Historical ADR is not explicitly superseded: $relativePath")
    }
}

$partiallySupersededAdrs = @(
    'docs/adr/ADR-0001-architecture-model.md',
    'docs/adr/ADR-0003-tenancy-rbac.md',
    'docs/adr/ADR-0006-modular-monolith.md',
    'docs/adr/ADR-0008-security-baseline.md',
    'docs/adr/ADR-0009-observability.md',
    'docs/adr/ADR-0010-resilience-tiers.md',
    'docs/adr/ADR-0011-provider-spi.md'
)
foreach ($relativePath in $partiallySupersededAdrs) {
    $content = Get-Content -LiteralPath (Join-Path $repoRoot $relativePath) -Raw
    if ($content -notmatch 'Частично заменено ADR-0014|Частично заменён ADR-0014') {
        $errors.Add("Historical ADR is not explicitly partially superseded: $relativePath")
    }
}

$markdownFiles = @($requiredFiles + $activeDocs + $supersededAdrs + $partiallySupersededAdrs) |
    Where-Object { $_ -match '\.md$' } |
    Sort-Object -Unique

foreach ($relativePath in $markdownFiles) {
    $absolutePath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath)) {
        continue
    }

    $content = Get-Content -LiteralPath $absolutePath -Raw
    foreach ($match in [regex]::Matches($content, '(?<!\!)\[[^\]]+\]\(([^)]+)\)')) {
        $target = $match.Groups[1].Value.Trim()
        if ($target -match '^(?:https?://|mailto:|#)') {
            continue
        }

        $pathOnly = ($target -split '#', 2)[0].Trim('<', '>')
        if ([string]::IsNullOrWhiteSpace($pathOnly)) {
            continue
        }

        $resolved = Join-Path (Split-Path $absolutePath -Parent) $pathOnly
        if (-not (Test-Path -LiteralPath $resolved)) {
            $errors.Add("Broken relative link in ${relativePath}: $target")
        }
    }
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    exit 1
}

Write-Host "Public documentation contract passed ($($requiredFiles.Count) required files, $($markdownFiles.Count) Markdown files)."
