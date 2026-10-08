[CmdletBinding()]
param(
    # The day the model is judged on: the release commit's date in release.yml, today otherwise.
    [string]$AsOf = (Get-Date).ToString('yyyy-MM-dd'),
    [int]$MaxAgeDays = 30,
    # Pull requests only warn about an old model; a release fails on it.
    [switch]$WarnOnly
)

# Plan 10/10, item 7.6: the threat model is dated no more than $MaxAgeDays days before a release. The date is the
# "**Updated:** YYYY-MM-DD" line of docs/security/threat-model.md, its label written in Russian. Always fails when the line is missing or the date
# lies after $AsOf; fails on an old date unless -WarnOnly. The rule itself is checked first on fixed samples.

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$modelPath = Join-Path $repoRoot 'docs/security/threat-model.md'
# The Russian label in \u escapes: Windows PowerShell 5.1 reads this BOM-less file in the ANSI code page.
$datePattern = '(?m)^\*\*' + [regex]::Unescape('\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u043e') +
    ':\*\*\s*(\d{4}-\d{2}-\d{2})\s*$'

function Get-ModelAge([string]$Text, [datetime]$Day) {
    $match = [regex]::Match($Text, $datePattern)
    if (-not $match.Success) { throw 'The threat model has no "**Updated:** YYYY-MM-DD" line (in Russian).' }
    $updated = [datetime]::ParseExact($match.Groups[1].Value, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
    $age = ($Day.Date - $updated.Date).Days
    if ($age -lt 0) { throw "The threat model is dated $($match.Groups[1].Value), after $($Day.ToString('yyyy-MM-dd'))." }
    return $age
}

# The rule on samples: a date counts in days, a future date and a missing line fail.
$label = [regex]::Unescape('\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u043e')
$sample = "# Model`n`n**${label}:** 2026-10-07`n"
if ((Get-ModelAge $sample ([datetime]'2026-11-06')) -ne 30) { throw 'Freshness self-check: 30 days not counted.' }
if ((Get-ModelAge $sample ([datetime]'2026-11-07')) -ne 31) { throw 'Freshness self-check: 31 days not counted.' }
foreach ($bad in @(@($sample, '2026-10-06'), @("# Model`n", '2026-10-07'))) {
    $failed = $false
    try { Get-ModelAge $bad[0] ([datetime]$bad[1]) | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw "Freshness self-check: a bad sample passed as of $($bad[1])." }
}

$day = [datetime]::ParseExact($AsOf, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
$age = Get-ModelAge (Get-Content -LiteralPath $modelPath -Raw -Encoding UTF8) $day
if ($age -gt $MaxAgeDays) {
    $message = "docs/security/threat-model.md was reviewed $age days before $AsOf; a release needs at most $MaxAgeDays."
    if (-not $WarnOnly) { throw $message }
    if ($env:GITHUB_ACTIONS) { Write-Host "::warning file=docs/security/threat-model.md::$message" }
    else { Write-Warning $message }
    return
}
Write-Host "Threat model reviewed $age day(s) before $AsOf (limit $MaxAgeDays)." -ForegroundColor Green
