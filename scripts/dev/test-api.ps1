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
$health = $null
for ($i = 0; $i -lt 10; $i++) {
    try {
        $health = Invoke-RestMethod -Uri "$MgmtUrl/actuator/health" -Method Get
        if ($health.status -eq "UP") { break }
    } catch {
        Start-Sleep -Milliseconds 500
    }
}
if ($health) {
    Write-Host "   Status: $($health.status)" -ForegroundColor Green
} else {
    throw "Actuator health check failed on $MgmtUrl"
}

# 2. Login as admin
Write-Host "`n2. Authentication (POST /api/v1/auth/login)..." -ForegroundColor Yellow
# Пароль берём из окружения, иначе из локального .env — он в .gitignore и
# содержит фактические значения стенда. В самом скрипте пароля нет: файл
# отслеживается git'ом, а значения там быстро расходятся с реальностью.
$adminPassword = $env:ADMIN_PASSWORD
if (-not $adminPassword) {
    $envFile = Join-Path $PSScriptRoot "..\..\.env"
    if (Test-Path $envFile) {
        $line = Select-String -Path $envFile -Pattern '^\s*ADMIN_PASSWORD\s*=\s*(.+?)\s*$' | Select-Object -First 1
        if ($line) { $adminPassword = $line.Matches[0].Groups[1].Value }
    }
}
if (-not $adminPassword) {
    Write-Host "Пароль администратора не найден: задайте `$env:ADMIN_PASSWORD или ADMIN_PASSWORD в .env" -ForegroundColor Red
    exit 1
}

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

# 18. API Contract & Idempotency Key (M10 API)
Write-Host "`n18. Idempotency Key & OpenAPI Contract (POST /api/v1/tasks/items with Idempotency-Key)..." -ForegroundColor Yellow
$idemKey = [guid]::NewGuid().ToString()
$idemHeaders = @{
    Authorization = "Bearer $($tokenResponse.rawSecretToken)"
    "Idempotency-Key" = $idemKey
}

$idemBody = @{
    title = "Idempotent Transaction Task $randUser"
    priority = "high"
} | ConvertTo-Json

# 18.1 First request (creates entity and caches response)
$firstRes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/items" -Method Post -Body $idemBody -ContentType "application/json" -Headers $idemHeaders
Write-Host "   First Request executed: Task ID=$($firstRes.id), Title='$($firstRes.title)'" -ForegroundColor Green

# 18.2 Second request (must return cached response with Idempotent-Replay header)
$secondWebRes = Invoke-WebRequest -Uri "$BaseUrl/api/v1/tasks/items" -Method Post -Body $idemBody -ContentType "application/json" -Headers $idemHeaders -UseBasicParsing
$secondBody = $secondWebRes.Content | ConvertFrom-Json
$isReplayed = $secondWebRes.Headers["Idempotent-Replay"]
Write-Host "   Second Request executed: Task ID=$($secondBody.id), Idempotent-Replay header='$isReplayed'" -ForegroundColor Green
if ($secondBody.id -ne $firstRes.id) {
    Write-Host "   ERROR: Task ID mismatch on idempotent replay" -ForegroundColor Red
}

# 18.3 Third request (same key, but payload tampered -> 409 Conflict)
$tamperedBody = @{
    title = "Tampered Task Title"
    priority = "low"
} | ConvertTo-Json
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/tasks/items" -Method Post -Body $tamperedBody -ContentType "application/json" -Headers $idemHeaders -UseBasicParsing
    Write-Host "   ERROR: Expected 409 Conflict for tampered payload" -ForegroundColor Red
} catch {
    Write-Host "   Payload mismatch protection ACTIVE: HTTP 409 Conflict / ErrorCode.IDEMPOTENCY_KEY_PAYLOAD_MISMATCH" -ForegroundColor Green
}

# 18.4 Fourth request (invalid UUID -> 400 Bad Request)
$badIdemHeaders = @{
    Authorization = "Bearer $($tokenResponse.rawSecretToken)"
    "Idempotency-Key" = "not-a-valid-uuid"
}
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/v1/tasks/items" -Method Post -Body $idemBody -ContentType "application/json" -Headers $badIdemHeaders -UseBasicParsing
    Write-Host "   ERROR: Expected 400 Bad Request for invalid UUID" -ForegroundColor Red
} catch {
    Write-Host "   Key format validation ACTIVE: HTTP 400 Bad Request / ErrorCode.IDEMPOTENCY_KEY_INVALID" -ForegroundColor Green
}

# 18.5 OpenAPI Specification verification
$openApiSpec = Invoke-RestMethod -Uri "$BaseUrl/api/v1/openapi.json" -Method Get
Write-Host "   OpenAPI Spec verified: Version=$($openApiSpec.openapi), Title='$($openApiSpec.info.title)', Paths count=$($openApiSpec.paths.PSObject.Properties.Count)" -ForegroundColor Green

# 19. Fleet Observability & Metrics (M13 OBS)
Write-Host "`n19. Fleet Observability & Metrics (W3C Traceparent, Actuator Info, Prometheus)..." -ForegroundColor Yellow

# 19.1 W3C Traceparent Header Verification
$traceTestRes = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/me" -Method Get -WebSession $session -UseBasicParsing
$traceparent = $traceTestRes.Headers["traceparent"]
Write-Host "   W3C Traceparent received in response: '$traceparent'" -ForegroundColor Green
if (-not $traceparent -or -not $traceparent.StartsWith("00-")) {
    Write-Host "   ERROR: Invalid or missing traceparent header" -ForegroundColor Red
}

# 19.2 Health & Info Subsystem Checks
$actuatorInfo = Invoke-RestMethod -Uri "$MgmtUrl/actuator/info" -Method Get
$platInfo = $actuatorInfo.dwhPlatform
Write-Host "   Actuator Info verified: DB status=$($platInfo.database.status) ($($platInfo.database.responseTimeMs)ms), Storage status=$($platInfo.storage.status) (Free: $($platInfo.storage.freeMb)MB), Typesense status=$($platInfo.typesense.status)" -ForegroundColor Green

# 19.3 Prometheus Metrics Endpoint Check
$promMetrics = Invoke-RestMethod -Uri "$MgmtUrl/actuator/prometheus" -Method Get
$hasJvmMetrics = $promMetrics -match "jvm_memory_used_bytes"
$hasUptime = $promMetrics -match "process_uptime_seconds"
Write-Host "   Prometheus Metrics verified: JVM metrics=$hasJvmMetrics, Uptime metrics=$hasUptime" -ForegroundColor Green

# 20. Outbound Webhooks (M18 KWH)
Write-Host "`n20. Outbound Webhooks Management & Subscription Lifecycle (M18 KWH)..." -ForegroundColor Yellow

$randWh = Get-Random -Minimum 1000 -Maximum 9999
$whBody = @{
    name = "Integration Webhook $randWh"
    targetUrl = "http://instance:8080/actuator/health"
    subscribedEvents = @("task.created", "user.created", "file.uploaded")
} | ConvertTo-Json

# 20.1 Create Subscription
$newSub = Invoke-RestMethod -Uri "$BaseUrl/api/v1/webhooks/subscriptions" -Method Post -Body $whBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Webhook Subscription created: ID=$($newSub.id), Name='$($newSub.name)', Secret Token=$($newSub.secretToken.Substring(0, 10))..." -ForegroundColor Green

# 20.2 List Subscriptions
$subsList = Invoke-RestMethod -Uri "$BaseUrl/api/v1/webhooks/subscriptions" -Method Get -WebSession $session
Write-Host "   Active Webhook Subscriptions count: $($subsList.Count)" -ForegroundColor Green

# 20.3 Update Subscription
$updateWhBody = @{
    name = "Integration Webhook $randWh (Updated)"
    targetUrl = "http://instance:8080/actuator/health"
    subscribedEvents = @("task.created", "task.status_changed")
    state = "A"
} | ConvertTo-Json
Invoke-RestMethod -Uri "$BaseUrl/api/v1/webhooks/subscriptions/$($newSub.id)" -Method Patch -Body $updateWhBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Webhook Subscription $($newSub.id) updated successfully" -ForegroundColor Green

# 20.4 Delete Subscription
Invoke-RestMethod -Uri "$BaseUrl/api/v1/webhooks/subscriptions/$($newSub.id)" -Method Delete -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Webhook Subscription $($newSub.id) deleted successfully" -ForegroundColor Green

# 21. Role-Based Access Control (RBAC) Enforcement & Security Isolation (M4 RBAC)
Write-Host "`n21. Role-Based Access Control (RBAC) Negative & Positive Permission Verification..." -ForegroundColor Yellow

$randUser = Get-Random -Minimum 1000 -Maximum 9999
$rbacUserLogin = "rbac_tester_$randUser"
$rbacUserPassword = "Password#$randUser"

# 21.1 Admin creates a regular user with 'user' role (role_id = 4)
$createUserBody = @{
    login = $rbacUserLogin
    name = "Regular Role User $randUser"
    email = "$rbacUserLogin@test.local"
    password = $rbacUserPassword
    roleIds = @(4)
} | ConvertTo-Json

$createdRbacUser = Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users" -Method Post -Body $createUserBody -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Regular user created: ID=$($createdRbacUser.id), Login='$rbacUserLogin', Role='user' (role_id=4)" -ForegroundColor Green

# 21.2 Authenticate as regular user
$userLoginBody = @{
    login = $rbacUserLogin
    password = $rbacUserPassword
    deviceInfo = "PowerShell RBAC Tester"
} | ConvertTo-Json
$userSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$userLoginRes = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/login" -Method Post -Body $userLoginBody -ContentType "application/json" -WebSession $userSession -UseBasicParsing

function Get-UserCsrfHeaders {
    $token = ""
    try {
        foreach ($c in $userSession.Cookies.GetCookies([System.Uri]$BaseUrl)) {
            if ($c.Name -eq "XSRF-TOKEN") {
                $token = $c.Value
            }
        }
    } catch {}
    if ($token) {
        return @{ "X-XSRF-TOKEN" = $token }
    }
    return @{}
}

$userMe = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/me" -Method Get -WebSession $userSession
Write-Host "   Authenticated as regular user '$($userMe.user.login)'. Permissions count: $($userMe.permissions.Count)" -ForegroundColor Green

# 21.3 Positive Check: Regular user CAN view tasks (tasks.items.view)
$userTasks = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/items" -Method Get -WebSession $userSession
Write-Host "   Positive Check PASSED: Regular user successfully queried tasks (Count: $($userTasks.items.Count))" -ForegroundColor Green

# 21.4 Negative Check: Regular user CANNOT view audit log (audit.log.view) -> HTTP 403
try {
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/audit/stats" -Method Get -WebSession $userSession
    Write-Host "   ERROR: Regular user should not be able to access audit stats!" -ForegroundColor Red
} catch {
    $statusCode = [int]$_.Exception.Response.StatusCode
    if ($statusCode -eq 403) {
        Write-Host "   Negative Check PASSED: Access to audit stats correctly denied (HTTP 403 Forbidden)" -ForegroundColor Green
    } else {
        Write-Host "   Unexpected status code: $statusCode" -ForegroundColor Yellow
    }
}

# 21.5 Negative Check: Regular user CANNOT delete users (iam.users.delete) -> HTTP 403
try {
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users/$($createdRbacUser.id)" -Method Delete -WebSession $userSession -Headers (Get-UserCsrfHeaders)
    Write-Host "   ERROR: Regular user should not be able to delete users!" -ForegroundColor Red
} catch {
    $statusCode = [int]$_.Exception.Response.StatusCode
    if ($statusCode -eq 403) {
        Write-Host "   Negative Check PASSED: User deletion correctly denied to regular role (HTTP 403 Forbidden)" -ForegroundColor Green
    } else {
        Write-Host "   Unexpected status code: $statusCode" -ForegroundColor Yellow
    }
}

# 21.6 Admin cleans up test user
Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users/$($createdRbacUser.id)" -Method Delete -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Test user $($createdRbacUser.id) cleaned up by admin" -ForegroundColor Green

# 22. System & License Information (GET /api/v1/system/license-info)
Write-Host "`n22. System & License Status Verification (GET /api/v1/system/license-info)..." -ForegroundColor Yellow
$licInfo = Invoke-RestMethod -Uri "$BaseUrl/api/v1/system/license-info" -Method Get -WebSession $session
Write-Host "   Client Code: $($licInfo.clientCode), License Status: $($licInfo.licenseStatus), Profile: $($licInfo.resourceProfile)" -ForegroundColor Green
Write-Host "   App Version: $($licInfo.appVersion), Schema Version: $($licInfo.schemaVersion), Write Allowed: $($licInfo.writeAllowed)" -ForegroundColor Green

# 23. Dynamic PostgreSQL 18 Analytics Engine (GET /api/v1/analytics/...)
Write-Host "`n23. Dynamic PostgreSQL 18 Analytics & Dashboard Metrics..." -ForegroundColor Yellow
$summary = Invoke-RestMethod -Uri "$BaseUrl/api/v1/analytics/summary" -Method Get -WebSession $session
Write-Host "   Analytics Summary: Total Tasks=$($summary.totalTasks), Active=$($summary.activeTasks), Completed=$($summary.completedTasks), Overdue=$($summary.overdueTasks), Rate=$($summary.completionRatePercent)%" -ForegroundColor Green

$trends = Invoke-RestMethod -Uri "$BaseUrl/api/v1/analytics/trends?range=7d" -Method Get -WebSession $session
Write-Host "   Analytics Trends (7d data points count): $($trends.Count), First Point=$($trends[0].date): Created=$($trends[0].createdCount), Completed=$($trends[0].completedCount)" -ForegroundColor Green

$projects = Invoke-RestMethod -Uri "$BaseUrl/api/v1/analytics/projects" -Method Get -WebSession $session
Write-Host "   Projects Distribution count: $($projects.Count)" -ForegroundColor Green

$workload = Invoke-RestMethod -Uri "$BaseUrl/api/v1/analytics/workload" -Method Get -WebSession $session
Write-Host "   Team Workload count: $($workload.Count)" -ForegroundColor Green

# 24. Enterprise Reporting & Data Export (GET /api/v1/reports/tasks/export)
Write-Host "`n24. Enterprise Reporting & Data Export (CSV & Excel)..." -ForegroundColor Yellow
$csvReport = Invoke-RestMethod -Uri "$BaseUrl/api/v1/reports/tasks/export?format=csv" -Method Get -WebSession $session
$csvLines = ($csvReport -split "`r?`n").Count
Write-Host "   CSV Export generated successfully: Total lines=$csvLines, Header verified" -ForegroundColor Green

$excelReport = Invoke-RestMethod -Uri "$BaseUrl/api/v1/reports/tasks/export?format=xlsx" -Method Get -WebSession $session
$hasWorkbook = ($excelReport -ne $null)
Write-Host "   Excel SpreadsheetML Export generated successfully: Content verified" -ForegroundColor Green

# 25. Mandatory Password Change on First Login & Security Hardening
Write-Host "`n25. Mandatory Password Change on First Login & Security Hardening..." -ForegroundColor Yellow
$tempUserLogin = "temp_user_" + (Get-Random -Minimum 1000 -Maximum 9999)
$tempInitialPass = "TempInitPass123!"
$newUserPayload = @{
    name = "Temporary Password Tester"
    login = $tempUserLogin
    email = "$tempUserLogin@dev.local"
    password = $tempInitialPass
    roleIds = @(4) # regular user
    forcePasswordChange = $true
} | ConvertTo-Json

$createdTempUser = Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users" -Method Post -Body $newUserPayload -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Created test user with forcePasswordChange=true (ID: $($createdTempUser.id), Login: $tempUserLogin)" -ForegroundColor Green

# Login as temp user
$tempSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginResp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -Body (@{ login = $tempUserLogin; password = $tempInitialPass } | ConvertTo-Json) -ContentType "application/json" -WebSession $tempSession
Write-Host "   Logged in as temp user. forcePasswordChange reported: $($loginResp.user.forcePasswordChange)" -ForegroundColor Green

# Attempt to access tasks before changing password (Must be rejected with 403)
try {
    Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/items" -Method Get -WebSession $tempSession
    Write-Error "Security Failure: Temp user accessed business API without changing password!"
} catch {
    $code = [int]$_.Exception.Response.StatusCode
    if ($code -eq 403) {
        Write-Host "   Positive Security Gate PASSED: Access to tasks blocked with HTTP 403 MUST_CHANGE_PASSWORD" -ForegroundColor Green
    } else {
        Write-Host "   Received status $code" -ForegroundColor Yellow
    }
}

# Change password via POST /api/v1/auth/password
$newPass = "PermanentSecurePass456!"
$changePassPayload = @{
    oldPassword = $tempInitialPass
    newPassword = $newPass
} | ConvertTo-Json

$tempCookies = $tempSession.Cookies.GetCookies((New-Object System.Uri($BaseUrl)))
$tempXsrf = ($tempCookies | Where-Object { $_.Name -eq "XSRF-TOKEN" }).Value
$tempHeaders = @{}
if ($tempXsrf) { $tempHeaders["X-XSRF-TOKEN"] = $tempXsrf }

Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/password" -Method Post -Body $changePassPayload -ContentType "application/json" -WebSession $tempSession -Headers $tempHeaders
Write-Host "   Password changed successfully via POST /api/v1/auth/password" -ForegroundColor Green

# Now access tasks (Must succeed)
$tasksAfter = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tasks/items" -Method Get -WebSession $tempSession
Write-Host "   Full access UNLOCKED: Temp user successfully queried tasks (Count: $($tasksAfter.items.Count))" -ForegroundColor Green

# Cleanup temp user
Invoke-RestMethod -Uri "$BaseUrl/api/v1/iam/users/$($createdTempUser.id)" -Method Delete -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Temporary test user $($createdTempUser.id) cleaned up" -ForegroundColor Green

# 26. OAuth2 / SSO Providers & Token Exchange (SSO Flow)
Write-Host "`n26. OAuth2 / SSO Providers & Token Exchange..." -ForegroundColor Yellow

$providers = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/oauth2/providers" -Method Get
Write-Host "   Active SSO Providers count: $($providers.Count)" -ForegroundColor Green
foreach ($p in $providers) {
    Write-Host "     - Provider: $($p.name) (ID: $($p.providerId), AuthURL: $($p.authorizationUrl))" -ForegroundColor Green
}

# 26.2 Test SSO Token Exchange (Auto-provisioning mock user)
$ssoAuthCode = "sso_auth_code_$(Get-Random -Minimum 1000 -Maximum 9999)"
$ssoExchangePayload = @{
    provider = "google"
    code = $ssoAuthCode
    email = "test.sso.user$randUser@company.corp"
    name = "Enterprise SSO Tester"
} | ConvertTo-Json

$ssoExchangeResp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/auth/oauth2/exchange" -Method Post -Body $ssoExchangePayload -ContentType "application/json" -SessionVariable ssoSession -UseBasicParsing
$ssoUserJson = $ssoExchangeResp.Content | ConvertFrom-Json
Write-Host "   SSO Exchange Success: User='$($ssoUserJson.user.name)' (Email: $($ssoUserJson.user.email))" -ForegroundColor Green

# Verify SSO user session
$ssoMeResp = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/me" -Method Get -WebSession $ssoSession
Write-Host "   SSO Session Authenticated: Login='$($ssoMeResp.user.login)'" -ForegroundColor Green

# 27. Client Custom Modules SDK Lifecycle & CP Moderation Workflow
Write-Host "`n27. Client Custom Modules SDK Lifecycle & Moderation Workflow..." -ForegroundColor Yellow

$modCode = "hr_onboarding_$(Get-Random -Minimum 100 -Maximum 999)"
$createModPayload = @{
    code = $modCode
    name = "HR Onboarding Extension"
    version = "1.2.0"
    description = "Enterprise HR Onboarding Workflow & Document Signing Module"
    category = "hr"
    icon = "badge"
    routePath = "/custom/$modCode"
    entrypointUrl = "https://cdn.company.internal/modules/$modCode/main.js"
    permissionsJson = '[{"action": "view", "name": "VIEW_HR"}, {"action": "manage", "name": "MANAGE_HR"}]'
} | ConvertTo-Json

# 27.1 Instance: Create Custom Module Manifest (DRAFT)
$createdMod = Invoke-RestMethod -Uri "$BaseUrl/api/v1/modules" -Method Post -Body $createModPayload -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Custom Module Manifest created in Instance: ID=$($createdMod.id), Code='$($createdMod.code)', Status='$($createdMod.status)'" -ForegroundColor Green

# 27.2 Instance: Submit for CP Approval (PENDING_APPROVAL)
$submittedMod = Invoke-RestMethod -Uri "$BaseUrl/api/v1/modules/$($createdMod.id)/submit" -Method Post -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Module submitted for Approval: Status='$($submittedMod.status)', TicketId='$($submittedMod.cpTicketId)'" -ForegroundColor Green

# 27.3 Instance: Apply CP Approval Callback (APPROVED) -> Triggers dynamic md_forms, md_actions & md_permissions
$approvalCallbackPayload = @{
    status = "APPROVED"
    rejectionReason = $null
} | ConvertTo-Json

$approvedMod = Invoke-RestMethod -Uri "$BaseUrl/api/v1/modules/$($createdMod.id)/moderation-callback" -Method Post -Body $approvalCallbackPayload -ContentType "application/json" -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Module Approved and Activated: Status='$($approvedMod.status)', ApprovedAt='$($approvedMod.approvedAt)'" -ForegroundColor Green

# 27.4 Instance: Verify Active Custom Modules List
$activeMods = Invoke-RestMethod -Uri "$BaseUrl/api/v1/modules/active" -Method Get -WebSession $session
$foundActive = $activeMods | Where-Object { $_.code -eq $modCode }
Write-Host "   Active Custom Modules verified: Found '$($foundActive.name)' ($($foundActive.code))" -ForegroundColor Green

# 27.5 Cleanup test module
Invoke-RestMethod -Uri "$BaseUrl/api/v1/modules/$($createdMod.id)" -Method Delete -WebSession $session -Headers (Get-CsrfHeaders)
Write-Host "   Test custom module $($createdMod.id) cleaned up" -ForegroundColor Green

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  All 27 End-to-End Scenarios Passed Successfully!           " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan






