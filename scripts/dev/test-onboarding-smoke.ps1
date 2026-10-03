# =====================================================================================================================
# SmartupCMS - onboarding smoke (plan 10/10, item 6.5): from git clone to a working UI in at most 10 minutes and three
# commands.
# =====================================================================================================================
# The nightly workflow runs it on ubuntu (job onboarding); locally it runs the same way on Windows. It clones the
# repository into a fresh directory and starts it the way README's quick start says:
#   1. git clone <repository> smartupcms
#   2. cd smartupcms
#   3. scripts/dev/run-local.sh up --detach --demo     (Windows: scripts\dev\run-local.ps1 up -Detach -Demo)
# then checks the UI, the API through the UI origin, the server readiness, a sign-in of the first administrator and the
# demo data, and fails when the whole run took longer than the budget. Prerequisites are those of a developer's machine
# (JDK 25, Node.js, Docker); nothing is cached for the clone. The stack runs on ports of its own, so it does not meet a
# stand already running; it is torn down at the end, data included. Only committed work is cloned.
[CmdletBinding()]
param(
    # The repository to clone; the checkout this script belongs to by default.
    [string]$Source,
    [int]$BudgetSeconds = 600,
    # Leaves the clone and its stack running for a look after the run.
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 has no $PSScriptRoot in parameter defaults.
if ([string]::IsNullOrWhiteSpace($Source)) { $Source = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')) }
$IsWin = [System.Environment]::OSVersion.Platform -eq 'Win32NT'

# Ports of its own unless the caller set them.
$defaults = [ordered]@{
    SMC_LOCAL_PROJECT = 'smc-onboarding-smoke'; DB_PORT = '55432'; SERVER_PORT = '18080'; MANAGEMENT_PORT = '19090'
    WEB_PORT = '14200'; MAILPIT_HTTP_PORT = '18025'; MAILPIT_SMTP_PORT = '11025'; TYPESENSE_PORT = '18108'
}
foreach ($name in $defaults.Keys) {
    if ([string]::IsNullOrWhiteSpace([System.Environment]::GetEnvironmentVariable($name))) {
        [System.Environment]::SetEnvironmentVariable($name, $defaults[$name])
    }
}
$webUrl = "http://localhost:$($env:WEB_PORT)"
$managementUrl = "http://127.0.0.1:$($env:MANAGEMENT_PORT)"

$baseTemp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
$work = Join-Path $baseTemp ("smc-onboarding-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$clone = Join-Path $work 'smartupcms'
New-Item -ItemType Directory -Force $work | Out-Null

function Invoke-Native([string]$What, [scriptblock]$Action) {
    # Native stderr must not become a fatal error record under Windows PowerShell 5.1: the exit code decides.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Action } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE" }
}

function Invoke-RunLocal([string[]]$Arguments) {
    if ($IsWin) {
        $shell = (Get-Process -Id $PID).Path
        Invoke-Native "run-local.ps1 $Arguments" { & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $clone 'scripts\dev\run-local.ps1') @Arguments }
    } else {
        Invoke-Native "run-local.sh $Arguments" { & bash (Join-Path $clone 'scripts/dev/run-local.sh') @Arguments }
    }
}

function Assert-Status([string]$Name, [string]$Url, [string]$Method = 'GET', [string]$Body, [string]$Expect) {
    $request = @{ UseBasicParsing = $true; Uri = $Url; Method = $Method; TimeoutSec = 30 }
    if ($Body) { $request.Body = $Body; $request.ContentType = 'application/json' }
    $response = Invoke-WebRequest @request
    if ($response.StatusCode -ne 200) { throw "$Name answered $($response.StatusCode) at $Url" }
    if ($Expect -and -not ([string]$response.Content).Contains($Expect)) { throw "$Name at $Url does not contain '$Expect'" }
    Write-Host "  [OK] $Name" -ForegroundColor Green
}

$clock = [System.Diagnostics.Stopwatch]::StartNew()
$passed = $false
try {
    Write-Host "1. git clone $Source" -ForegroundColor Yellow
    Invoke-Native 'git clone' { git clone --quiet $Source $clone }
    Write-Host '2. cd smartupcms' -ForegroundColor Yellow
    Push-Location $clone
    try {
        if ($IsWin) {
            Write-Host '3. scripts\dev\run-local.ps1 up -Detach -Demo' -ForegroundColor Yellow
            Invoke-RunLocal @('up', '-Detach', '-Demo')
        } else {
            Write-Host '3. scripts/dev/run-local.sh up --detach --demo' -ForegroundColor Yellow
            Invoke-RunLocal @('up', '--detach', '--demo')
        }
    } finally {
        Pop-Location
    }
    $ready = $clock.Elapsed.TotalSeconds

    Write-Host "`nChecks" -ForegroundColor Yellow
    Assert-Status 'login page (the web application shell)' "$webUrl/" -Expect '<app-root'
    Assert-Status 'API through the web origin' "$webUrl/api/v1/i18n/languages"
    Assert-Status 'server readiness' "$managementUrl/actuator/health/readiness" -Expect 'UP'
    # The generated password is read from the clone's ignored file and never printed.
    $password = (Get-Content (Join-Path $clone '.local/admin-password') -Raw).Trim()
    $login = @{ login = 'admin'; password = $password; deviceInfo = 'onboarding smoke' } | ConvertTo-Json
    Assert-Status 'sign-in of the first administrator' "$webUrl/api/v1/auth/login" -Method 'POST' -Body $login
    $demo = Select-String -Path (Join-Path $clone '.local/server.log') -Pattern 'Demo data: \d+ records created' | Select-Object -First 1
    if (-not $demo) { throw 'The demo profile did not report its records in .local/server.log' }
    Write-Host "  [OK] $($demo.Matches[0].Value)" -ForegroundColor Green

    $total = $clock.Elapsed.TotalSeconds
    Write-Host ("`nFrom git clone to a working UI: {0:N0} s (budget {1} s, 3 commands)" -f $ready, $BudgetSeconds) -ForegroundColor Cyan
    if ($total -gt $BudgetSeconds) { throw ("The onboarding took {0:N0} s, over the budget of {1} s." -f $total, $BudgetSeconds) }
    $passed = $true
} finally {
    if (-not $passed) {
        foreach ($log in @('server.log', 'web.log', 'migrate.log', 'npm-ci.log')) {
            $path = Join-Path $clone ".local/$log"
            if (Test-Path $path) {
                Write-Host "`n--- .local/$log (last 60 lines) ---"
                Get-Content $path -Tail 60 | Write-Host
            }
        }
    }
    if (-not $Keep) {
        if (Test-Path (Join-Path $clone 'scripts')) {
            try {
                if ($IsWin) { Invoke-RunLocal @('down', '-Volumes') } else { Invoke-RunLocal @('down', '--volumes') }
            } catch {
                Write-Warning "Teardown failed: $($_.Exception.Message)"
            }
        }
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    }
}
Write-Host 'Onboarding smoke passed.' -ForegroundColor Green
