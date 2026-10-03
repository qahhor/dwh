param(
    [string]$EnvFile = '.env.production',
    [string]$SecretsDirectory = 'deploy/compose/.secrets',
    [string]$AppVersion = '1.0.0',
    [string]$OrganizationCode = 'smartup',
    [string]$OrganizationName = 'Smartup Enterprise',
    [ValidateSet('S', 'M', 'L')][string]$ResourceProfile = 'S',
    [string]$AdminLogin = 'admin',
    [string]$AdminEmail = 'admin@example.local',
    [string]$AdminPassword = '',
    [string]$AgeIdentityFile = 'backup-age-identity.txt',
    [ValidateSet('local_disk', 's3')][string]$StorageMode = 'local_disk',
    [ValidateSet('local', 's3')][string]$BackupStorageMode = 'local',
    [int]$HttpPort = 8080,
    [string]$HttpBind = '127.0.0.1',
    [string]$PublicUrl = '',
    [switch]$Force,
    [switch]$Validate
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$targetEnvPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }
$targetSecretsDir = if ([System.IO.Path]::IsPathRooted($SecretsDirectory)) { $SecretsDirectory } else { Join-Path $repoRoot $SecretsDirectory }
$targetAgeIdentity = if ([System.IO.Path]::IsPathRooted($AgeIdentityFile)) { $AgeIdentityFile } else { Join-Path $repoRoot $AgeIdentityFile }

Write-Host "=== SmartupCMS Production Environment Initializer ===" -ForegroundColor Cyan

if ((Test-Path -LiteralPath $targetEnvPath) -and (-not $Force)) {
    throw "Target environment file '$targetEnvPath' already exists. Use -Force to overwrite."
}

function New-SecureHexToken([int]$bytes = 32) {
    $buffer = New-Object byte[] $bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
        return -join ($buffer | ForEach-Object { $_.ToString('x2') })
    } finally {
        $rng.Dispose()
    }
}

# The key of the secrets kept in the database (ADR-0029): base64 of 32 random bytes.
function New-SecureBase64Key([int]$bytes = 32) {
    $buffer = New-Object byte[] $bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
        return [Convert]::ToBase64String($buffer)
    } finally {
        $rng.Dispose()
    }
}

# The first administrator password: 20 characters, the longest the password policy accepts.
function New-SecurePassword([int]$length = 20) {
    $chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*-_=+'
    $buffer = New-Object byte[] $length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
        $password = -join ($buffer | ForEach-Object { $chars[$_ % $chars.Length] })
        return $password
    } finally {
        $rng.Dispose()
    }
}

# 1. Ensure secrets directory
if (-not (Test-Path -LiteralPath $targetSecretsDir)) {
    New-Item -ItemType Directory -Path $targetSecretsDir -Force | Out-Null
    Write-Host "[+] Created secrets directory: $targetSecretsDir" -ForegroundColor Green
}

# 2. Generate database and application passwords
$dbPassword = New-SecureHexToken 32
$backupDbPassword = New-SecureHexToken 32
$typesenseApiKey = New-SecureHexToken 32
$secretsKey = New-SecureBase64Key 32
$resolvedAdminPassword = if ([string]::IsNullOrWhiteSpace($AdminPassword)) { New-SecurePassword 20 } else { $AdminPassword }

# Write secret files
$dbPasswordFile = Join-Path $targetSecretsDir 'database-password'
$backupPasswordFile = Join-Path $targetSecretsDir 'backup-database-password'

[System.IO.File]::WriteAllText($dbPasswordFile, $dbPassword, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($backupPasswordFile, $backupDbPassword, [System.Text.UTF8Encoding]::new($false))
Write-Host "[+] Wrote database secrets to $targetSecretsDir" -ForegroundColor Green

# 3. Generate Age Keypair for backups
$ageRecipient = ''
$ageKeygenCmd = Get-Command age-keygen -ErrorAction SilentlyContinue

if ($null -ne $ageKeygenCmd) {
    Write-Host "[+] Generating Age keypair using host age-keygen..." -ForegroundColor Gray
    & $ageKeygenCmd.Source -o $targetAgeIdentity 2>$null
    if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $targetAgeIdentity)) {
        $content = Get-Content -LiteralPath $targetAgeIdentity
        foreach ($line in $content) {
            if ($line -match '^# public key:\s+(age1[a-z0-9]+)') {
                $ageRecipient = $Matches[1]
                break
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($ageRecipient)) {
    $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -ne $dockerCmd) {
        Write-Host "[+] Generating Age keypair via ephemeral Docker container..." -ForegroundColor Gray
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $dockerOut = & docker run --rm alpine:latest sh -c "apk add --no-cache age >/dev/null 2>&1 && age-keygen" 2>&1
        $ErrorActionPreference = $prevEap
        if ($LASTEXITCODE -eq 0 -and $null -ne $dockerOut) {
            $dockerLines = $dockerOut -split "`r?`n"
            $privKey = ''
            foreach ($line in $dockerLines) {
                if ($line -match '^# public key:\s+(age1[a-z0-9]+)' -or $line -match '^Public key:\s+(age1[a-z0-9]+)') {
                    $ageRecipient = $Matches[1]
                }
                if ($line -match '^AGE-SECRET-KEY-1') {
                    $privKey = $line
                }
            }
            if (-not [string]::IsNullOrWhiteSpace($privKey) -and -not [string]::IsNullOrWhiteSpace($ageRecipient)) {
                $identityContent = "# created: $((Get-Date).ToUniversalTime().ToString('u'))`n# public key: $ageRecipient`n$privKey`n"
                [System.IO.File]::WriteAllText($targetAgeIdentity, $identityContent, [System.Text.UTF8Encoding]::new($false))
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($ageRecipient)) {
    $ageRecipient = 'age1' + (New-SecureHexToken 28)
    Write-Host "[!] Warning: age-keygen not available. Generated placeholder Age recipient: $ageRecipient" -ForegroundColor Yellow
    Write-Host "    Replace BACKUP_AGE_RECIPIENT with your real public key from age-keygen." -ForegroundColor Yellow
} else {
    Write-Host "[+] Age backup keypair generated successfully!" -ForegroundColor Green
    Write-Host "    Public recipient: $ageRecipient" -ForegroundColor Gray
    Write-Host "    Private key saved to: $targetAgeIdentity" -ForegroundColor Yellow
    Write-Host "    IMPORTANT: Move $targetAgeIdentity off this server to secure offline storage." -ForegroundColor Yellow
}

# 4. Generate .env.production
$envContent = @"
# SmartupCMS: one installation, one organization, many users.
# Generated automatically by init-production-env.ps1 on $((Get-Date).ToUniversalTime().ToString('u'))
# Keep this file and all referenced secret files at mode 0600.

PROJECT_NAME=smartupcms
IMAGE_REGISTRY=ghcr.io/smartupcms
APP_VERSION=$AppVersion

ORGANIZATION_CODE=$OrganizationCode
ORGANIZATION_NAME=$OrganizationName
RESOURCE_PROFILE=$ResourceProfile

# Public address of the web application, e.g. https://cms.example.com.
# Password reset links are built from it; empty means no link is sent.
SMC_PUBLIC_URL=$PublicUrl

# Database credentials (least-privilege role separation, NFR-SEC-02)
DB_NAME=smartupcms
POSTGRES_USER=postgres
POSTGRES_PASSWORD=$dbPassword
POSTGRES_PASSWORD_FILE=./.secrets/database-password
MIGRATE_DB_USER=smartupcms_migrator
MIGRATE_DB_PASSWORD=$dbPassword
MIGRATE_DB_PASSWORD_FILE=./.secrets/database-password
DB_USER=smartupcms
DB_PASSWORD=$dbPassword
DB_PASSWORD_FILE=./.secrets/database-password
BACKUP_DB_USER=smartupcms_backup
BACKUP_DB_PASSWORD_FILE=./.secrets/backup-database-password

ADMIN_LOGIN=$AdminLogin
ADMIN_EMAIL=$AdminEmail
ADMIN_PASSWORD=$resolvedAdminPassword

TYPESENSE_API_KEY=$typesenseApiKey

# Encrypts the secrets kept in the database (ADR-0029). Keep a copy with the
# backup identity, outside this host: a restore without it loses those secrets.
SMC_SECRETS_KEY=$secretsKey

# Storage configuration
SMC_PROVIDER_STORAGE=$StorageMode
SMC_S3_ENDPOINT=
SMC_S3_REGION=auto
SMC_S3_ACCESS_KEY=
# S3 secret key (leave blank for local_disk)
SMC_S3_SECRET_KEY=
# Target bucket name
SMC_S3_BUCKET=
SMC_S3_PATH_STYLE=true

# Malware protection (fail-closed)
CLAMAV_IMAGE=clamav/clamav-debian:1.5.4@sha256:df80497be841a8ad57f95e04f978216241457f8f8ad608f1f682e3cd0fe63c45
SMC_FILE_SCANNER_CLAMAV_HOST=clamav
SMC_FILE_SCANNER_CLAMAV_PORT=3310
SMC_FILE_SCANNER_CLAMAV_CONNECT_TIMEOUT=3s
SMC_FILE_SCANNER_CLAMAV_READ_TIMEOUT=60s

SMC_RATE_LIMIT_PUBLIC_READ_PER_MINUTE=600

# Outbound webhooks (disabled by default)
SMC_WEBHOOKS_ENABLED=false
SMC_WEBHOOKS_ALLOWED_HOSTS=
SMC_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES=false
SMC_WEBHOOKS_CONNECT_TIMEOUT=3s
SMC_WEBHOOKS_READ_TIMEOUT=10s

# Delivery channels: password reset links and two-factor codes. console_* only
# writes to the log; the server refuses to start while two-factor users depend
# on it (SMC_DELIVERY_ENFORCE). Mail: SMTP_HOST + SMC_PROVIDER_MAIL=smtp.
# Telegram: TELEGRAM_BOT_TOKEN + SMC_PROVIDER_MESSENGER=telegram.
SMTP_HOST=
SMTP_PORT=587
SMTP_USER=
SMTP_PASSWORD=
SMTP_STARTTLS=true
SMC_MAIL_FROM=no-reply@localhost
SMC_MAIL_FROM_NAME=SmartupCMS
SMC_PROVIDER_MAIL=console_mail
TELEGRAM_BOT_TOKEN=
SMC_PROVIDER_MESSENGER=console_messenger
SMC_PROVIDER_SMS=console_sms
SMC_DELIVERY_ENFORCE=true

# Network & Reverse Proxy
HTTP_BIND=$HttpBind
HTTP_PORT=$HttpPort

# Encrypted automated backups
BACKUP_AGE_RECIPIENT=$ageRecipient
BACKUP_INTERVAL_SECONDS=86400
SMC_BACKUP_MAX_AGE=26h
BACKUP_RETENTION_DAYS=14
BACKUP_STORAGE_MODE=$BackupStorageMode

BACKUP_S3_ENDPOINT=
BACKUP_S3_REGION=auto
BACKUP_S3_BUCKET=
BACKUP_S3_PREFIX=backups
BACKUP_S3_ACCESS_KEY_ID_FILE=./.secrets/backup-s3-access-key-id
BACKUP_S3_SECRET_ACCESS_KEY_FILE=./.secrets/backup-s3-secret-access-key
"@

[System.IO.File]::WriteAllText($targetEnvPath, $envContent.Trim() + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Host "[+] Successfully created production environment: $targetEnvPath" -ForegroundColor Green

# 5. Validation if requested
if ($Validate) {
    Write-Host "[*] Validating generated configuration with Docker Compose..." -ForegroundColor Yellow
    $composeFile = Join-Path $repoRoot 'deploy/compose/docker-compose.prod.yml'
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $composeConfigOut = & docker compose -f $composeFile --env-file $targetEnvPath --profile tools config --format json 2>&1
    $ErrorActionPreference = $prevEap
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[-] Compose config validation failed:" -ForegroundColor Red
        Write-Host ($composeConfigOut -join "`n") -ForegroundColor Red
        throw "Configuration validation failed."
    }
    Write-Host "[+] Docker Compose configuration validation PASSED." -ForegroundColor Green
}

Write-Host "`n=== Production Deployment Summary ===" -ForegroundColor Cyan
Write-Host "Organization : $OrganizationName ($OrganizationCode)"
Write-Host "Admin user   : $AdminLogin ($AdminEmail)"
Write-Host "Admin pass   : $resolvedAdminPassword"
Write-Host "Environment  : $targetEnvPath"
Write-Host "Secrets dir  : $targetSecretsDir"
Write-Host "Age public   : $ageRecipient"
Write-Host "Next step    : Run .\scripts\prod\deploy.ps1 -EnvFile $EnvFile" -ForegroundColor Yellow
