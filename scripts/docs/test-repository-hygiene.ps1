$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$errors = [System.Collections.Generic.List[string]]::new()

# Removed on purpose: dated audit reports, agent plans, design scratch, a machine-bound start script.
$obsoletePaths = @(
    'REPORT.md',
    'STATS_MAP.md',
    'audit',
    'deploy/spike',
    'docs/audit',
    'docs/superpowers',
    '.superdesign',
    'local-up.cmd'
)

foreach ($relativePath in $obsoletePaths) {
    if (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath)) {
        $errors.Add("Obsolete repository artifact remains: $relativePath")
    }
}

Push-Location $repoRoot
try {
    $tracked = @(git ls-files)
    if ($LASTEXITCODE -ne 0) {
        throw 'git ls-files failed'
    }
}
finally {
    Pop-Location
}

foreach ($relativePath in $tracked) {
    # The knowledge graph is built locally (graphify update .) and never committed.
    if ($relativePath -match '^graphify-out/' -or
        $relativePath -match '^\.playwright-cli/') {
        $errors.Add("Generated artifact is tracked: $relativePath")
    }
}

if ($errors.Count -gt 0) {
    throw "Repository hygiene contract failed:`n - $($errors -join "`n - ")"
}

Write-Host 'Repository hygiene contract passed.'
