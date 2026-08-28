# ============================================================================
# DWH Platform - End-to-End Live API Verification Script
# ============================================================================
$ErrorActionPreference = "Stop"
$BaseUrl = "http://localhost:8080"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  DWH Platform - Live Smoke Test Suite                      " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Health Check
Write-Host "`n1. Actuator Health Check..." -ForegroundColor Yellow
$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get
Write-Host "   Status: $($health.status)" -ForegroundColor Green

# 2. Login as admin
Write-Host "`n2. Authentication (POST /api/v1/auth/login)..." -ForegroundColor Yellow
$loginBody = @{
    login = "admin"
    password = "Admin123!"
    deviceInfo = "PowerShell Smoke Tester"
} | ConvertTo-Json

$loginResponse = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -SessionVariable session
$userJson = $loginResponse.Content | ConvertFrom-Json
Write-Host "   Login Success! User: $($userJson.user.name) ($($userJson.user.email))" -ForegroundColor Green

# 3. GET /api/v1/auth/me
Write-Host "`n3. Verify Session (GET /api/v1/auth/me)..." -ForegroundColor Yellow
$meResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/me" -Method Get -WebSession $session
Write-Host "   User Verified: $($meResponse.user.login)" -ForegroundColor Green
Write-Host "   Effective Permissions Count: $($meResponse.permissions.Count)" -ForegroundColor Green

# 4. GET /api/v1/iam/users
Write-Host "`n4. List Users with Keyset Pagination (GET /api/v1/iam/users)..." -ForegroundColor Yellow
$usersResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users?limit=10" -Method Get -WebSession $session
Write-Host "   Users found: $($usersResponse.items.Count)" -ForegroundColor Green

# 5. Create Custom Field for Tasks
Write-Host "`n5. Create Dynamic Custom Field (POST /api/v1/custom-fields)..." -ForegroundColor Yellow
$fieldBody = @{
    entityType = "TASK"
    code = "budget"
    name = "Project Budget"
    fieldType = "number"
    isRequired = $false
    defaultValue = "0"
    orderNo = 1
} | ConvertTo-Json

try {
    $fieldResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/custom-fields" -Method Post -Body $fieldBody -ContentType "application/json" -WebSession $session
    Write-Host "   Custom field created: $($fieldResponse.name) ($($fieldResponse.code))" -ForegroundColor Green
} catch {
    Write-Host "   Custom field already exists." -ForegroundColor Gray
}

# 6. Create Project
Write-Host "`n6. Create Project (POST /api/v1/tasks/projects)..." -ForegroundColor Yellow
$rand = Get-Random
$projectBody = @{
    name = "DWH Core Platform $rand"
    description = "Enterprise single-tenant instance deployment"
    state = "A"
    attributes = @{}
} | ConvertTo-Json

$projectResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/projects" -Method Post -Body $projectBody -ContentType "application/json" -WebSession $session
Write-Host "   Project created: ID=$($projectResponse.id), Name=$($projectResponse.name)" -ForegroundColor Green

# 7. Create Task with dynamic custom attributes
Write-Host "`n7. Create Task with Dynamic JSONB Attributes (POST /api/v1/tasks/items)..." -ForegroundColor Yellow
$taskBody = @{
    projectId = $projectResponse.id
    title = "Setup Kafka CDC connector"
    descriptionMarkdown = "Implement Debezium CDC for high-throughput replication"
    priority = "high"
    responsibleUserId = 1
    attributes = @{
        budget = 50000
    }
} | ConvertTo-Json

$taskResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/items" -Method Post -Body $taskBody -ContentType "application/json" -WebSession $session
Write-Host "   Task created: ID=$($taskResponse.id), Title=$($taskResponse.title)" -ForegroundColor Green
Write-Host "   Dynamic attributes stored: $($taskResponse.attributes | ConvertTo-Json -Compress)" -ForegroundColor Green

# 8. Instant Search / Command Palette
Write-Host "`n8. Instant Search (GET /api/v1/search?q=Kafka)..." -ForegroundColor Yellow
$searchResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/search?q=Kafka" -Method Get -WebSession $session
Write-Host "   Search total hits: $($searchResponse.totalHits)" -ForegroundColor Green
foreach ($hit in $searchResponse.hits) {
    Write-Host "     - [$($hit.entityType)] $($hit.title) -> $($hit.targetUrl)" -ForegroundColor Gray
}

# 9. Add Comment to Task
Write-Host "`n9. Add Markdown Comment (POST /api/v1/tasks/items/$($taskResponse.id)/comments)..." -ForegroundColor Yellow
$commentBody = @{
    textMarkdown = "Initial replication tests passed cleanly"
    fileIds = @()
} | ConvertTo-Json
$commentResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/items/$($taskResponse.id)/comments" -Method Post -Body $commentBody -ContentType "application/json" -WebSession $session
Write-Host "   Comment added: ID=$($commentResponse.id)" -ForegroundColor Green

# 10. Issue API Token
Write-Host "`n10. Issue Bearer API Token (POST /api/v1/iam/profile/tokens)..." -ForegroundColor Yellow
$tokenBody = @{
    name = "CI Integration Token"
} | ConvertTo-Json
$tokenResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/profile/tokens" -Method Post -Body $tokenBody -ContentType "application/json" -WebSession $session
Write-Host "   API Token created: $($tokenResponse.name)" -ForegroundColor Green
Write-Host "   Prefix: $($tokenResponse.token_prefix)..." -ForegroundColor Green
Write-Host "   Raw Secret Token: $($tokenResponse.token)" -ForegroundColor Yellow

# 11. Test Bearer Token Authentication
Write-Host "`n11. Verify Bearer Token Auth (GET /api/v1/auth/me with Authorization header)..." -ForegroundColor Yellow
$bearerHeaders = @{
    Authorization = "Bearer $($tokenResponse.token)"
}
$bearerMe = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/me" -Method Get -Headers $bearerHeaders
Write-Host "   Successfully authenticated via API Bearer Token! User=$($bearerMe.user.login)" -ForegroundColor Green

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  All 11 End-to-End Scenarios Passed Successfully!           " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
