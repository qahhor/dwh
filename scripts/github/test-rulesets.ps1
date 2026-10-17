# Plan 10/10, item 1.9: the rulesets in .github/rulesets are the branch and tag protection of the repository,
# applied by an administrator with scripts/github/apply-rulesets.ps1. This contract keeps them consistent with the
# workflows. Owner decision 2026-10-17: no workflow runs automatically, every gate runs locally before a merge
# (AGENTS.md, sections 3 and 5). A workflow is therefore started by hand (workflow_dispatch) or called by another
# (workflow_call); only release.yml and prerelease.yml start on their version tags, a deliberate act of a release
# administrator (scripts/release/verify-release.ps1 pins their tag patterns). Since no job reports on a pull
# request, main may require no status check and no code scanning result: either would block every pull request.
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$errors = [System.Collections.Generic.List[string]]::new()

$rulesets = @{}
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github/rulesets') -Filter '*.json') {
    try {
        $rulesets[$file.Name] = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    } catch {
        $errors.Add("Ruleset $($file.Name) is not valid JSON: $($_.Exception.Message)")
    }
}

# Triggers: the top-level keys of the `on:` block of every workflow.
$manualTriggers = @('workflow_dispatch', 'workflow_call')
$tagWorkflows = @('release.yml', 'prerelease.yml')
$workflowCount = 0
foreach ($workflowFile in Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github/workflows') -Filter '*.yml') {
    $workflowCount++
    $text = (Get-Content -LiteralPath $workflowFile.FullName -Raw) -replace "`r`n", "`n"
    $onMatch = [regex]::Match($text, '(?m)^on:[ \t]*(?<inline>[^\n]*)\n(?<block>(?:[ \t]+[^\n]*\n|[ \t]*\n)*)')
    if (-not $onMatch.Success) { $errors.Add("$($workflowFile.Name) has no top-level 'on:' block."); continue }
    $inline = $onMatch.Groups['inline'].Value.Trim()
    if ($inline) {
        $triggers = @($inline.Trim('[', ']') -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    } else {
        $triggers = @([regex]::Matches($onMatch.Groups['block'].Value, '(?m)^  ([A-Za-z_]+):') | ForEach-Object { $_.Groups[1].Value })
    }
    if ($tagWorkflows -contains $workflowFile.Name) {
        if ((@($triggers) -join ',') -ne 'push' -or $onMatch.Groups['block'].Value -notmatch '(?m)^    tags:' -or
            $onMatch.Groups['block'].Value -match '(?m)^    (branches|branches-ignore|paths|paths-ignore):') {
            $errors.Add("$($workflowFile.Name) must start only on a pushed version tag.")
        }
        continue
    }
    foreach ($trigger in $triggers) {
        if ($manualTriggers -notcontains $trigger) {
            $errors.Add("$($workflowFile.Name) starts automatically on '$trigger': workflows run by hand only (workflow_dispatch).")
        }
    }
    if ($triggers -notcontains 'workflow_dispatch') {
        $errors.Add("$($workflowFile.Name) cannot be started by hand: add workflow_dispatch.")
    }
}

$main = $rulesets['main.json']
if (-not $main) {
    $errors.Add('Missing .github/rulesets/main.json.')
} else {
    if ($main.target -ne 'branch' -or $main.enforcement -ne 'active' -or $main.conditions.ref_name.include -notcontains '~DEFAULT_BRANCH') {
        $errors.Add('main.json must be an active branch ruleset on the default branch.')
    }
    # No job runs on a pull request, so a required check or a required code scanning result would never arrive.
    if ($main.rules | Where-Object { $_.type -eq 'required_status_checks' }) {
        $errors.Add('main.json must not require status checks: no workflow runs on pull requests, the gates run locally.')
    }
    $pullRequest = @($main.rules | Where-Object { $_.type -eq 'pull_request' })
    if ($pullRequest.Count -ne 1 -or $pullRequest[0].parameters.required_approving_review_count -lt 1) {
        $errors.Add('main.json must require a reviewed pull request.')
    } elseif ((@($pullRequest[0].parameters.allowed_merge_methods) -join ',') -ne 'merge') {
        # A squash or a rebase rewrites the commits that .git-blame-ignore-revs names.
        $errors.Add('main.json must allow merge commits only (.git-blame-ignore-revs names commits by SHA).')
    }
    if ($main.rules | Where-Object { $_.type -eq 'code_scanning' }) {
        $errors.Add('main.json must not require code scanning results: codeql.yml runs by hand only.')
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
Write-Host "Ruleset contract passed: $($rulesets.Count) rulesets; $workflowCount workflows start by hand or on a release tag only."
