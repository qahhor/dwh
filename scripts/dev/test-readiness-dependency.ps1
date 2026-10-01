# ============================================================================
# SmartupCMS - readiness follows the main database (plan 10/10, item 0.7)
# ============================================================================
# On a running Compose stack: stops the postgres service, expects the server's readiness probe to answer 503 within
# the budget, starts postgres again and expects 200. CI runs it at the end of the e2e job (ci.yml), after the browser
# suite, because it takes the database away; scripts/dev/test-e2e.ps1 -CheckReadiness runs it locally. The stack is
# chosen the usual Compose way (COMPOSE_FILE, COMPOSE_PROJECT_NAME or -ProjectName).
[CmdletBinding()]
param(
    [string]$ProjectName = $env:COMPOSE_PROJECT_NAME,
    [ValidateRange(1, 300)]
    [int]$DownBudgetSeconds = 30,
    [ValidateRange(1, 600)]
    [int]$RecoveryBudgetSeconds = 180
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
# The management port is never published: the probe is read from inside the server container, as its healthcheck does.
$readinessUrl = 'http://127.0.0.1:9090/actuator/health/readiness'

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $prefix = @('compose')
    if (-not [string]::IsNullOrWhiteSpace($ProjectName)) { $prefix += @('-p', $ProjectName) }
    & docker @prefix @Arguments
}

function Get-ReadinessStatus {
    $output = Invoke-Compose exec -T server curl -s -o /dev/null -w '%{http_code}' --max-time 5 $readinessUrl
    $code = (($output | Out-String).Trim())
    if ($code -match '^\d{3}$') { return [int]$code }
    return 0
}

function Wait-ReadinessStatus {
    param([int]$Expected, [int]$BudgetSeconds)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $last = 0
    while ($watch.Elapsed.TotalSeconds -lt $BudgetSeconds) {
        $last = Get-ReadinessStatus
        if ($last -eq $Expected) { return $watch.Elapsed.TotalSeconds }
        Start-Sleep -Milliseconds 500
    }
    throw "Readiness did not answer $Expected within $BudgetSeconds s (last answer: $last)"
}

Push-Location $root
$postgresStopped = $false
try {
    Write-Host "Readiness before the outage" -ForegroundColor Yellow
    [void](Wait-ReadinessStatus -Expected 200 -BudgetSeconds 30)
    Write-Host "  [OK] 200" -ForegroundColor Green

    Write-Host "Stop the postgres service" -ForegroundColor Yellow
    $postgresStopped = $true
    Invoke-Compose stop postgres
    if ($LASTEXITCODE -ne 0) { throw "docker compose stop postgres failed with exit code $LASTEXITCODE" }

    $down = Wait-ReadinessStatus -Expected 503 -BudgetSeconds $DownBudgetSeconds
    Write-Host ("  [OK] 503 after {0:N1} s (budget {1} s)" -f $down, $DownBudgetSeconds) -ForegroundColor Green

    Write-Host "Start the postgres service" -ForegroundColor Yellow
    Invoke-Compose start postgres
    if ($LASTEXITCODE -ne 0) { throw "docker compose start postgres failed with exit code $LASTEXITCODE" }
    $postgresStopped = $false

    $up = Wait-ReadinessStatus -Expected 200 -BudgetSeconds $RecoveryBudgetSeconds
    Write-Host ("  [OK] 200 again after {0:N1} s" -f $up) -ForegroundColor Green
} finally {
    if ($postgresStopped) {
        # A failed check must not leave the stack without its database.
        Invoke-Compose start postgres
    }
    Pop-Location
}

Write-Host "`nReadiness follows the main database." -ForegroundColor Green
