# Plan 10/10, item 1.9: the rulesets in .github/rulesets are the branch and tag protection of the repository,
# applied by an administrator with scripts/github/apply-rulesets.ps1. This contract keeps them consistent with the
# workflows: a required check that no job produces would block every pull request, and a renamed job would silently
# stop being required.
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$errors = [System.Collections.Generic.List[string]]::new()

function Get-JobNames([string]$workflowText) {
    # The display names of the jobs, a matrix name expanded over its values (one level: `key: [a, b]`).
    $names = [System.Collections.Generic.List[string]]::new()
    $jobsAt = $workflowText.IndexOf("`njobs:")
    if ($jobsAt -lt 0) { return $names }
    $jobs = $workflowText.Substring($jobsAt)
    $blocks = [regex]::Split($jobs, '(?m)^  (?=[A-Za-z0-9_-]+:\s*$)')
    foreach ($block in $blocks) {
        $nameMatch = [regex]::Match($block, '(?m)^    name:\s*(.+?)\s*$')
        if (-not $nameMatch.Success) { continue }
        $name = $nameMatch.Groups[1].Value.Trim("'", '"')
        $matrixRefs = [regex]::Matches($name, '\$\{\{\s*matrix\.([A-Za-z0-9_-]+)\s*\}\}')
        if ($matrixRefs.Count -eq 0) { $names.Add($name); continue }
        $expanded = @($name)
        foreach ($ref in $matrixRefs) {
            $key = $ref.Groups[1].Value
            $valuesMatch = [regex]::Match($block, "(?m)^\s+${key}:\s*\[([^\]]*)\]")
            if (-not $valuesMatch.Success) { $errors.Add("Matrix '$key' of job '$name' is not a one-line list."); continue }
            $values = $valuesMatch.Groups[1].Value -split ',' | ForEach-Object { $_.Trim().Trim("'", '"') } | Where-Object { $_ }
            $expanded = foreach ($candidate in $expanded) { foreach ($value in $values) { $candidate.Replace($ref.Value, $value) } }
        }
        $expanded | ForEach-Object { $names.Add($_) }
    }
    return $names
}

$rulesets = @{}
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github/rulesets') -Filter '*.json') {
    try {
        $rulesets[$file.Name] = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    } catch {
        $errors.Add("Ruleset $($file.Name) is not valid JSON: $($_.Exception.Message)")
    }
}

# Jobs that report on a pull request.
$pullRequestJobs = [System.Collections.Generic.HashSet[string]]::new()
foreach ($workflowFile in Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github/workflows') -Filter '*.yml') {
    $text = (Get-Content -LiteralPath $workflowFile.FullName -Raw) -replace "`r`n", "`n"
    if ($text -notmatch '(?m)^\s+pull_request:|^on:\s*pull_request\s*$|^on:\s*\[[^\]]*pull_request') { continue }
    foreach ($name in (Get-JobNames $text)) { [void]$pullRequestJobs.Add($name) }
}

$main = $rulesets['main.json']
if (-not $main) {
    $errors.Add('Missing .github/rulesets/main.json.')
} else {
    if ($main.target -ne 'branch' -or $main.enforcement -ne 'active' -or $main.conditions.ref_name.include -notcontains '~DEFAULT_BRANCH') {
        $errors.Add('main.json must be an active branch ruleset on the default branch.')
    }
    $checks = @($main.rules | Where-Object { $_.type -eq 'required_status_checks' })
    if ($checks.Count -ne 1) { $errors.Add('main.json must have one required_status_checks rule.') }
    foreach ($check in $checks.parameters.required_status_checks) {
        if (-not $pullRequestJobs.Contains($check.context)) {
            $errors.Add("Required check '$($check.context)' is produced by no job that runs on pull requests.")
        }
    }
    $pullRequest = @($main.rules | Where-Object { $_.type -eq 'pull_request' })
    if ($pullRequest.Count -ne 1 -or $pullRequest[0].parameters.required_approving_review_count -lt 1) {
        $errors.Add('main.json must require a reviewed pull request.')
    } elseif ((@($pullRequest[0].parameters.allowed_merge_methods) -join ',') -ne 'merge') {
        # A squash or a rebase rewrites the commits that .git-blame-ignore-revs names.
        $errors.Add('main.json must allow merge commits only (.git-blame-ignore-revs names commits by SHA).')
    }
    if (-not ($main.rules | Where-Object { $_.type -eq 'code_scanning' })) {
        $errors.Add('main.json must require code scanning results.')
    }
}

$immutable = $rulesets.Values | Where-Object {
    $_.target -eq 'tag' -and $_.conditions.ref_name.include -contains 'refs/tags/v*' -and @($_.bypass_actors).Count -eq 0
}
$ruleTypes = @($immutable | ForEach-Object { $_.rules.type })
if ($ruleTypes -notcontains 'update' -or $ruleTypes -notcontains 'deletion') {
    $errors.Add('A tag ruleset without bypass must forbid moving and deleting refs/tags/v*.')
}
$creation = $rulesets.Values | Where-Object {
    $_.target -eq 'tag' -and $_.conditions.ref_name.include -contains 'refs/tags/v*' -and ($_.rules.type -contains 'creation')
}
if (-not $creation) { $errors.Add('A tag ruleset must restrict who creates refs/tags/v*.') }

if (-not (Test-Path -LiteralPath (Join-Path $repoRoot '.github/CODEOWNERS'))) {
    $errors.Add('Missing .github/CODEOWNERS: main.json requires a code owner review.')
}

if ($errors.Count -gt 0) {
    throw "Ruleset contract failed:`n - $($errors -join "`n - ")"
}
Write-Host "Ruleset contract passed: $($rulesets.Count) rulesets, required checks match $($pullRequestJobs.Count) pull request jobs."
