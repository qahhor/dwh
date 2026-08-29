# ============================================================================
# DWH Control Plane - End-to-End Live API Verification Script
# ============================================================================
$ErrorActionPreference = "Stop"
$CpBaseUrl = "http://localhost:8082"
$CpMgmtUrl = "http://localhost:9191"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  DWH Control Plane and Fleet - Live Smoke Test Suite       " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Health Check
Write-Host "`n1. Control Plane Actuator Health Check..." -ForegroundColor Yellow
$health = Invoke-RestMethod -Uri "$CpMgmtUrl/actuator/health" -Method Get
Write-Host "   Status: $($health.status)" -ForegroundColor Green

# 2. Login as cpadmin
Write-Host "`n2. Authentication (POST /api/v1/auth/login)..." -ForegroundColor Yellow
$loginBody = @{
    login = "cpadmin"
    password = "CpDevOnly-ChangeMe-1"
} | ConvertTo-Json

$loginResponse = Invoke-WebRequest -Uri "$CpBaseUrl/api/v1/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -SessionVariable cpSession -UseBasicParsing
$userJson = $loginResponse.Content | ConvertFrom-Json
Write-Host "   Login Success! User: $($userJson.user.name) ($($userJson.user.role))" -ForegroundColor Green

# 3. GET /api/v1/auth/me
Write-Host "`n3. Verify Session (GET /api/v1/auth/me)..." -ForegroundColor Yellow
$meResponse = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/auth/me" -Method Get -WebSession $cpSession
Write-Host "   User Verified: $($meResponse.user.login), Role: $($meResponse.user.role)" -ForegroundColor Green

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

# 4. List Clients
Write-Host "`n4. List Clients (GET /api/v1/clients)..." -ForegroundColor Yellow
$clients = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/clients" -Method Get -WebSession $cpSession
Write-Host "   Existing Clients count: $($clients.Count)" -ForegroundColor Green

# 5. Create New Client
$randClient = Get-Random -Minimum 1000 -Maximum 9999
$clientCode = "client_$randClient"
Write-Host "`n5. Create New Client '$clientCode' (POST /api/v1/clients)..." -ForegroundColor Yellow
$clientBody = @{
    code = $clientCode
    name = "Enterprise Client $randClient Corp"
    resourceProfile = "M"
} | ConvertTo-Json

$newClient = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/clients" -Method Post -Body $clientBody -ContentType "application/json" -WebSession $cpSession -Headers (Get-CpCsrfHeaders)
Write-Host "   Client Created: ID=$($newClient.id), Code=$($newClient.code)" -ForegroundColor Green

# 6. Register New Instance for Client
Write-Host "`n6. Register Instance for '$clientCode' (POST /api/v1/instances)..." -ForegroundColor Yellow
$instanceBody = @{
    clientCode = $clientCode
    environment = "production"
    url = "https://$clientCode.dwh.internal"
} | ConvertTo-Json

$newInstance = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/instances" -Method Post -Body $instanceBody -ContentType "application/json" -WebSession $cpSession -Headers (Get-CpCsrfHeaders)
Write-Host "   Instance Registered: ID=$($newInstance.instanceId)" -ForegroundColor Green
Write-Host "   Generated Heartbeat Token: $($newInstance.heartbeatToken.Substring(0, 15))..." -ForegroundColor Green

# 7. Ingest Heartbeat from Instance
Write-Host "`n7. Ingest Heartbeat (POST /api/v1/instances/heartbeat with X-Instance-Token)..." -ForegroundColor Yellow
$heartbeatHeaders = @{
    "X-Instance-Token" = $newInstance.heartbeatToken
}
$heartbeatBody = @{
    appVersion = "1.0.0-PROD"
    schemaVersion = "009"
    metrics = @{
        usersCount = 150
        tasksCount = 3400
        storageBytes = 10737418240
        memoryUsedMb = 512
        cpuPercent = 14.5
    }
} | ConvertTo-Json

Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/instances/heartbeat" -Method Post -Body $heartbeatBody -ContentType "application/json" -Headers $heartbeatHeaders
Write-Host "   Heartbeat ingested successfully for instance $($newInstance.instanceId)" -ForegroundColor Green


# 8. List Fleet Status & Dashboard Metrics
Write-Host "`n8. Fleet Overview & Monitoring (GET /api/v1/fleet)..." -ForegroundColor Yellow
$fleet = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/fleet" -Method Get -WebSession $cpSession
Write-Host "   Fleet Summary: Total Instances=$($fleet.total), Problems=$($fleet.problems), Timeout=$($fleet.heartbeatTimeoutMinutes)m" -ForegroundColor Green
foreach ($item in $fleet.items) {
    Write-Host "     - [$($item.health)] Client='$($item.clientCode)' ($($item.clientName)), Env=$($item.environment), Version=$($item.appVersion), Heartbeat=$($item.lastHeartbeatAt)" -ForegroundColor Gray
}

# 9. Create and Publish Global Announcement
Write-Host "`n9. Global Announcements (POST /api/v1/announcements and /publish)..." -ForegroundColor Yellow
$announcementBody = @{
    bannerType = "warning"
    targetClientIds = @($newClient.id)
    contents = @(
        @{
            language = "ru"
            title = "Scheduled Platform Maintenance RU"
            body = "Maintenance window on Sunday 02:00 to 04:00 UTC."
        },
        @{
            language = "uz"
            title = "Scheduled Platform Maintenance UZ"
            body = "Profilaktika ishlari yakshanba kuni soat 02:00 dan 04:00 gacha."
        },
        @{
            language = "en"
            title = "Scheduled Platform Maintenance EN"
            body = "Maintenance window is scheduled for Sunday 02:00 - 04:00 UTC."
        }
    )
} | ConvertTo-Json -Depth 5

$announcement = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/announcements" -Method Post -Body $announcementBody -ContentType "application/json" -WebSession $cpSession -Headers (Get-CpCsrfHeaders)
Write-Host "   Announcement Created: ID=$($announcement.id), State=$($announcement.state)" -ForegroundColor Green

Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/announcements/$($announcement.id)/publish" -Method Post -WebSession $cpSession -Headers (Get-CpCsrfHeaders)
Write-Host "   Announcement $($announcement.id) published successfully" -ForegroundColor Green

$announcementsList = Invoke-RestMethod -Uri "$CpBaseUrl/api/v1/announcements" -Method Get -WebSession $cpSession
Write-Host "   Active Announcements count: $($announcementsList.Count)" -ForegroundColor Green

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  All Control Plane and Fleet Scenarios Passed Successfully! " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
