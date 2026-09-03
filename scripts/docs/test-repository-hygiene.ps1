$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$errors = [System.Collections.Generic.List[string]]::new()

$obsoletePaths = @(
    'REPORT.md',
    'STATS_MAP.md',
    'deploy/spike',
    'docs/audit',
    'docs/runbooks/RB-01-node-failure-recovery.md',
    'docs/runbooks/RB-02-vault-unseal-and-raft-quorum.md',
    'docs/runbooks/RB-03-license-key-emergency-rotation.md',
    'docs/superpowers/specs/2026-08-30-e2e-browser-testing-design.md',
    'docs/superpowers/specs/2026-08-30-ui-ux-hardening-design.md',
    'docs/superpowers/specs/2026-09-01-fleet-foundation-design.md',
    'docs/superpowers/plans/2026-08-30-e2e-browser-testing-implementation.md',
    'docs/superpowers/plans/2026-08-30-ui-feature-screens.md',
    'docs/superpowers/plans/2026-08-30-ui-foundation-entry-shell.md',
    'docs/superpowers/plans/2026-09-01-fleet-foundation-control-plane-contract-security-implementation.md'
)

$obsoleteAuditPaths = @(
    'audit/00-master-improvement-plan-2026-08-31.md',
    'audit/00-master-improvement-plan-2026-09-02.md',
    'audit/architecture-2026-08-31.md',
    'audit/architecture-2026-09-02.md',
    'audit/code-quality-2026-08-31.md',
    'audit/devops-2026-08-31.md',
    'audit/devops-2026-09-02.md',
    'audit/documentation-2026-08-31.md',
    'audit/documentation-2026-09-02.md',
    'audit/health-check-2026-08-31.md',
    'audit/performance-2026-08-31.md',
    'audit/security-2026-08-31.md',
    'audit/security-2026-09-02.md',
    'audit/testing-2026-08-31.md',
    'audit/testing-2026-09-02.md',
    'audit/widgets-2026-08-30.md',
    'audit/widgets-2026-08-31.md',
    'audit/widgets-2026-09-02.md',
    'audit/evidence/fleet-foundation-cp-contract-2026-09-01.md',
    'audit/evidence/verification-2026-08-31.md',
    'audit/evidence/web-cp-login-desktop.png',
    'audit/evidence/web-cp-login-mobile.png',
    'audit/evidence/web-instance-login-desktop.png',
    'audit/evidence/web-instance-login-mobile.png'
)

$obsoletePaths += $obsoleteAuditPaths

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
    if ($relativePath -match '^graphify-out/cache/' -or
        $relativePath -match '^graphify-out/\d{4}-\d{2}-\d{2}/' -or
        $relativePath -match '^\.playwright-cli/') {
        $errors.Add("Generated artifact is tracked: $relativePath")
    }
}

if ($errors.Count -gt 0) {
    throw "Repository hygiene contract failed:`n - $($errors -join "`n - ")"
}

Write-Host 'Repository hygiene contract passed.'
