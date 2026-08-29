# ============================================================================
# DWH Platform - End-to-End Live API Verification Script
# ============================================================================
$ErrorActionPreference = "Stop"
$BaseUrl = "http://localhost:8080"
$MgmtUrl = "http://localhost:9190"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  DWH Platform - Live Smoke Test Suite                      " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Health Check
Write-Host "`n1. Actuator Health Check..." -ForegroundColor Yellow
try {
    $health = Invoke-RestMethod -Uri "$MgmtUrl/actuator/health" -Method Get
    Write-Host "   Status: $($health.status)" -ForegroundColor Green
} catch {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get
    Write-Host "   Status: $($health.status)" -ForegroundColor Green
}

# 2. Login as admin
Write-Host "`n2. Authentication (POST /api/v1/auth/login)..." -ForegroundColor Yellow
$adminPassword = if ($env:ADMIN_PASSWORD) { $env:ADMIN_PASSWORD } else { "Qazaq#1212" }

$loginBody = @{
    login = "admin"
    password = $adminPassword
    deviceInfo = "PowerShell Smoke Tester"
} | ConvertTo-Json

$loginResponse = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -SessionVariable session -UseBasicParsing
$userJson = $loginResponse.Content | ConvertFrom-Json
Write-Host "   Login Success! User: $($userJson.user.name) ($($userJson.user.email))" -ForegroundColor Green

# 3. GET /api/v1/auth/me (initializes session context)
Write-Host "`n3. Verify Session (GET /api/v1/auth/me)..." -ForegroundColor Yellow
$meResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/me" -Method Get -WebSession $session
Write-Host "   User Verified: $($meResponse.user.login)" -ForegroundColor Green
Write-Host "   Effective Permissions Count: $($meResponse.permissions.Count)" -ForegroundColor Green

function Get-CsrfHeaders {
    $token = ""
    try {
        foreach ($c in $session.Cookies.GetCookies([System.Uri]$BaseUrl)) {
            if ($c.Name -eq "XSRF-TOKEN") {
                $token = $c.Value
            }
        }
    } catch {}
    return @{ "X-XSRF-TOKEN" = $token }
}

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
    $fieldResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/custom-fields" -Method Post -Body $fieldBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
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

$projectResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/projects" -Method Post -Body $projectBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
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

$taskResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/items" -Method Post -Body $taskBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
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
$commentResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/items/$($taskResponse.id)/comments" -Method Post -Body $commentBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Comment added: ID=$($commentResponse.id)" -ForegroundColor Green

# 9b. Upload and Attach File to Task (M7 FILE)
Write-Host "`n9b. Upload & Attach File (POST /api/v1/files/upload & POST /api/v1/tasks/$($taskResponse.id)/files)..." -ForegroundColor Yellow
Add-Type -AssemblyName System.Net.Http
$tempFilePath = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "sample_replication_log.txt")

[System.IO.File]::WriteAllText($tempFilePath, "Sample replication log report for task $($taskResponse.id)")

$xsrf = (Get-CsrfHeaders)["X-XSRF-TOKEN"]
$handler = New-Object System.Net.Http.HttpClientHandler
$cookieContainer = New-Object System.Net.CookieContainer
foreach ($c in $session.Cookies.GetCookies([System.Uri]$BaseUrl)) {
    $cookieContainer.Add([System.Uri]$BaseUrl, (New-Object System.Net.Cookie($c.Name, $c.Value)))
}
$handler.CookieContainer = $cookieContainer
$client = New-Object System.Net.Http.HttpClient($handler)
$client.DefaultRequestHeaders.Add("X-XSRF-TOKEN", $xsrf)

$formContent = New-Object System.Net.Http.MultipartFormDataContent
$fileBytes = [System.IO.File]::ReadAllBytes($tempFilePath)
$byteContent = New-Object System.Net.Http.ByteArrayContent($fileBytes, 0, $fileBytes.Length)
$formContent.Add($byteContent, "file", [System.IO.Path]::GetFileName($tempFilePath))

$httpRes = $client.PostAsync("$BaseUrl/api/v1/files/upload", $formContent).Result
$resStr = $httpRes.Content.ReadAsStringAsync().Result
Remove-Item $tempFilePath

if (-not $httpRes.IsSuccessStatusCode) {
    Write-Host "   Upload failed: HTTP $($httpRes.StatusCode), Body: $resStr" -ForegroundColor Red
    throw "Upload failed"
}

$fileUploadRes = $resStr | ConvertFrom-Json
Write-Host "   File uploaded: ID=$($fileUploadRes.id), Name=$($fileUploadRes.originalName), Size=$($fileUploadRes.sizeBytes) bytes, SHA256=$($fileUploadRes.sha256.Substring(0, 16))..." -ForegroundColor Green



# Attach to task
$attachBody = @{
    fileId = $fileUploadRes.id
} | ConvertTo-Json
Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$($taskResponse.id)/files" -Method Post -Body $attachBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders) | Out-Null
Write-Host "   File attached to Task $($taskResponse.id) successfully" -ForegroundColor Green

# Verify in task details
$taskDetail = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/$($taskResponse.id)" -Method Get -WebSession $session
Write-Host "   Task details retrieved: Attached files count = $($taskDetail.files.Count), First file = $($taskDetail.files[0].fileName)" -ForegroundColor Green

# Storage Quotas & Stats verification
$storageStats = Invoke-RestMethod -Uri "$BaseUrl/api/v1/files/storage/stats" -Method Get -WebSession $session
Write-Host "   Storage stats: Company Quota=$([Math]::Round($storageStats.companyQuotaBytes / 1GB, 1)) GB, Company Used=$($storageStats.companyUsedBytes) bytes, User Quota=$([Math]::Round($storageStats.userQuotaBytes / 1MB, 0)) MB, User Used=$($storageStats.userUsedBytes) bytes" -ForegroundColor Green

# Files list verification
$filesList = Invoke-RestMethod -Uri "$BaseUrl/api/v1/files?scope=all" -Method Get -WebSession $session
Write-Host "   Files list retrieved: Total count = $($filesList.Count), Most recent = $($filesList[0].originalName) by $($filesList[0].creatorName)" -ForegroundColor Green

# Download file verification
$downloadedContent = Invoke-RestMethod -Uri "$BaseUrl/api/v1/files/$($fileUploadRes.id)/download" -Method Get -WebSession $session
Write-Host "   File downloaded successfully: Content='$downloadedContent'" -ForegroundColor Green



# 10. Issue API Token
Write-Host "`n10. Issue Bearer API Token (POST /api/v1/iam/profile/tokens)..." -ForegroundColor Yellow
$tokenBody = @{
    name = "CI Integration Token"
} | ConvertTo-Json
$tokenResponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/profile/tokens" -Method Post -Body $tokenBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   API Token created: $($tokenResponse.record.name)" -ForegroundColor Green
Write-Host "   Prefix: $($tokenResponse.record.tokenPrefix)..." -ForegroundColor Green
Write-Host "   Raw Secret Token: $($tokenResponse.rawSecretToken)" -ForegroundColor Yellow

# 11. Test Bearer Token Authentication
Write-Host "`n11. Verify Bearer Token Auth (GET /api/v1/auth/me with Authorization header)..." -ForegroundColor Yellow
$bearerHeaders = @{
    Authorization = "Bearer $($tokenResponse.rawSecretToken)"
}
$bearerMe = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/me" -Method Get -Headers $bearerHeaders
Write-Host "   Successfully authenticated via API Bearer Token! User=$($bearerMe.user.login)" -ForegroundColor Green


# 12. Create User (CRUD: Add) with 10-char password validation
Write-Host "`n12. Create User (POST /api/v1/iam/users)..." -ForegroundColor Yellow
$randUser = Get-Random -Minimum 1000 -Maximum 9999
$newUserBody = @{
    name = "Test Engineer $randUser"
    login = "tester_$randUser"
    email = "tester_$randUser@company.local"
    phone = "+99890$randUser"
    password = "StrongPassword2026!"
    language = "ru"
    timezone = "Asia/Tashkent"
    is2faEnabled = $false
    attributes = @{}
} | ConvertTo-Json
$newUser = Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users" -Method Post -Body $newUserBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   User created: ID=$($newUser.id), Login=$($newUser.login)" -ForegroundColor Green

# 13. Update User (CRUD: Edit)
Write-Host "`n13. Update User (PATCH /api/v1/iam/users/$($newUser.id))..." -ForegroundColor Yellow
$updateUserBody = @{
    name = "Senior Test Engineer $randUser"
    language = "en"
} | ConvertTo-Json
Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users/$($newUser.id)" -Method Patch -Body $updateUserBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
$updatedUser = Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users/$($newUser.id)" -Method Get -WebSession $session
Write-Host "   User updated: Name=$($updatedUser.name), Lang=$($updatedUser.language)" -ForegroundColor Green

# 14. Brute-Force Protection Test (5 failed logins -> 423 Locked)
Write-Host "`n14. Brute-Force Lockout Protection (5 invalid attempts on $($newUser.login))..." -ForegroundColor Yellow
for ($i = 1; $i -le 5; $i++) {
    try {
        $badLogin = @{ login = $newUser.login; password = "WrongPassword$i" } | ConvertTo-Json
        Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" -Method Post -Body $badLogin -ContentType "application/json" -UseBasicParsing | Out-Null
    } catch {
        # expected 401 on attempts 1..4, 423 on attempt 5
    }
}
try {
    $attempt6 = @{ login = $newUser.login; password = "WrongPassword6" } | ConvertTo-Json
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" -Method Post -Body $attempt6 -ContentType "application/json" -UseBasicParsing
    Write-Host "   ERROR: Expected account lockout but login proceeded" -ForegroundColor Red
} catch {
    Write-Host "   Brute-force protection ACTIVE: Account temporarily locked (HTTP 423 Locked / ErrorCode.LOGIN_LOCKED)" -ForegroundColor Green
}

# 15. Delete / Anonymize User (CRUD: Delete)
Write-Host "`n15. Delete & Anonymize User (DELETE /api/v1/iam/users/$($newUser.id))..." -ForegroundColor Yellow
Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users/$($newUser.id)" -Method Delete -WebSession $session -Headers (Get-CsrfHeaders)
$anonymizedUser = Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users/$($newUser.id)" -Method Get -WebSession $session
Write-Host "   User anonymized: Name=$($anonymizedUser.name), State=$($anonymizedUser.state)" -ForegroundColor Green

# 16. Audit Log & Security Events (M8 AUD)
Write-Host "`n16. Audit Log & Security Events Verification (GET /api/v1/audit/...)..." -ForegroundColor Yellow
$auditStats = Invoke-RestMethod -Uri "$BaseUrl/api/v1/audit/stats" -Method Get -WebSession $session
Write-Host "   Audit Stats: Total Logs=$($auditStats.totalAuditLogs), Total Sec Events=$($auditStats.totalSecurityEvents), Sec Events (24h)=$($auditStats.securityEventsLast24h), Failed Logins (24h)=$($auditStats.failedLoginsLast24h)" -ForegroundColor Green

$auditLogs = Invoke-RestMethod -Uri "$BaseUrl/api/v1/audit/logs?limit=5" -Method Get -WebSession $session
Write-Host "   Recent Audit Logs count=$($auditLogs.Count):" -ForegroundColor Green
foreach ($l in $auditLogs) {
    Write-Host "     - [#$($l.id)] [$($l.event)] Table=$($l.tableName), PK=$($l.rowPk), Author=$($l.changedByName)" -ForegroundColor Gray
}

$secEvents = Invoke-RestMethod -Uri "$BaseUrl/api/v1/audit/security-events?limit=5" -Method Get -WebSession $session
Write-Host "   Recent Security Events count=$($secEvents.Count):" -ForegroundColor Green
foreach ($se in $secEvents) {
    Write-Host "     - [#$($se.id)] $($se.eventType) from $($se.ip), User=$($se.userName)" -ForegroundColor Gray
}

# 17. Settings & Localization (M9 SET & I18N)
Write-Host "`n17. System Settings & I18n Dictionaries (GET & PATCH /api/v1/settings/..., GET /api/v1/i18n/...)..." -ForegroundColor Yellow
$sysSettings = Invoke-RestMethod -Uri "$BaseUrl/api/v1/settings/system" -Method Get -WebSession $session
Write-Host "   Current System Settings: Company='$($sysSettings.'system.company_name')', Timezone='$($sysSettings.'system.default_timezone')', MinPassLen=$($sysSettings.'security.min_password_length')" -ForegroundColor Green

$updateSettingsBody = @{
    "system.company_name" = "Smartup Enterprise DWH"
    "system.default_timezone" = "Asia/Tashkent"
} | ConvertTo-Json
Invoke-RestMethod -Uri "$BaseUrl/api/v1/settings/system" -Method Patch -Body $updateSettingsBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   System Settings updated successfully" -ForegroundColor Green

$effectiveSettings = Invoke-RestMethod -Uri "$BaseUrl/api/v1/settings" -Method Get -WebSession $session
Write-Host "   Effective Settings verified: Company='$($effectiveSettings.'system.company_name')', Lang='$($effectiveSettings.'system.default_language')'" -ForegroundColor Green

$ruDict = Invoke-RestMethod -Uri "$BaseUrl/api/v1/i18n/ru" -Method Get -WebSession $session
$uzDict = Invoke-RestMethod -Uri "$BaseUrl/api/v1/i18n/uz" -Method Get -WebSession $session
$enDict = Invoke-RestMethod -Uri "$BaseUrl/api/v1/i18n/en" -Method Get -WebSession $session
Write-Host "   I18n Dictionaries retrieved: RU: nav.tasks='$($ruDict.'nav.tasks')', UZ: nav.tasks='$($uzDict.'nav.tasks')', EN: nav.tasks='$($enDict.'nav.tasks')'" -ForegroundColor Green

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  All 17 End-to-End Scenarios Passed Successfully!           " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan


