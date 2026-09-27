$ErrorActionPreference = 'Stop'

# Plan 10/10, item 1.4: a skipped test is a hole nobody sees. Testcontainers suites marked
# disabledWithoutDocker skip silently where Docker is missing; this check makes any skip fail the build.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$reportDirectories = @('apps', 'libs') | ForEach-Object { [System.IO.Path]::Combine($repoRoot, $_, '*', 'target', 'surefire-reports') }
$reports = @(Resolve-Path -Path $reportDirectories -ErrorAction SilentlyContinue |
    ForEach-Object { Get-ChildItem -LiteralPath $_.Path -Filter 'TEST-*.xml' -File })

if ($reports.Count -eq 0) {
    throw 'No surefire reports found: run mvn verify first.'
}

$skipped = [System.Collections.Generic.List[string]]::new()
$total = 0
foreach ($report in $reports) {
    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
    $suite = $xml.testsuite
    $total += [int]$suite.tests
    foreach ($case in @($suite.testcase)) {
        if ($null -ne $case -and $null -ne $case.skipped) {
            $skipped.Add("$($case.classname).$($case.name)")
        }
    }
}

if ($skipped.Count -gt 0) {
    Write-Error ("$($skipped.Count) of $total tests were skipped; every test must run:`n  " + ($skipped -join "`n  "))
    exit 1
}

Write-Output "No skipped tests: $total tests in $($reports.Count) suites ran."
