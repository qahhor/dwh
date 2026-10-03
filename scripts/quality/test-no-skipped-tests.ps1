$ErrorActionPreference = 'Stop'

# Plan 10/10, item 1.4: a skipped test is a hole nobody sees. Testcontainers suites marked
# disabledWithoutDocker skip silently where Docker is missing; this check makes any skip fail the build.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$reportDirectories = @('apps', 'libs', 'examples') | ForEach-Object { [System.IO.Path]::Combine($repoRoot, $_, '*', 'target', 'surefire-reports') }
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

# The web unit tests (Vitest) and the browser suites (Playwright) are checked in their sources: a skipped, pending
# or focused test (skip, todo, fixme, only, xit, xdescribe, xtest) never reaches main. A known unstable E2E test is
# listed in e2e/quarantine.json instead, where its owner and its deadline are visible.
$specRoots = @('apps/web/src', 'e2e/tests')
$skipCall = [regex]::new('\b(?:it|test|describe|suite)(?:\.\w+)*\.(?:skip|todo|fixme|only)\s*\(|\bx(?:it|describe|test)\s*\(')
$sourceSkips = [System.Collections.Generic.List[string]]::new()
foreach ($root in $specRoots) {
    $path = Join-Path $repoRoot $root
    if (-not (Test-Path -LiteralPath $path)) { continue }
    foreach ($file in Get-ChildItem -LiteralPath $path -Recurse -File -Include '*.spec.ts', '*.test.ts', '*.spec.mjs', '*.test.mjs') {
        $lineNumber = 0
        foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
            $lineNumber++
            if ($skipCall.IsMatch($line)) {
                $relative = $file.FullName.Substring($repoRoot.Length + 1).Replace('\', '/')
                $sourceSkips.Add("${relative}:$lineNumber")
            }
        }
    }
}
# The pattern itself is checked: the forms above match, an ordinary test or a word ending in "only" does not.
foreach ($sample in @("it.skip('x', () => {})", "test.describe.skip('x'", "test.fixme('x'", "it.todo('x')", "describe.only('x'", "xit('x'", "test.skip(!enabled, 'reason')")) {
    if (-not $skipCall.IsMatch($sample)) { $sourceSkips.Add("pattern misses: $sample") }
}
foreach ($sample in @("it('skips nothing', () => {})", "readonly('x')", "test('only once', async () => {})")) {
    if ($skipCall.IsMatch($sample)) { $sourceSkips.Add("pattern flags: $sample") }
}

if ($skipped.Count -gt 0 -or $sourceSkips.Count -gt 0) {
    $message = ''
    if ($skipped.Count -gt 0) {
        $message += "$($skipped.Count) of $total tests were skipped; every test must run:`n  " + ($skipped -join "`n  ") + "`n"
    }
    if ($sourceSkips.Count -gt 0) {
        $message += "Skipped, pending or focused web/E2E tests:`n  " + ($sourceSkips -join "`n  ")
    }
    Write-Error $message
    exit 1
}

Write-Output "No skipped tests: $total tests in $($reports.Count) suites ran; no skipped or focused web/E2E test."
