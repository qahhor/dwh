#!/bin/sh
set -eu

PGHOST="${PGHOST:-postgres}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-smartupcms}"
PGUSER="${PGUSER:-smartupcms}"
BACKUP_DB_USER="${BACKUP_DB_USER:-smartupcms_backup}"
export PGHOST PGPORT PGDATABASE PGUSER

if [ -z "${PGPASSWORD_FILE:-}" ] || [ -z "${BACKUP_DB_PASSWORD_FILE:-}" ] \
    || [ ! -f "$PGPASSWORD_FILE" ] || [ ! -f "$BACKUP_DB_PASSWORD_FILE" ]; then
    echo 'database credential files are required' >&2
    exit 64
fi

admin_password="$(cat "$PGPASSWORD_FILE")"
backup_password="$(cat "$BACKUP_DB_PASSWORD_FILE")"
if [ -z "$admin_password" ] || [ -z "$backup_password" ]; then
    echo 'database credential files must not be empty' >&2
    exit 64
fi

umask 077
pgpass_file="$(mktemp /tmp/.pgpass.XXXXXX)"
trap 'rm -f "$pgpass_file"' EXIT HUP INT TERM
printf '%s:%s:%s:%s:%s\n' "$PGHOST" "$PGPORT" "$PGDATABASE" "$PGUSER" "$admin_password" > "$pgpass_file"
chmod 0600 "$pgpass_file"
export PGPASSFILE="$pgpass_file"
unset admin_password

psql --set=ON_ERROR_STOP=1 --no-psqlrc --quiet \
    --set=backup_user="$BACKUP_DB_USER" --set=backup_password="$backup_password" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
    :'backup_user', :'backup_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'backup_user') \gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
    :'backup_user', :'backup_password') \gexec
SELECT format('ALTER ROLE %I SET default_transaction_read_only = on', :'backup_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'backup_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace
WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace
WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' \gexec
SELECT format('GRANT SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', nspname, :'backup_user')
FROM pg_namespace
WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' \gexec
SELECT format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT ON TABLES TO %I', nspname, :'backup_user')
FROM pg_namespace
WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' \gexec
SQL

unset backup_password
echo 'backup database role is ready'
