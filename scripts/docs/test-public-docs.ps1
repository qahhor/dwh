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
    'docs/onboarding.md',
    'docs/security/threat-model.md',
    'docs/ops/architecture-overview.md',
    'docs/ops/deployment-guide.md',
    'docs/ops/maintenance-guide.md',
    'docs/ops/operations-runbook.md',
    'docs/ops/production-launch-checklist.md',
    'docs/ops/rollback.md'
)

$retiredTerms = '(?i)control[ -]plane|fleet proxy|migrate-cp|web-instance|apps/instance|docker-compose\.fleet|DWH_CP_'
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
    if ($content -notmatch '(?im)^\*\*Статус:\*\* Заменено') {
        $errors.Add("Historical ADR is not explicitly superseded: $relativePath")
    }
}

$markdownFiles = @($requiredFiles + $activeDocs + $supersededAdrs) |
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
