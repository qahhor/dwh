#!/bin/sh
set -eu

PGHOST="${PGHOST:-postgres}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-smartupcms}"
PGUSER="${PGUSER:-postgres}"
MIGRATE_DB_USER="${MIGRATE_DB_USER:-smartupcms_migrator}"
APP_DB_USER="${APP_DB_USER:-${DB_USER:-smartupcms}}"
BACKUP_DB_USER="${BACKUP_DB_USER:-smartupcms_backup}"
# ADR-0001: the DWH database, owned by the application role (db/dwh migrations
# create its schemas). Empty only where no DWH exists (a bare test).
WAREHOUSE_DB_NAME="${WAREHOUSE_DB_NAME-smartupcms_dwh}"
export PGHOST PGPORT PGDATABASE PGUSER

if [ -n "$WAREHOUSE_DB_NAME" ] && [ "$WAREHOUSE_DB_NAME" = "$PGDATABASE" ]; then
    echo 'WAREHOUSE_DB_NAME must differ from the CMS database name' >&2
    exit 64
fi

read_credential() {
    if [ -z "$1" ] || [ ! -f "$1" ] || [ ! -r "$1" ]; then
        echo 'each database role requires a readable credential file' >&2
        exit 64
    fi
    value="$(cat "$1")"
    if [ "$3" = set ] && [ "$value" != "$2" ]; then
        echo 'inline database credentials must match their credential files' >&2
        exit 64
    fi
    printf '%s' "$value"
}

admin_password="$(read_credential "${PGPASSWORD_FILE:-}" "${POSTGRES_PASSWORD:-}" "${POSTGRES_PASSWORD+set}")"
migrate_password="$(read_credential "${MIGRATE_DB_PASSWORD_FILE:-}" "${MIGRATE_DB_PASSWORD:-}" "${MIGRATE_DB_PASSWORD+set}")"
app_password="$(read_credential "${APP_DB_PASSWORD_FILE:-}" "${APP_DB_PASSWORD:-}" "${APP_DB_PASSWORD+set}")"
backup_password="$(read_credential "${BACKUP_DB_PASSWORD_FILE:-}" "${BACKUP_DB_PASSWORD:-}" "${BACKUP_DB_PASSWORD+set}")"

if [ -z "$admin_password" ] || [ -z "$backup_password" ] || [ -z "$migrate_password" ] || [ -z "$app_password" ]; then
    echo 'database credentials must not be empty' >&2
    exit 64
fi

require_distinct() {
    while [ "$#" -gt 0 ]; do
        current="$1"
        shift
        for other do
            if [ "$current" = "$other" ]; then
                echo 'database role names and credentials must be distinct' >&2
                exit 64
            fi
        done
    done
}

require_distinct "$PGUSER" "$MIGRATE_DB_USER" "$APP_DB_USER" "$BACKUP_DB_USER"
require_distinct "$admin_password" "$migrate_password" "$app_password" "$backup_password"

umask 077
pgpass_file="$(mktemp /tmp/.pgpass.XXXXXX)"
trap 'rm -f "$pgpass_file"' EXIT HUP INT TERM
printf '%s:%s:%s:%s:%s\n' "$PGHOST" "$PGPORT" "$PGDATABASE" "$PGUSER" "$admin_password" > "$pgpass_file"
if [ -n "$WAREHOUSE_DB_NAME" ]; then
    printf '%s:%s:%s:%s:%s\n' "$PGHOST" "$PGPORT" "$WAREHOUSE_DB_NAME" "$PGUSER" "$admin_password" >> "$pgpass_file"
fi
chmod 0600 "$pgpass_file"
export PGPASSFILE="$pgpass_file"
unset admin_password

psql --set=ON_ERROR_STOP=1 --no-psqlrc --quiet \
    --set=migrate_user="$MIGRATE_DB_USER" --set=migrate_password="$migrate_password" \
    --set=app_user="$APP_DB_USER" --set=app_password="$app_password" \
    --set=backup_user="$BACKUP_DB_USER" --set=backup_password="$backup_password" <<'SQL'
-- Extensions
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "fuzzystrmatch";

-- Roles
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
    :'migrate_user', :'migrate_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrate_user') \gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
    :'migrate_user', :'migrate_password') \gexec

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
    :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
    :'app_user', :'app_password') \gexec

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
    :'backup_user', :'backup_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'backup_user') \gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
    :'backup_user', :'backup_password') \gexec

-- Migrator Schema ownership and permissions
SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO %I', current_database(), :'migrate_user') \gexec
SELECT format('ALTER SCHEMA %I OWNER TO %I', nspname, :'migrate_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('GRANT ALL ON SCHEMA %I TO %I', nspname, :'migrate_user')
FROM pg_namespace WHERE nspname = 'public' \gexec

-- Transfer table and sequence ownership to migrate_user if upgrading from single-user setup.
-- psql does not substitute variables inside a dollar-quoted body, so the name
-- travels through a session setting.
SELECT set_config('smartupcms.migrate_user', :'migrate_user', false) AS migrate_setting \gset
DO $$
DECLARE
    r RECORD;
    v_migrator text := current_setting('smartupcms.migrate_user');
BEGIN
    FOR r IN (
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'public' AND tableowner <> v_migrator
    ) LOOP
        EXECUTE format('ALTER TABLE public.%I OWNER TO %I', r.tablename, v_migrator);
    END LOOP;

    FOR r IN (
        SELECT sequencename FROM pg_sequences
        WHERE schemaname = 'public' AND sequenceowner <> v_migrator
    ) LOOP
        EXECUTE format('ALTER SEQUENCE public.%I OWNER TO %I', r.sequencename, v_migrator);
    END LOOP;
END $$;

-- Default privileges for future tables/sequences created by migrator
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
    :'migrate_user', nspname, :'app_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO %I',
    :'migrate_user', nspname, :'app_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE TRUNCATE ON TABLES FROM %I',
    :'migrate_user', nspname, :'app_user')
FROM pg_namespace WHERE nspname = 'public' \gexec

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT ON TABLES TO %I',
    :'migrate_user', nspname, :'backup_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I GRANT SELECT ON SEQUENCES TO %I',
    :'migrate_user', nspname, :'backup_user')
FROM pg_namespace WHERE nspname = 'public' \gexec

-- App user permissions on current objects
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', nspname, :'app_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('REVOKE CREATE ON SCHEMA public FROM %I', :'app_user') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', nspname, :'app_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', nspname, :'app_user')
FROM pg_namespace WHERE nspname = 'public' \gexec

-- Explicitly revoke TRUNCATE and UPDATE/DELETE on audit_log
SELECT format('REVOKE TRUNCATE ON ALL TABLES IN SCHEMA %I FROM %I', nspname, :'app_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_log, audit_log_default FROM %I', :'app_user')
WHERE EXISTS (SELECT 1 FROM pg_class WHERE relname = 'audit_log') \gexec

-- Audit partition maintenance is granted to the application role only, not to PUBLIC (V125)
SELECT format('GRANT EXECUTE ON FUNCTION audit_log_create_partition(int, int), audit_log_detach_partition(int, int) TO %I', :'app_user')
WHERE EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'audit_log_detach_partition') \gexec
-- Daily partitions and the audit archive (V127)
SELECT format('GRANT EXECUTE ON FUNCTION audit_log_create_day_partition(date), audit_log_detach_day_partition(date), audit_log_drop_archived_partition(text) TO %I', :'app_user')
WHERE EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'audit_log_drop_archived_partition') \gexec

-- Backup user read-only permissions
SELECT format('ALTER ROLE %I SET default_transaction_read_only = on', :'backup_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'backup_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SELECT format('GRANT SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace WHERE nspname = 'public' \gexec
SQL

unset migrate_password app_password backup_password

if [ -n "$WAREHOUSE_DB_NAME" ]; then
    # init-dwh.sh creates the DWH database only on an empty PGDATA; an installation
    # upgraded from before the DWH gets it here. CREATE DATABASE cannot run inside
    # a transaction, so it is a statement of its own.
    psql --set=ON_ERROR_STOP=1 --no-psqlrc --quiet \
        --set=dwh_database="$WAREHOUSE_DB_NAME" --set=app_user="$APP_DB_USER" <<'SQL'
SELECT format('CREATE DATABASE %I OWNER %I', :'dwh_database', :'app_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'dwh_database') \gexec
SQL

    # The backup role reads every DWH schema: those that exist now and, through
    # default privileges of the owning application role, those that migrations
    # add later. It writes nothing (default_transaction_read_only above).
    psql --set=ON_ERROR_STOP=1 --no-psqlrc --quiet --dbname="$WAREHOUSE_DB_NAME" \
        --set=app_user="$APP_DB_USER" --set=backup_user="$BACKUP_DB_USER" <<'SQL'
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'backup_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace
WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema' \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace
WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema' \gexec
SELECT format('GRANT SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace
WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema' \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I GRANT USAGE ON SCHEMAS TO %I', :'app_user', :'backup_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I GRANT SELECT ON TABLES TO %I', :'app_user', :'backup_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I GRANT SELECT ON SEQUENCES TO %I', :'app_user', :'backup_user') \gexec
SQL
fi

echo 'database roles and privileges are ready'
