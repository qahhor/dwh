# =====================================================================================================================
# SmartupCMS - a local run from the sources on Windows (plan 10/10, item 6.5); scripts/dev/run-local.sh on Linux/macOS.
# =====================================================================================================================
# The infrastructure runs in Docker Compose (PostgreSQL with both databases, the mail stub Mailpit, optionally
# Typesense); the server and the web application run on the host from the sources.
#
#   powershell -ExecutionPolicy Bypass -File scripts\dev\run-local.ps1 [up|infra|migrate|down|status] [options]
#
# Commands: up (default) - infrastructure, build, migrations of both databases, server, web dev server, then waits
# until Ctrl+C; infra; migrate; down (-Volumes also deletes the data and .local\); status.
# Options: -Detach (up returns once everything answers), -Demo (demo profile: users, projects, tasks, notes, orders),
# -Search (Typesense and the search index), -SkipBuild (reuse apps\server\target\server-*-exec.jar),
# -Volumes (with down).
# Ports and names: DB_PORT [5432], SERVER_PORT [8080], MANAGEMENT_PORT [9090], WEB_PORT [4200], WEB_HOST [localhost],
# MAILPIT_HTTP_PORT [8025], MAILPIT_SMTP_PORT [1025], TYPESENSE_PORT [8108], SMC_LOCAL_PROJECT [smartupcms-local].
# Prerequisites: JDK 25 (JAVA_HOME or java on PATH), Node.js of .node-version with npm, Docker Desktop with Compose v2.
# The first administrator's password is generated into .local\admin-password (ignored by git) and never printed.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'infra', 'migrate', 'down', 'status')]
    [string]$Command = 'up',
    [switch]$Detach,
    [switch]$Demo,
    [switch]$Search,
    [switch]$SkipBuild,
    [switch]$Volumes
)

$ErrorActionPreference = 'Stop'
$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$State = Join-Path $Root '.local'
$IsWin = [System.Environment]::OSVersion.Platform -eq 'Win32NT'

function Get-Setting([string]$Name, [string]$Default) {
    $value = [System.Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

$DbPort = Get-Setting 'DB_PORT' '5432'
$ServerPort = Get-Setting 'SERVER_PORT' '8080'
$ManagementPort = Get-Setting 'MANAGEMENT_PORT' '9090'
$WebPort = Get-Setting 'WEB_PORT' '4200'
$WebHost = Get-Setting 'WEB_HOST' 'localhost'
$MailpitHttpPort = Get-Setting 'MAILPIT_HTTP_PORT' '8025'
$MailpitSmtpPort = Get-Setting 'MAILPIT_SMTP_PORT' '1025'
$TypesensePort = Get-Setting 'TYPESENSE_PORT' '8108'
$Project = Get-Setting 'SMC_LOCAL_PROJECT' 'smartupcms-local'
# The development credentials of docker-compose.yml and .env.example.
$DbPassword = Get-Setting 'DB_PASSWORD' 'smartupcms_local_dev'
$env:DB_PORT = $DbPort; $env:MAILPIT_HTTP_PORT = $MailpitHttpPort; $env:MAILPIT_SMTP_PORT = $MailpitSmtpPort
$env:TYPESENSE_PORT = $TypesensePort; $env:PROJECT_NAME = $Project

# The JDK on Windows opens its selector over a Unix domain socket in TEMP, whose path must stay under 108 characters
# together with the socket's own name: a long TEMP fails the server with "Unable to establish loopback connection".
# The processes get the first short candidate: .local\tmp of the checkout, else %LOCALAPPDATA%\smc-tmp.
function Set-ShortTemp {
    if (-not $IsWin) { return }
    $limit = 72
    $candidates = @((Join-Path $State 'tmp'))
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'smc-tmp') }
    $short = $candidates | Where-Object { $_.Length -le $limit } | Select-Object -First 1
    if (-not $short) {
        Write-Warning "The checkout path is long; if the server fails with 'Unable to establish loopback connection', set TEMP to a short directory."
        return
    }
    New-Item -ItemType Directory -Force $short | Out-Null
    $env:TEMP = $short; $env:TMP = $short
}

function Write-Step([string]$Text) { Write-Host "`n==> $Text" -ForegroundColor Cyan }

# Windows PowerShell 5.1 turns a native command's stderr into an error record when the output is redirected, which
# 'Stop' would make fatal: native tools run under 'Continue' and are judged by their exit code.
function Invoke-Native([string]$What, [scriptblock]$Action) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Action } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE" }
}

function Invoke-Compose {
    $composeArgs = @('compose', '-p', $Project, '--project-directory', $Root,
        '-f', (Join-Path $Root 'docker-compose.yml'), '-f', (Join-Path $Root 'scripts\dev\local.compose.yml')) + $args
    Invoke-Native 'docker compose' { & docker @composeArgs }
}

function Assert-Tools {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is not installed (Docker Desktop).' }
    try { Invoke-Native 'docker compose version' { docker compose version *> $null } }
    catch { throw "Docker Compose v2 is missing (the 'docker compose' command)." }
    try { Invoke-Native 'docker info' { docker info *> $null } }
    catch { throw 'The Docker daemon does not answer: start Docker Desktop first.' }
    if ($Command -eq 'infra') { return }
    $javaName = if ($IsWin) { 'java.exe' } else { 'java' }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\$javaName"))) {
        $script:Java = Join-Path $env:JAVA_HOME "bin\$javaName"
    } else {
        $found = Get-Command java -ErrorAction SilentlyContinue
        if (-not $found) { throw 'Java is not found: install JDK 25 and put it on PATH or set JAVA_HOME.' }
        $script:Java = $found.Source
        $env:JAVA_HOME = Split-Path (Split-Path $script:Java -Parent) -Parent
    }
    Invoke-Native 'java -version' { $script:versionText = (& $script:Java -version 2>&1 | Out-String) }
    $versionText = $script:versionText
    $major = 0
    if ($versionText -match 'version "(\d+)') { $major = [int]$Matches[1] }
    if ($major -lt 25) { throw "JDK 25 or newer is required, found $major ($script:Java)." }
    if ($Command -eq 'up') {
        if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is not found: install the version in .node-version.' }
        if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw 'npm is not found (it comes with Node.js).' }
        $want = ((Get-Content (Join-Path $Root '.node-version') -Raw).Trim() -split '\.')[0]
        $have = (& node -p "process.versions.node.split('.')[0]").Trim()
        if ($have -ne $want) { Write-Warning "Node.js $have found, .node-version asks for $want." }
    }
}

function New-AdminPassword {
    New-Item -ItemType Directory -Force $State | Out-Null
    $file = Join-Path $State 'admin-password'
    if ((Test-Path $file) -and (Get-Item $file).Length -gt 0) { return }
    # 20 characters, the longest the password policy accepts; upper, lower, digit and sign are all present.
    $bytes = New-Object byte[] 8
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    [System.IO.File]::WriteAllText($file, "Dv1!$hex")
}

function Start-Infra {
    Write-Step "Infrastructure (Compose project $Project): PostgreSQL on $DbPort, Mailpit on $MailpitHttpPort"
    $services = @('postgres', 'mailpit')
    if ($Search) { $services += 'typesense' }
    Invoke-Compose up -d --wait --wait-timeout 300 @services
}

function Get-ServerJar {
    # The runnable jar has the exec classifier; the plain and testkit jars next to it are libraries.
    $jar = Get-ChildItem (Join-Path $Root 'apps\server\target') -Filter 'server-*-exec.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

function Get-Mvnw {
    if ($IsWin) { return (Join-Path $Root 'mvnw.cmd') }
    return (Join-Path $Root 'mvnw')
}

function Build-Server {
    if ($SkipBuild -and (Get-ServerJar)) { return }
    Write-Step 'Building the server (Maven wrapper, tests skipped)'
    $mvnw = Get-Mvnw
    Push-Location $Root
    try { Invoke-Native 'Maven build' { & $mvnw -B -q -DskipTests '-Djacoco.skip=true' -pl apps/server -am package } }
    finally { Pop-Location }
    if (-not (Get-ServerJar)) { throw 'The build produced no apps\server\target\server-*-exec.jar.' }
}

# The environment the server reads (ADR-0027 names), for the host processes.
function Set-ServerEnvironment {
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$DbPort/smartupcms"
    $env:DB_USER = 'smartupcms'; $env:DB_PASSWORD = $DbPassword
    $env:WAREHOUSE_URL = "jdbc:postgresql://127.0.0.1:$DbPort/smartupcms_dwh"
    $env:WAREHOUSE_USERNAME = 'smartupcms'; $env:WAREHOUSE_PASSWORD = $DbPassword; $env:WAREHOUSE_CONNECT_TIMEOUT = '5s'
    $env:SMC_STORAGE_LOCAL_PATH = Join-Path $State 'storage'
    $env:SMC_AUDIT_ARCHIVE_LOCAL_PATH = Join-Path $State 'audit-archive'
    $env:SMC_BACKUP_STATUS_FILE = Join-Path $State 'backup\status.json'
    $env:SMC_PUBLIC_URL = "http://localhost:$WebPort"
    $env:SMC_PROVIDER_MAIL = 'smtp'; $env:SMTP_HOST = '127.0.0.1'; $env:SMTP_PORT = $MailpitSmtpPort
    $env:SMTP_AUTH = 'false'; $env:SMTP_STARTTLS = 'false'; $env:SMC_MAIL_FROM = 'no-reply@localhost'
    if ($Search) {
        $env:SMC_TYPESENSE_ENABLED = 'true'; $env:SMC_TYPESENSE_URL = "http://127.0.0.1:$TypesensePort"
    } else {
        $env:SMC_TYPESENSE_ENABLED = 'false'
    }
}

function Invoke-Migrations {
    Write-Step 'Migrations: pg-dwh, then the main database'
    $jar = Get-ServerJar
    Set-ServerEnvironment
    # As the migrator role of the PostgreSQL image, the same steps as the migrate service of docker-compose.yml.
    $env:DB_USER = 'smartupcms_migrator'
    $env:SMC_MIGRATE_SCOPE = 'warehouse'
    try {
        Invoke-Native 'pg-dwh migrations' {
            & $script:Java -cp $jar '-Dloader.main=com.smartup24.cms.instance.warehouse.migration.MigrateMain' `
                org.springframework.boot.loader.launch.PropertiesLauncher
        }
        Remove-Item Env:SMC_MIGRATE_SCOPE
        $log = Join-Path $State 'migrate.log'
        $process = Start-Process -FilePath $script:Java -ArgumentList @('-jar', "`"$jar`"", '--spring.profiles.active=migrate') `
            -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow -PassThru -Wait
        if ($process.ExitCode -ne 0) {
            Get-Content $log -Tail 60 | Write-Host
            throw 'Migrations failed (full log: .local\migrate.log).'
        }
    } finally {
        Remove-Item Env:SMC_MIGRATE_SCOPE -ErrorAction SilentlyContinue
        $env:DB_USER = 'smartupcms'
    }
}

function Test-Running([string]$Name) {
    $pidFile = Join-Path $State "$Name.pid"
    if (-not (Test-Path $pidFile)) { return $false }
    return [bool](Get-Process -Id ([int](Get-Content $pidFile -Raw)) -ErrorAction SilentlyContinue)
}

function Wait-Http([string]$Name, [string]$Url, [string]$Process, [int]$Seconds, [string]$Log) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ($true) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
            if ($response.StatusCode -eq 200) { return }
        } catch {
            Write-Verbose "$Name is not ready yet: $($_.Exception.Message)"
        }
        if (-not (Test-Running $Process)) {
            Get-Content $Log -Tail 80 -ErrorAction SilentlyContinue | Write-Host
            throw "$Name stopped before it answered (log: $Log)."
        }
        if ((Get-Date) -gt $deadline) {
            Get-Content $Log -Tail 80 -ErrorAction SilentlyContinue | Write-Host
            throw "$Name did not answer at $Url within ${Seconds}s."
        }
        Start-Sleep -Seconds 2
    }
}

function Start-Background([string]$Name, [string]$File, [string[]]$Arguments, [string]$Directory) {
    $log = Join-Path $State "$Name.log"
    $options = @{
        FilePath = $File; ArgumentList = $Arguments; WorkingDirectory = $Directory; PassThru = $true
        RedirectStandardOutput = $log; RedirectStandardError = "$log.err"
    }
    if ($IsWin) { $options.WindowStyle = 'Hidden' }
    $process = Start-Process @options
    Set-Content -Path (Join-Path $State "$Name.pid") -Value $process.Id -Encoding ascii
}

function Start-Server {
    $profiles = if ($Demo) { 'dev,demo' } else { 'dev' }
    Write-Step "Server ($profiles) on $ServerPort, management on $ManagementPort"
    Set-ServerEnvironment
    $env:SMC_INSTANCE_ADMIN_PASSWORD = (Get-Content (Join-Path $State 'admin-password') -Raw).Trim()
    try {
        Start-Background 'server' $script:Java @('-jar', "`"$(Get-ServerJar)`"", "--spring.profiles.active=$profiles",
            "--server.port=$ServerPort", "--management.server.port=$ManagementPort") $Root
    } finally {
        Remove-Item Env:SMC_INSTANCE_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    }
    Wait-Http 'The server' "http://127.0.0.1:$ManagementPort/actuator/health/readiness" 'server' 300 (Join-Path $State 'server.log')
}

function Install-Web {
    if (Test-Path (Join-Path $Root 'apps\web\node_modules\@angular\cli')) { return }
    Write-Step 'Installing the web dependencies (npm ci)'
    Push-Location (Join-Path $Root 'apps\web')
    try { Invoke-Native 'npm ci' { npm ci --no-audit --no-fund --loglevel=error } }
    finally { Pop-Location }
}

function Start-Web {
    Write-Step "Web dev server on http://${WebHost}:$WebPort (API proxied to $ServerPort)"
    # The proxy of apps/web/proxy.conf.json with the port of this run.
    $proxy = Join-Path $State 'proxy.conf.json'
    $proxyJson = '{ "/api": { "target": "http://127.0.0.1:' + $ServerPort + '", "secure": false, "changeOrigin": true } }'
    [System.IO.File]::WriteAllText($proxy, $proxyJson)
    $node = (Get-Command node).Source
    Start-Background 'web' $node @('node_modules/@angular/cli/bin/ng.js', 'serve', '--host', $WebHost, '--port', $WebPort,
        '--proxy-config', "`"$proxy`"") (Join-Path $Root 'apps\web')
    Wait-Http 'The web dev server' "http://localhost:$WebPort/" 'web' 300 (Join-Path $State 'web.log')
    Wait-Http 'The API through the web origin' "http://localhost:$WebPort/api/v1/i18n/languages" 'server' 60 (Join-Path $State 'server.log')
}

function Stop-App([string]$Name) {
    $pidFile = Join-Path $State "$Name.pid"
    if (-not (Test-Path $pidFile)) { return }
    $id = [int](Get-Content $pidFile -Raw)
    if (Get-Process -Id $id -ErrorAction SilentlyContinue) {
        # The whole tree: mvnw, npm and node start processes of their own.
        if ($IsWin) { & taskkill /PID $id /T /F *> $null } else { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
        Write-Host "Stopped $Name (pid $id)."
    }
    Remove-Item $pidFile -Force
}

function Stop-Apps { Stop-App 'web'; Stop-App 'server' }

function Write-Summary {
    Write-Host ''
    Write-Host 'SmartupCMS is running.' -ForegroundColor Green
    Write-Host "  UI:          http://localhost:$WebPort"
    Write-Host '  Sign in:     login "admin", password in .local\admin-password (Get-Content .local\admin-password);'
    Write-Host '               the first sign-in asks for a new password.'
    Write-Host "  Mail stub:   http://localhost:$MailpitHttpPort (invitations and reset links of new users)"
    Write-Host "  API health:  http://127.0.0.1:$ManagementPort/actuator/health"
    Write-Host '  Logs:        .local\server.log, .local\web.log'
    Write-Host '  Stop:        scripts\dev\run-local.ps1 down   (add -Volumes to delete the data)'
    if ($Demo) { Write-Host '  Demo data:   users demo.anna, demo.bobur, demo.dilnoza get their invitation in the mail stub.' }
}

switch ($Command) {
    'status' {
        Invoke-Compose ps
        foreach ($name in @('server', 'web')) {
            if (Test-Running $name) { Write-Host "${name}: running" } else { Write-Host "${name}: stopped" }
        }
    }
    'down' {
        Stop-Apps
        if ($Volumes) {
            Invoke-Compose down --volumes --remove-orphans
            if (Test-Path $State) { Remove-Item -Recurse -Force $State }
        } else {
            Invoke-Compose down --remove-orphans
        }
    }
    'infra' { Assert-Tools; Start-Infra }
    'migrate' {
        Assert-Tools
        New-Item -ItemType Directory -Force $State | Out-Null
        Set-ShortTemp
        Start-Infra; Build-Server; Invoke-Migrations
    }
    'up' {
        Assert-Tools
        New-AdminPassword
        Set-ShortTemp
        Stop-Apps
        Start-Infra
        Build-Server
        Invoke-Migrations
        Start-Server
        Install-Web
        Start-Web
        Write-Summary
        if ($Detach) { return }
        Write-Host '  Ctrl+C stops the server and the web dev server.'
        try {
            while ((Test-Running 'server') -and (Test-Running 'web')) { Start-Sleep -Seconds 2 }
            throw 'The server or the web dev server stopped; see .local\server.log and .local\web.log.'
        } finally {
            Stop-Apps
            Write-Host 'The infrastructure keeps running: scripts\dev\run-local.ps1 down stops it.'
        }
    }
}
