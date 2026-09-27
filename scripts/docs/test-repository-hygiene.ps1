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

# Plan 10/10, item 1.8: a check that no workflow runs is a check that silently rots. Every scripts/**/test-* script
# is called by a workflow, directly or through another script a workflow calls.
$workflowText = (Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github/workflows') -Filter '*.yml' |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
$checkScripts = @($tracked | Where-Object { $_ -match '^scripts/.+/test-[^/]+\.(ps1|sh)$' })
$reached = [System.Collections.Generic.HashSet[string]]::new()
$frontier = [System.Collections.Generic.List[string]]::new()
foreach ($script in $checkScripts) {
    if ($workflowText.Contains($script) -or $workflowText.Contains((Split-Path $script -Leaf))) {
        if ($reached.Add($script)) { $frontier.Add($script) }
    }
}
while ($frontier.Count -gt 0) {
    $caller = $frontier[0]
    $frontier.RemoveAt(0)
    $callerText = Get-Content -LiteralPath (Join-Path $repoRoot $caller) -Raw
    foreach ($script in $checkScripts) {
        if (-not $reached.Contains($script) -and $callerText.Contains((Split-Path $script -Leaf))) {
            [void]$reached.Add($script)
            $frontier.Add($script)
        }
    }
}
foreach ($script in $checkScripts) {
    if (-not $reached.Contains($script)) {
        $errors.Add("Check script is not run by any workflow: $script (call it from .github/workflows, e.g. nightly.yml)")
    }
}

if ($errors.Count -gt 0) {
    throw "Repository hygiene contract failed:`n - $($errors -join "`n - ")"
}

Write-Host 'Repository hygiene contract passed.'
