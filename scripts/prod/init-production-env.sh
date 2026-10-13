#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${1:-.env.production}"
SECRETS_DIR="${SECRETS_DIR:-deploy/compose/.secrets}"
APP_VERSION="${APP_VERSION:-1.0.0}"
ORGANIZATION_CODE="${ORGANIZATION_CODE:-smartup}"
ORGANIZATION_NAME="${ORGANIZATION_NAME:-Smartup Enterprise}"
RESOURCE_PROFILE="${RESOURCE_PROFILE:-S}"
ADMIN_LOGIN="${ADMIN_LOGIN:-admin}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
AGE_IDENTITY_FILE="${AGE_IDENTITY_FILE:-backup-age-identity.txt}"
STORAGE_MODE="${STORAGE_MODE:-local_disk}"
BACKUP_STORAGE_MODE="${BACKUP_STORAGE_MODE:-local}"
HTTP_PORT="${HTTP_PORT:-8080}"
HTTP_BIND="${HTTP_BIND:-127.0.0.1}"
SMC_PUBLIC_URL="${SMC_PUBLIC_URL:-}"
SMTP_HOST="${SMTP_HOST:-}"
FORCE="${FORCE:-false}"
VALIDATE="${VALIDATE:-false}"

echo "=== SmartupCMS Production Environment Initializer ==="

if [[ -z "$SMC_PUBLIC_URL" ]]; then
    echo "Error: SMC_PUBLIC_URL is required (e.g. https://cms.example.com): invitation and reset links are built from it." >&2
    exit 1
fi

if [[ -n "$SMTP_HOST" ]]; then
    MAIL_PROVIDER="smtp"
else
    MAIL_PROVIDER="console_mail"
fi

if [[ -f "$ENV_FILE" && "$FORCE" != "true" ]]; then
    echo "Error: Target environment file '$ENV_FILE' already exists. Set FORCE=true or remove the file." >&2
    exit 1
fi

generate_hex_token() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32
    else
        head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
    fi
}

# The key of the secrets kept in the database (ADR-0029): base64 of 32 random bytes.
generate_base64_key() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 32
    else
        head -c 32 /dev/urandom | base64 | tr -d '\n'
    fi
}

# The first administrator password: 20 characters, the longest the password policy accepts.
generate_secure_password() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 24 | tr -dc 'A-Za-z0-9!@#$%^&*-_=+' | head -c 20
    else
        head -c 64 /dev/urandom | tr -dc 'A-Za-z0-9!@#$%^&*-_=+' | head -c 20
    fi
}

command -v age-keygen >/dev/null 2>&1 || { echo "Error: host age-keygen is required." >&2; exit 1; }

# 1. Prepare secrets directory
umask 077
mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"
SECRETS_DIR="$(cd "$SECRETS_DIR" && pwd -P)"
if command -v cygpath >/dev/null 2>&1; then
    SECRETS_DIR="$(cygpath -m "$SECRETS_DIR")"
fi

# 2. Generate secrets
DB_PASSWORD="$(generate_hex_token)"
POSTGRES_PASSWORD="$(generate_hex_token)"
MIGRATE_DB_PASSWORD="$(generate_hex_token)"
BACKUP_DB_PASSWORD="$(generate_hex_token)"
TYPESENSE_API_KEY="$(generate_hex_token)"
SMC_SECRETS_KEY="$(generate_base64_key)"

if [[ -z "$ADMIN_PASSWORD" ]]; then
    ADMIN_PASSWORD="$(generate_secure_password)"
fi

# Write secret files with strict 0600 permissions
printf "%s" "$DB_PASSWORD" > "$SECRETS_DIR/database-password"
printf "%s" "$POSTGRES_PASSWORD" > "$SECRETS_DIR/postgres-password"
printf "%s" "$MIGRATE_DB_PASSWORD" > "$SECRETS_DIR/migrate-database-password"
printf "%s" "$BACKUP_DB_PASSWORD" > "$SECRETS_DIR/backup-database-password"
chmod 600 "$SECRETS_DIR/database-password" "$SECRETS_DIR/postgres-password" \
    "$SECRETS_DIR/migrate-database-password" "$SECRETS_DIR/backup-database-password"

echo "[+] Wrote database secret files to $SECRETS_DIR (mode 0600)"

# 3. Generate Age Keypair
AGE_RECIPIENT=""

if command -v age-keygen >/dev/null 2>&1; then
    echo "[+] Generating Age keypair using host age-keygen..."
    age-keygen -o "$AGE_IDENTITY_FILE" 2>/dev/null || true
    if [[ -f "$AGE_IDENTITY_FILE" ]]; then
        AGE_RECIPIENT="$(grep -E '^# public key: age1' "$AGE_IDENTITY_FILE" | awk '{print $NF}' || true)"
        chmod 600 "$AGE_IDENTITY_FILE"
    fi
fi

if [[ -z "$AGE_RECIPIENT" ]]; then
    echo "Error: a real age keypair is required; initialization aborted." >&2
    exit 1
fi
echo "[+] Age backup keypair generated; store the identity off-host: $AGE_IDENTITY_FILE"

# 4. Generate environment file
cat > "$ENV_FILE" <<EOF
# SmartupCMS: one installation, one organization, many users.
# Generated automatically by init-production-env.sh on $(date -u +'%Y-%m-%dT%H:%M:%SZ')
# Keep this file and all referenced secret files at mode 0600.

PROJECT_NAME=smartupcms
IMAGE_REGISTRY=ghcr.io/smartupcms
APP_VERSION=$APP_VERSION

ORGANIZATION_CODE=$ORGANIZATION_CODE
ORGANIZATION_NAME=$ORGANIZATION_NAME
RESOURCE_PROFILE=$RESOURCE_PROFILE

# Public address of the web application, e.g. https://cms.example.com.
# Required: invitation and password reset links are built from it, and the
# server refuses to start without it.
SMC_PUBLIC_URL=$SMC_PUBLIC_URL

# Database credentials (least-privilege role separation, NFR-SEC-02)
DB_NAME=smartupcms
POSTGRES_USER=postgres
POSTGRES_PASSWORD=$POSTGRES_PASSWORD
POSTGRES_PASSWORD_FILE=$SECRETS_DIR/postgres-password
MIGRATE_DB_USER=smartupcms_migrator
MIGRATE_DB_PASSWORD=$MIGRATE_DB_PASSWORD
MIGRATE_DB_PASSWORD_FILE=$SECRETS_DIR/migrate-database-password
DB_USER=smartupcms
DB_PASSWORD=$DB_PASSWORD
DB_PASSWORD_FILE=$SECRETS_DIR/database-password
BACKUP_DB_USER=smartupcms_backup
BACKUP_DB_PASSWORD=$BACKUP_DB_PASSWORD
BACKUP_DB_PASSWORD_FILE=$SECRETS_DIR/backup-database-password

ADMIN_LOGIN=$ADMIN_LOGIN
ADMIN_EMAIL=$ADMIN_EMAIL
ADMIN_PASSWORD=$ADMIN_PASSWORD

TYPESENSE_API_KEY=$TYPESENSE_API_KEY

# Encrypts the secrets kept in the database (ADR-0029). Keep a copy with the
# backup identity, outside this host: a restore without it loses those secrets.
SMC_SECRETS_KEY=$SMC_SECRETS_KEY

# Storage configuration
SMC_PROVIDER_STORAGE=$STORAGE_MODE
SMC_S3_ENDPOINT=
SMC_S3_REGION=auto
SMC_S3_ACCESS_KEY=
# S3 secret key (leave blank for local_disk)
SMC_S3_SECRET_KEY=
# Target bucket name
SMC_S3_BUCKET=
SMC_S3_PATH_STYLE=true

# Malware protection (fail-closed)
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

# Delivery channels: invitations, password reset links and two-factor codes.
# console_* delivers nothing. While SMC_DELIVERY_ENFORCE=true the server
# refuses to start on console_mail and while two-factor users depend on a
# console channel. Mail: SMTP_HOST + SMC_PROVIDER_MAIL=smtp.
# Telegram: TELEGRAM_BOT_TOKEN + SMC_PROVIDER_MESSENGER=telegram.
SMTP_HOST=$SMTP_HOST
SMTP_PORT=587
SMTP_USER=
SMTP_PASSWORD=
SMTP_STARTTLS=true
SMC_MAIL_FROM=no-reply@localhost
SMC_MAIL_FROM_NAME=SmartupCMS
SMC_PROVIDER_MAIL=$MAIL_PROVIDER
TELEGRAM_BOT_TOKEN=
SMC_PROVIDER_MESSENGER=console_messenger
SMC_PROVIDER_SMS=console_sms
SMC_DELIVERY_ENFORCE=true

# Network & Reverse Proxy
HTTP_BIND=$HTTP_BIND
HTTP_PORT=$HTTP_PORT

# Encrypted automated backups
BACKUP_AGE_RECIPIENT=$AGE_RECIPIENT
BACKUP_INTERVAL_SECONDS=86400
SMC_BACKUP_MAX_AGE=26h
BACKUP_RETENTION_DAYS=14
BACKUP_STORAGE_MODE=$BACKUP_STORAGE_MODE

BACKUP_S3_ENDPOINT=
BACKUP_S3_REGION=auto
BACKUP_S3_BUCKET=
BACKUP_S3_PREFIX=backups
BACKUP_S3_ACCESS_KEY_ID_FILE=./.secrets/backup-s3-access-key-id
BACKUP_S3_SECRET_ACCESS_KEY_FILE=./.secrets/backup-s3-secret-access-key
EOF

chmod 600 "$ENV_FILE"
echo "[+] Successfully created production environment file: $ENV_FILE (mode 0600)"

if [[ "$VALIDATE" == "true" ]]; then
    echo "[*] Validating generated configuration with Docker Compose..."
    docker compose -f deploy/compose/docker-compose.prod.yml --env-file "$ENV_FILE" --profile tools config >/dev/null
    echo "[+] Docker Compose configuration validation PASSED."
fi

echo ""
echo "=== Production Deployment Summary ==="
echo "Organization : $ORGANIZATION_NAME ($ORGANIZATION_CODE)"
echo "Admin user   : $ADMIN_LOGIN ($ADMIN_EMAIL)"
echo "Admin pass   : saved in the environment file"
echo "Environment  : $ENV_FILE"
echo "Secrets dir  : $SECRETS_DIR"
echo "Age public   : $AGE_RECIPIENT"
if [[ "$MAIL_PROVIDER" == "console_mail" ]]; then
    echo "Mail         : not configured. The server refuses to start until SMTP_HOST and"
    echo "               SMC_PROVIDER_MAIL=smtp are set in $ENV_FILE, or SMC_DELIVERY_ENFORCE=false knowingly."
fi
echo "Next step    : ./scripts/prod/deploy.sh $ENV_FILE"
