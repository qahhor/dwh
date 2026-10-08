$ErrorActionPreference = 'Stop'

# Plan 10/10, item 7.3 (docs/ops/slo.md): promtool checks the example configuration and the rules of
# deploy/observability/prometheus and runs their unit tests; every alert has a test, every Grafana dashboard is valid
# JSON with a uid and queries. The image is pinned by digest (approved 2026-10-07); scripts/observability/
# test-prometheus-rules.sh does the same on Linux and macOS.
$image = 'prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$prometheus = Join-Path $repoRoot 'deploy/observability/prometheus'
$dashboards = Join-Path $repoRoot 'deploy/observability/grafana/dashboards'
$errors = [System.Collections.Generic.List[string]]::new()

$ruleFiles = @(Get-ChildItem -LiteralPath (Join-Path $prometheus 'rules') -Filter '*.yml' -File | Sort-Object Name)
$testFiles = @(Get-ChildItem -LiteralPath (Join-Path $prometheus 'tests') -Filter '*.yml' -File | Sort-Object Name)
if ($ruleFiles.Count -eq 0 -or $testFiles.Count -eq 0) {
    throw 'No Prometheus rules or rule tests found in deploy/observability/prometheus'
}

# Every alert is exercised by a unit test (an alert_rule_test names it).
$alerts = @($ruleFiles | ForEach-Object {
        [regex]::Matches((Get-Content -LiteralPath $_.FullName -Raw), '(?m)^\s*-\s*alert:\s*(\S+)\s*$') |
            ForEach-Object { $_.Groups[1].Value }
    })
$tested = @($testFiles | ForEach-Object {
        [regex]::Matches((Get-Content -LiteralPath $_.FullName -Raw), '(?m)^\s*alertname:\s*(\S+)\s*$') |
            ForEach-Object { $_.Groups[1].Value }
    }) | Sort-Object -Unique
if ($alerts.Count -eq 0) {
    $errors.Add('No alert rules found')
}
foreach ($alert in $alerts) {
    if ($tested -notcontains $alert) {
        $errors.Add("Alert has no unit test in deploy/observability/prometheus/tests: $alert")
    }
}
foreach ($name in $tested) {
    if ($alerts -notcontains $name) {
        $errors.Add("Unit test names an alert that does not exist: $name")
    }
}

# Grafana dashboards stay JSON (no Grafana image is used): each parses, has a uid and a title, and every panel
# target has a query.
$dashboardFiles = @(Get-ChildItem -LiteralPath $dashboards -Filter '*.json' -File)
if ($dashboardFiles.Count -eq 0) {
    $errors.Add('No Grafana dashboards found in deploy/observability/grafana/dashboards')
}
$uids = @{}
foreach ($file in $dashboardFiles) {
    try {
        $dashboard = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    catch {
        $errors.Add("Dashboard is not valid JSON: $($file.Name) ($($_.Exception.Message))")
        continue
    }
    if ([string]::IsNullOrWhiteSpace($dashboard.uid) -or [string]::IsNullOrWhiteSpace($dashboard.title)) {
        $errors.Add("Dashboard has no uid or title: $($file.Name)")
    }
    elseif ($uids.ContainsKey($dashboard.uid)) {
        $errors.Add("Dashboard uid $($dashboard.uid) is used twice: $($file.Name), $($uids[$dashboard.uid])")
    }
    else {
        $uids[$dashboard.uid] = $file.Name
    }
    $targets = @($dashboard.panels | Where-Object { $_.targets } | ForEach-Object { $_.targets })
    if ($targets.Count -eq 0) {
        $errors.Add("Dashboard has no queries: $($file.Name)")
    }
    foreach ($target in $targets) {
        if ([string]::IsNullOrWhiteSpace($target.expr)) {
            $errors.Add("Dashboard panel target without a query: $($file.Name)")
        }
    }
}

if ($errors.Count -gt 0) {
    throw "Prometheus rules contract failed:`n - $($errors -join "`n - ")"
}

function Invoke-Promtool([string[]]$arguments) {
    & docker run --rm -v "${prometheus}:/work:ro" -w /work --entrypoint promtool $image @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "promtool $($arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

Invoke-Promtool @('check', 'config', 'prometheus.yml')
Invoke-Promtool (@('check', 'rules') + @($ruleFiles | ForEach-Object { "rules/$($_.Name)" }))
Invoke-Promtool (@('test', 'rules') + @($testFiles | ForEach-Object { "tests/$($_.Name)" }))

Write-Host "Prometheus rules passed ($($alerts.Count) alerts, $($testFiles.Count) test files, $($dashboardFiles.Count) dashboards)."
