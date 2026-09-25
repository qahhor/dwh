#!/usr/bin/env bash
# Behavioural regression (ADR-0001, two databases): on a real PostgreSQL the backup
# image must
#   - create the DWH database on an installation from before the DWH and let the
#     backup role read it, including schemas that migrations add later;
#   - stay idempotent when bootstrapped again;
#   - write one encrypted archive per database with the same timestamp;
#   - restore the DWH archive with the application role as owner;
#   - report FAILED, leaving no partial file, when the DWH cannot be read.
set -euo pipefail
export MSYS_NO_PATHCONV=1

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
image="smartupcms/backup:backup-databases-test"
postgres_image="postgres:18-alpine"
run_id="backup-db-test-$$-$RANDOM"
network="${run_id}-net"
postgres="${run_id}-pg"
work="$(mktemp -d)"
if command -v cygpath >/dev/null 2>&1; then
    work="$(cygpath -m "$work")"
    repo_root="$(cygpath -m "$repo_root")"
fi

cleanup() {
    docker rm -f "$postgres" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

fail() { echo "[FAIL] $*" >&2; exit 1; }
sql() { docker exec "$postgres" psql -X -A -t -v ON_ERROR_STOP=1 -U postgres "$@"; }
app_sql() { docker exec -e PGPASSWORD=app-test-password "$postgres" psql -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U smartupcms "$@"; }

bootstrap() {
    docker run --rm --network "$network" --entrypoint /usr/local/bin/bootstrap-role \
        -e PGHOST="$postgres" -e PGDATABASE=smartupcms -e PGUSER=postgres -e PGPASSWORD_FILE=/secrets/admin \
        -e APP_DB_USER=smartupcms -e APP_DB_PASSWORD_FILE=/secrets/app \
        -e BACKUP_DB_PASSWORD_FILE=/secrets/backup -e DWH_DB_NAME=smartupcms_dwh \
        -v "$work/secrets:/secrets:ro" "$image" >/dev/null
}

backup_once() {
    docker run --rm --network "$network" -e BACKUP_RUN_ONCE=true \
        -e PGHOST="$postgres" -e PGDATABASE=smartupcms -e PGUSER=smartupcms_backup \
        -e PGPASSWORD_FILE=/secrets/backup -e AGE_RECIPIENT="$recipient" -e DWH_DB_NAME=smartupcms_dwh \
        -v "$work/secrets:/secrets:ro" -v "$work/backups:/backups" -v "$work/status:/status" "$image"
}

docker build --quiet --tag "$image" "$repo_root/deploy/images/backup" >/dev/null

mkdir -p "$work/secrets" "$work/backups" "$work/status" "$work/key"
printf 'admin-test-password' > "$work/secrets/admin"
printf 'app-test-password' > "$work/secrets/app"
printf 'backup-test-password' > "$work/secrets/backup"
chmod 0644 "$work"/secrets/*
chmod 0777 "$work/backups" "$work/status" "$work/key"
docker run --rm --entrypoint age-keygen -v "$work/key:/key" "$image" -o /key/identity.txt 2>/dev/null
recipient="$(grep -o 'age1[0-9a-z]*' "$work/key/identity.txt")"
[[ -n "$recipient" ]] || fail 'no age recipient was generated'

docker network create "$network" >/dev/null
docker run -d --name "$postgres" --network "$network" \
    -e POSTGRES_PASSWORD=admin-test-password -e POSTGRES_DB=smartupcms "$postgres_image" >/dev/null
for _ in $(seq 1 60); do
    sql -d smartupcms -c 'select 1' >/dev/null 2>&1 && break
    sleep 1
done
sql -d smartupcms -c "create table md_users(id text primary key); insert into md_users values ('u1');" >/dev/null

echo '[1/5] Bootstrap on an installation without the DWH database...'
bootstrap
owner="$(sql -d postgres -c "select pg_get_userbyid(datdba) from pg_database where datname = 'smartupcms_dwh'")"
[[ "$owner" == "smartupcms" ]] || fail "DWH database owner is '$owner', expected smartupcms"

echo '[2/5] Migrations add schemas before and after a second bootstrap...'
app_sql -d smartupcms_dwh -c "create schema raw; create table raw.sales(id bigserial primary key, amount numeric); insert into raw.sales(amount) values (10), (20), (30);"
bootstrap
app_sql -d smartupcms_dwh -c "create schema mart; create table mart.daily as select 1 as day;"

echo '[3/5] One backup run writes both archives...'
backup_once >/dev/null
grep -q '"status":"SUCCESS"' "$work/status/status.json" || fail "backup status: $(cat "$work/status/status.json")"
for prefix in smartupcms smartupcms_dwh; do
    count="$(find "$work/backups" -name "${prefix}-*.dump.age" | wc -l)"
    [[ "$count" -eq 1 ]] || fail "expected one ${prefix} archive, found $count"
done
cms_archive="$(basename "$(find "$work/backups" -name 'smartupcms-*.dump.age')")"
dwh_archive="$(basename "$(find "$work/backups" -name 'smartupcms_dwh-*.dump.age')")"
[[ "${cms_archive#smartupcms-}" == "${dwh_archive#smartupcms_dwh-}" ]] || fail 'archives of one run must share the timestamp'
grep -q '"database":"smartupcms_dwh"' "$work/backups/${dwh_archive}.manifest.json" || fail 'DWH manifest names the wrong database'
(cd "$work/backups" && sha256sum -c "${dwh_archive}.sha256" >/dev/null) || fail 'DWH archive checksum does not match'

echo '[4/5] The DWH archive restores with the application role as owner...'
sql -d postgres -c 'create database dwh_restored owner smartupcms' >/dev/null
docker run --rm --entrypoint age -v "$work/backups:/backups:ro" -v "$work/key:/key:ro" "$image" \
    --decrypt --identity /key/identity.txt "/backups/$dwh_archive" \
    | docker exec -i "$postgres" pg_restore --exit-on-error --no-owner --no-acl --role=smartupcms -U postgres -d dwh_restored
rows="$(sql -d dwh_restored -c 'select count(*) from raw.sales')"
[[ "$rows" == "3" ]] || fail "restored raw.sales has $rows rows, expected 3"
[[ "$(sql -d dwh_restored -c "select count(*) from pg_tables where schemaname = 'mart'")" == "1" ]] \
    || fail 'a schema added after bootstrap is missing from the archive'
foreign="$(sql -d dwh_restored -c "select count(*) from pg_tables where schemaname in ('raw', 'mart') and tableowner <> 'smartupcms'")"
[[ "$foreign" == "0" ]] || fail "$foreign restored tables are not owned by the application role"

echo '[5/5] An unreadable DWH fails the run without partial files...'
sql -d smartupcms_dwh -c 'revoke select on raw.sales from smartupcms_backup' >/dev/null
if backup_once >/dev/null 2>&1; then fail 'backup succeeded without reading the DWH'; fi
grep -q '"failureCode":"DATABASE_DUMP_FAILED"' "$work/status/status.json" || fail "status after failure: $(cat "$work/status/status.json")"
[[ -z "$(find "$work/backups" -name '*.partial')" ]] || fail 'a partial file was left behind'

echo 'Backup of both databases passed.'
