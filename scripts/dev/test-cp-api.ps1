# ============================================================================
# DWH Control Plane - End-to-End Live API Verification Script
# ============================================================================
$ErrorActionPreference = "Stop"
$CpBaseUrl = "http://localhost:8082"
$CpMgmtUrl = "http://localhost:9191"
. (Join-Path $PSScriptRoot "dotenv.ps1")

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  DWH Control Plane and Fleet - Live Smoke Test Suite       " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Health Check
Write-Host "`n1. Control Plane Actuator Health Check..." -ForegroundColor Yellow
$health = Invoke-RestMethod -Uri "$CpMgmtUrl/actuator/health" -Method Get
Write-Host "   Status: $($health.status)" -ForegroundColor Green

# 2. Login as control-plane administrator
Write-Host "`n2. Authentication (POST /api/v1/auth/login)..." -ForegroundColor Yellow
$cpAdminLogin = $env:CP_ADMIN_LOGIN
$cpAdminPassword = $env:CP_ADMIN_PASSWORD
if (-not $cpAdminLogin -or -not $cpAdminPassword) {
    $envFile = Join-Path $PSScriptRoot "..\..\.env"
    if (-not $cpAdminLogin) { $cpAdminLogin = Get-DotEnvValue -Path $envFile -Key "CP_ADMIN_LOGIN" }
    if (-not $cpAdminPassword) { $cpAdminPassword = Get-DotEnvValue -Path $envFile -Key "CP_ADMIN_PASSWORD" }
}
if (-not $cpAdminLogin -or -not $cpAdminPassword) {
    Write-Host "Учётные данные Control Plane не найдены: задайте CP_ADMIN_LOGIN и CP_ADMIN_PASSWORD" -ForegroundColor Red
    exit 1
}

$loginBody = @{
    login = $cpAdminLogin
    password = $cpAdminPassword
} | ConvertTo-Json

$loginResponse = Invoke-WebRequest -Uri "$CpBaseUrl/api/v1/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -SessionVariable cpSession -UseBasicParsing
$userJson = $loginResponse.Content | ConvertFrom-Json
if ($userJson.login -ne $cpAdminLogin -or -not $userJson.name -or -not $userJson.roles) {
    throw "Control Plane login response does not match the expected CpUser contract"
}
Write-Host "   Login Success! User: $($userJson.name), Roles: $($userJson.roles -join ', ')" -ForegroundColor Green

# 3. GET /api/v1/auth/me
Write-Host "`n3. Verify Session (GET /api/v1/auth/me)..." -ForegroundColor Yellow
$meResponse = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/auth/me" -Method Get -WebSession $cpSession
if ($meResponse.login -ne $cpAdminLogin -or -not $meResponse.roles) {
    throw "Control Plane session response does not match the expected CpUser contract"
}
Write-Host "   User Verified: $($meResponse.login), Roles: $($meResponse.roles -join ', ')" -ForegroundColor Green

function Get-CpCsrfHeaders {
    $token = ""
    try {
        foreach ($c in $cpSession.Cookies.GetCookies([System.Uri]$CpBaseUrl)) {
            if ($c.Name -eq "XSRF-TOKEN") {
                $token = $c.Value
            }
        }
    } catch {}
    return @{ "X-XSRF-TOKEN" = $token }
}

function Invoke-ExpectStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][int]$ExpectedStatus,
        [string]$Body,
        [hashtable]$Headers,
        $WebSession
    )

    $request = @{
        Uri = $Uri
        Method = $Method
        UseBasicParsing = $true
        ErrorAction = "Stop"
    }
    if ($Body) {
        $request.Body = $Body
        $request.ContentType = "application/json"
    }
    if ($Headers) { $request.Headers = $Headers }
    if ($WebSession) { $request.WebSession = $WebSession }

    $actualStatus = 0
    try {
        $response = Invoke-WebRequest @request
        $actualStatus = [int]$response.StatusCode
    } catch {
        if (-not $_.Exception.Response) { throw }
        $actualStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($actualStatus -ne $ExpectedStatus) {
        throw "Expected HTTP $ExpectedStatus from $Method $Uri, received $actualStatus"
    }
}

function New-SmokeClient {
    param([Parameter(Mandatory = $true)][string]$Code)
    $body = @{
        code = $Code
        name = "Fleet smoke $Code"
        resourceProfile = "S"
    } | ConvertTo-Json
    return Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/clients" -Method Post `
        -Body $body -ContentType "application/json" -WebSession $cpSession `
        -Headers (Get-CpCsrfHeaders)
}

function Register-ManagedInstance {
    param([Parameter(Mandatory = $true)][string]$ClientCode)
    $hostLabel = $ClientCode.Replace('_', '-')
    $body = @{
        clientCode = $ClientCode
        environment = "production"
        url = "https://$hostLabel.invalid"
        deploymentMode = "MANAGED_CLOUD"
        jurisdiction = "EU"
        cloudProvider = "HETZNER"
        storageProvider = "CLOUDFLARE_R2"
        edgeProvider = "CLOUDFLARE"
        supportTier = "MANAGED_995"
    } | ConvertTo-Json
    return Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/instances" -Method Post `
        -Body $body -ContentType "application/json" -WebSession $cpSession `
        -Headers (Get-CpCsrfHeaders)
}

$runSuffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$clientCodeA = "smoke_a_$runSuffix"
$clientCodeB = "smoke_b_$runSuffix"

# 4. Create client A and register instance A
Write-Host "`n4. Create client A and register a managed instance..." -ForegroundColor Yellow
$clientA = New-SmokeClient -Code $clientCodeA
$enrollmentA = Register-ManagedInstance -ClientCode $clientCodeA
if (-not $clientA.id -or -not $enrollmentA.instanceId -or
    -not $enrollmentA.enrollmentToken -or -not $enrollmentA.expiresAt) {
    throw "Client A registration did not return the expected enrollment contract"
}
Write-Host "   Client A and instance A registered; enrollment secret retained only in memory" -ForegroundColor Green

# 5. Exchange one-time enrollment token
Write-Host "`n5. Exchange the one-time enrollment token..." -ForegroundColor Yellow
$enrollBodyA = @{ enrollmentToken = $enrollmentA.enrollmentToken } | ConvertTo-Json
$runtimeA = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/instances/enroll" -Method Post `
    -Body $enrollBodyA -ContentType "application/json"
if ($runtimeA.instanceId -ne $enrollmentA.instanceId -or -not $runtimeA.credential) {
    throw "Enrollment exchange did not return the expected runtime credential contract"
}
$runtimeHeadersA = @{ "X-Instance-Token" = $runtimeA.credential }
Write-Host "   Enrollment exchanged; runtime credential retained only in memory" -ForegroundColor Green

# 6. Prove enrollment replay fails closed
Write-Host "`n6. Reject replay of the consumed enrollment token..." -ForegroundColor Yellow
Invoke-ExpectStatus -Uri "$CpBaseUrl/api/v1/instances/enroll" -Method Post `
    -ExpectedStatus 401 -Body $enrollBodyA
Write-Host "   Replayed enrollment token rejected with HTTP 401" -ForegroundColor Green

# 7. Send typed heartbeat
Write-Host "`n7. Send typed heartbeat with the runtime credential..." -ForegroundColor Yellow
$heartbeatBody = @{
    appVersion = "1.0.0-smoke"
    schemaVersion = "006"
    releaseVersion = "1.0.0-smoke"
    configVersion = "smoke-1"
    components = @{
        app = "UP"
        database = "UP"
        typesense = "UP"
        objectStorage = "UP"
    }
    storage = @{
        usedBytes = 1073741824
        quotaBytes = 10737418240
    }
    backup = @{
        lastCompletedAt = [DateTimeOffset]::UtcNow.AddMinutes(-5).ToString("o")
        status = "UPLOADED"
    }
    agents = @{
        tunnel = "UP"
        telemetry = "UP"
    }
    deploymentState = "IDLE"
    capacity = @{
        activeUsers = 3
        outboxPending = 0
        outboxDeadLetter = 0
    }
} | ConvertTo-Json -Depth 6
$heartbeat = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/instances/heartbeat" -Method Post `
    -Body $heartbeatBody -ContentType "application/json" -Headers $runtimeHeadersA
if (-not $heartbeat.accepted -or $heartbeat.instanceId -ne $enrollmentA.instanceId) {
    throw "Typed heartbeat response did not match instance A"
}
Write-Host "   Typed heartbeat accepted for instance A" -ForegroundColor Green

# 8. Create client B and register instance B for the cross-client negative path
Write-Host "`n8. Create isolated client B and instance B..." -ForegroundColor Yellow
$clientB = New-SmokeClient -Code $clientCodeB
$enrollmentB = Register-ManagedInstance -ClientCode $clientCodeB
if (-not $clientB.id -or -not $enrollmentB.instanceId -or
    -not $enrollmentB.enrollmentToken -or -not $enrollmentB.expiresAt) {
    throw "Client B registration did not return the expected enrollment contract"
}
Write-Host "   Client B and instance B registered independently" -ForegroundColor Green

# 9. Record a valid backup report as instance A and verify the operator projection
Write-Host "`n9. Record and verify an instance-bound backup report..." -ForegroundColor Yellow
$backupIdA = [Guid]::NewGuid()
$backupBodyA = @{
    backupId = $backupIdA
    status = "UPLOADED"
    checksumSha256 = ('a' * 64 -join '')
    durationSec = 19
    completedAt = [DateTimeOffset]::UtcNow.AddMinutes(-1).ToString("o")
    reasonCode = $null
} | ConvertTo-Json
Invoke-ExpectStatus -Uri "$CpBaseUrl/api/v1/instances/backup-reports" -Method Post `
    -ExpectedStatus 202 -Body $backupBodyA -Headers $runtimeHeadersA
$reports = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/backup-reports?limit=100" `
    -Method Get -WebSession $cpSession
$storedReport = $reports | Where-Object {
    $_.backupId -eq $backupIdA.ToString()
} | Select-Object -First 1
if (-not $storedReport -or $storedReport.instanceId -ne $enrollmentA.instanceId -or
    $storedReport.clientCode -ne $clientCodeA -or $storedReport.artifactStatus -ne "UPLOADED") {
    throw "Backup report was not projected under instance A ownership"
}
Write-Host "   Backup report persisted and projected under client A" -ForegroundColor Green

# 10. Prove caller identity wins over a cross-client body
Write-Host "`n10. Reject a cross-client backup body submitted with credential A..." -ForegroundColor Yellow
$crossClientBody = @{
    backupId = [Guid]::NewGuid()
    status = "UPLOADED"
    checksumSha256 = ('b' * 64 -join '')
    durationSec = 20
    completedAt = [DateTimeOffset]::UtcNow.AddMinutes(-1).ToString("o")
    reasonCode = $null
    clientCode = $clientCodeB
} | ConvertTo-Json
Invoke-ExpectStatus -Uri "$CpBaseUrl/api/v1/instances/backup-reports" -Method Post `
    -ExpectedStatus 400 -Body $crossClientBody -Headers $runtimeHeadersA
Write-Host "   Cross-client body rejected with HTTP 400" -ForegroundColor Green

# 11. No target means an explicit 204, not an invented desired state
Write-Host "`n11. Verify desired state before assignment..." -ForegroundColor Yellow
Invoke-ExpectStatus -Uri "$CpBaseUrl/api/v1/instances/desired-state" -Method Get `
    -ExpectedStatus 204 -Headers $runtimeHeadersA
Write-Host "   Desired-state endpoint returned HTTP 204 before assignment" -ForegroundColor Green

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  Secure Control Plane instance contract passed successfully  " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
