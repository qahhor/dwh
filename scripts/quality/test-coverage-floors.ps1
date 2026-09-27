$ErrorActionPreference = 'Stop'

# Plan 10/10, item 1.4: coverage per business module of the server. JaCoCo's own rule covers a Maven module as a
# whole (pom.xml); a business module spans several packages, so its floor is checked here from jacoco.csv.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$csv = Join-Path $repoRoot 'apps/server/target/site/jacoco/jacoco.csv'
$floorsFile = Join-Path $repoRoot 'apps/server/coverage-floors.csv'
$reportFile = Join-Path $repoRoot 'apps/server/target/coverage/modules.md'

if (-not (Test-Path -LiteralPath $csv)) {
    throw "No coverage report at ${csv}: run mvn verify first."
}

function Get-Module([string]$package) {
    $parts = @($package -replace '^com\.smartup24\.cms\.instance\.?', '' -split '\.' | Where-Object { $_ })
    if ($parts.Count -eq 0) { return $null }
    if ($parts[0] -eq 'ms' -and $parts.Count -gt 1) { return "ms.$($parts[1])" }
    return $parts[0]
}

$measured = @{}
foreach ($row in Import-Csv -LiteralPath $csv) {
    $module = Get-Module $row.PACKAGE
    if (-not $module) { continue }
    if (-not $measured.ContainsKey($module)) { $measured[$module] = @{ LineMissed = 0; LineCovered = 0; BranchMissed = 0; BranchCovered = 0 } }
    $measured[$module].LineMissed += [int]$row.LINE_MISSED
    $measured[$module].LineCovered += [int]$row.LINE_COVERED
    $measured[$module].BranchMissed += [int]$row.BRANCH_MISSED
    $measured[$module].BranchCovered += [int]$row.BRANCH_COVERED
}

$floors = @{}
Get-Content -LiteralPath $floorsFile | Where-Object { $_ -and -not $_.StartsWith('#') } | ConvertFrom-Csv | ForEach-Object {
    $floors[$_.module] = @{ Line = [double]$_.line; Branch = [double]$_.branch }
}

function Get-Percent([int]$covered, [int]$missed) {
    if ($covered + $missed -eq 0) { return 100.0 }
    return [math]::Round(100.0 * $covered / ($covered + $missed), 1)
}

$errors = [System.Collections.Generic.List[string]]::new()
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('### Coverage by module (plan 10/10, 1.4)')
$lines.Add('')
$lines.Add('| Module | Lines | Floor | Branches | Floor |')
$lines.Add('|---|---|---|---|---|')
foreach ($module in ($measured.Keys | Sort-Object)) {
    $m = $measured[$module]
    $line = Get-Percent $m.LineCovered $m.LineMissed
    $branch = Get-Percent $m.BranchCovered $m.BranchMissed
    if (-not $floors.ContainsKey($module)) {
        $errors.Add("Module $module has no floor in apps/server/coverage-floors.csv: add it at its current $line / $branch.")
        continue
    }
    $floor = $floors[$module]
    if ($line -lt $floor.Line) { $errors.Add("Module ${module}: line coverage $line% is under its floor $($floor.Line)%.") }
    if ($branch -lt $floor.Branch) { $errors.Add("Module ${module}: branch coverage $branch% is under its floor $($floor.Branch)%.") }
    $lines.Add("| $module | $line% | $($floor.Line)% | $branch% | $($floor.Branch)% |")
}
foreach ($module in $floors.Keys) {
    if (-not $measured.ContainsKey($module)) {
        $errors.Add("Floor for $module, but the module has no code: remove it from apps/server/coverage-floors.csv.")
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path $reportFile) | Out-Null
Set-Content -LiteralPath $reportFile -Value ($lines -join "`n") -Encoding utf8
$lines | ForEach-Object { Write-Output $_ }

if ($errors.Count -gt 0) {
    Write-Error ("Coverage floors:`n  " + ($errors -join "`n  "))
    exit 1
}
Write-Output 'Coverage floors passed.'
