# Applies .github/rulesets/*.json to the GitHub repository (plan 10/10, item 1.9): creates a ruleset that does not
# exist yet and replaces one of the same name. Needs the GitHub CLI signed in as a repository administrator
# (gh auth login). Without -Apply it only prints what it would do.
#
#   ./scripts/github/apply-rulesets.ps1                 # plan
#   ./scripts/github/apply-rulesets.ps1 -Apply          # apply
[CmdletBinding()]
param(
    [string]$Repository,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

& (Join-Path $PSScriptRoot 'test-rulesets.ps1')

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'The GitHub CLI (gh) is required: https://cli.github.com/'
}
if (-not $Repository) {
    $Repository = (& gh repo view --json nameWithOwner --jq .nameWithOwner).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $Repository) { throw 'Could not resolve the repository; pass -Repository owner/name.' }
}

$existing = @{}
$listing = & gh api "repos/$Repository/rulesets" --paginate
if ($LASTEXITCODE -ne 0) { throw "Could not list the rulesets of $Repository (is gh signed in as an administrator?)." }
foreach ($ruleset in ($listing | ConvertFrom-Json)) { $existing[$ruleset.name] = $ruleset.id }

foreach ($file in Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github/rulesets') -Filter '*.json' | Sort-Object Name) {
    $name = (Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json).name
    if ($existing.ContainsKey($name)) {
        $method = 'PUT'
        $path = "repos/$Repository/rulesets/$($existing[$name])"
    } else {
        $method = 'POST'
        $path = "repos/$Repository/rulesets"
    }
    if (-not $Apply) {
        Write-Host "would $method '$name' ($($file.Name)) -> $path"
        continue
    }
    & gh api --method $method $path --input $file.FullName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not $method ruleset '$name'." }
    Write-Host "$method '$name' applied." -ForegroundColor Green
}

if (-not $Apply) {
    Write-Host 'Nothing changed. Run again with -Apply to write these rulesets.' -ForegroundColor Yellow
}
