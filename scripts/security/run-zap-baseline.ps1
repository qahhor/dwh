[CmdletBinding()]
param(
    # The web origin of a running stack; nginx there proxies /api to the server.
    [string]$WebUrl = 'http://localhost:4200',
    [string]$OutputDir = 'zap-report',
    # Plan 10/10, item 7.7: the approved image, pinned by digest (zaproxy/zap-stable, 2026-08-07).
    [string]$Image = 'zaproxy/zap-stable@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef',
    [int]$SpiderMinutes = 2
)

# Plan 10/10, item 7.7: ZAP baseline (passive) against a running stack. Two scans share the rules file
# scripts/security/zap-baseline.conf: the web origin with the spider, and every operation of docs/api/openapi.json
# through the same origin in safe mode (passive only, no attack). Unauthenticated: the API answers 401/403, whose
# headers are scanned too. The step fails on any High alert not ignored by the rules file, on a FAIL rule, or when a
# scan does not finish; the HTML, Markdown and JSON reports stay in $OutputDir for the CI artifact.

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$rulesFile = Join-Path $PSScriptRoot 'zap-baseline.conf'
$openApi = Join-Path $repoRoot 'docs/api/openapi.json'

if (-not [System.IO.Path]::IsPathRooted($OutputDir)) { $OutputDir = Join-Path $repoRoot $OutputDir }
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$OutputDir = (Resolve-Path $OutputDir).Path
Copy-Item -LiteralPath $rulesFile -Destination (Join-Path $OutputDir 'zap-baseline.conf') -Force
Copy-Item -LiteralPath $openApi -Destination (Join-Path $OutputDir 'openapi.json') -Force

# On Linux the container shares the host network and reaches the published port as is; Docker Desktop (Windows,
# macOS) reaches the host through host.docker.internal. The ZAP user (uid 1000) writes its reports to the mount.
$onLinux = (Get-Variable -Name IsLinux -ErrorAction SilentlyContinue) -and $IsLinux
if ($onLinux) {
    $network = @('--network', 'host')
    $target = $WebUrl
    & chmod 0777 $OutputDir
}
else {
    $network = @()
    $target = $WebUrl -replace '://(localhost|127\.0\.0\.1)', '://host.docker.internal'
}
$target = $target.TrimEnd('/')

function Invoke-Zap([string[]]$Arguments, [string]$Name) {
    Write-Host "ZAP $Name scan of $target" -ForegroundColor Cyan
    $dockerArgs = @('run', '--rm') + $network + @('-v', "${OutputDir}:/zap/wrk:rw", $Image) + $Arguments
    & docker @dockerArgs | Out-Host
    $code = $LASTEXITCODE
    # 0 clean, 1 a FAIL rule matched, 2 warnings only (-I makes them 0), 3 the scan itself failed.
    if ($code -eq 3) { throw "ZAP $Name scan did not complete (exit 3)." }
    if (-not (Test-Path -LiteralPath (Join-Path $OutputDir "$Name.json"))) {
        throw "ZAP $Name scan wrote no JSON report (exit $code)."
    }
    return $code
}

$codes = @{}
$codes['web'] = Invoke-Zap @('zap-baseline.py', '-t', $target, '-c', 'zap-baseline.conf', '-m', "$SpiderMinutes",
    '-I', '-J', 'web.json', '-r', 'web.html', '-w', 'web.md') 'web'
$codes['api'] = Invoke-Zap @('zap-api-scan.py', '-t', 'openapi.json', '-f', 'openapi', '-S', '-O', $target,
    '-c', 'zap-baseline.conf', '-I', '-J', 'api.json', '-r', 'api.html', '-w', 'api.md') 'api'

# Rules the file ignores, with the reason written next to each: ZAP still lists their alerts in the report.
$ignored = @{}
foreach ($line in Get-Content -LiteralPath $rulesFile) {
    if ($line -match '^\s*(\d+)\s+IGNORE\b') { $ignored[$Matches[1]] = $true }
}

$risks = @{ '3' = 'High'; '2' = 'Medium'; '1' = 'Low'; '0' = 'Informational' }
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($name in @('web', 'api')) {
    $report = Get-Content -LiteralPath (Join-Path $OutputDir "$name.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($site in @($report.site)) {
        foreach ($alert in @($site.alerts)) {
            if ($null -eq $alert) { continue }
            $rows.Add([pscustomobject]@{
                Scan = $name
                Risk = $risks["$($alert.riskcode)"]
                RiskCode = [int]$alert.riskcode
                Plugin = "$($alert.pluginid)"
                Alert = "$($alert.name)"
                Count = [int]$alert.count
                Ignored = $ignored.ContainsKey("$($alert.pluginid)")
            })
        }
    }
}

$summary = [System.Collections.Generic.List[string]]::new()
$summary.Add('## ZAP baseline')
$summary.Add('')
$summary.Add("Target: $WebUrl; image: $Image")
$summary.Add('')
$summary.Add('| Risk | Scan | Plugin | Alert | Instances | Ignored |')
$summary.Add('|---|---|---|---|---|---|')
foreach ($row in ($rows | Sort-Object -Property @{ Expression = 'RiskCode'; Descending = $true }, Scan, Plugin)) {
    $summary.Add("| $($row.Risk) | $($row.Scan) | $($row.Plugin) | $($row.Alert) | $($row.Count) | $(if ($row.Ignored) { 'yes' } else { 'no' }) |")
}
$summary.Add('')
foreach ($level in @(3, 2, 1, 0)) {
    $active = @($rows | Where-Object { $_.RiskCode -eq $level -and -not $_.Ignored }).Count
    $summary.Add("- $($risks["$level"]): $active alert type(s) not ignored")
}
$summaryText = $summary -join [Environment]::NewLine
Set-Content -LiteralPath (Join-Path $OutputDir 'summary.md') -Value $summaryText -Encoding UTF8
Write-Host $summaryText
if ($env:GITHUB_STEP_SUMMARY) { Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $summaryText -Encoding UTF8 }

$high = @($rows | Where-Object { $_.RiskCode -eq 3 -and -not $_.Ignored })
if ($high.Count -gt 0) {
    throw "ZAP found $($high.Count) High alert type(s): $(($high | ForEach-Object { "$($_.Plugin) $($_.Alert)" }) -join '; ')"
}
foreach ($name in $codes.Keys) {
    if ($codes[$name] -eq 1) { throw "ZAP $name scan matched a FAIL rule of zap-baseline.conf; see $name.md." }
}
Write-Host 'ZAP baseline: no High alert.' -ForegroundColor Green
