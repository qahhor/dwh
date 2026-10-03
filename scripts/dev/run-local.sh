#!/usr/bin/env bash
# =====================================================================================================================
# SmartupCMS - a local run from the sources (plan 10/10, item 6.5): Linux, macOS, WSL, the dev container.
# =====================================================================================================================
# The infrastructure runs in Docker Compose (PostgreSQL with both databases, the mail stub Mailpit, optionally
# Typesense); the server and the web application run on the host from the sources. Windows: scripts/dev/run-local.ps1
# takes the same commands and options.
#
#   scripts/dev/run-local.sh [command] [options]
#
# Commands:
#   up        (default) infrastructure, build, migrations of both databases, server, web dev server; waits until the
#             UI answers, prints the address and how to sign in, then runs until Ctrl+C
#   infra     only the infrastructure
#   migrate   infrastructure, build when needed, migrations of the main database and pg-dwh
#   down      stops the server, the web dev server and the infrastructure (--volumes also deletes the data)
#   status    what runs and where
#
# Options:
#   --detach      up returns once everything answers; stop it later with down
#   --demo        the server starts with the demo profile: users, projects, tasks, notes and orders (idempotent)
#   --search      also starts Typesense and turns the search index on
#   --skip-build  reuses apps/server/target/server-*-exec.jar
#   --volumes     with down: also deletes the database volumes and the local state in .local/
#
# Ports and names come from the environment (defaults in brackets): DB_PORT [5432], SERVER_PORT [8080],
# MANAGEMENT_PORT [9090], WEB_PORT [4200], WEB_HOST [localhost], MAILPIT_HTTP_PORT [8025], MAILPIT_SMTP_PORT [1025],
# TYPESENSE_PORT [8108], SMC_LOCAL_PROJECT [smartupcms-local] (the Compose project). Prerequisites: JDK 25, Node.js of
# .node-version with npm, Docker with Compose v2. Maven is not needed: the wrapper ./mvnw is used.
#
# The first administrator's password is generated into .local/admin-password (ignored by git) and never printed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$ROOT/.local"
COMMAND="up"
DETACH=0 DEMO=0 SEARCH=0 SKIP_BUILD=0 VOLUMES=0

for arg in "$@"; do
    case "$arg" in
        up|infra|migrate|down|status) COMMAND="$arg" ;;
        --detach) DETACH=1 ;;
        --demo) DEMO=1 ;;
        --search) SEARCH=1 ;;
        --skip-build) SKIP_BUILD=1 ;;
        --volumes) VOLUMES=1 ;;
        -h|--help) sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument: $arg (see --help)" >&2; exit 2 ;;
    esac
done

export DB_PORT="${DB_PORT:-5432}"
SERVER_PORT="${SERVER_PORT:-8080}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-9090}"
WEB_PORT="${WEB_PORT:-4200}"
WEB_HOST="${WEB_HOST:-localhost}"
export MAILPIT_HTTP_PORT="${MAILPIT_HTTP_PORT:-8025}"
export MAILPIT_SMTP_PORT="${MAILPIT_SMTP_PORT:-1025}"
export TYPESENSE_PORT="${TYPESENSE_PORT:-8108}"
PROJECT="${SMC_LOCAL_PROJECT:-smartupcms-local}"
export PROJECT_NAME="$PROJECT"
# The development credentials of docker-compose.yml and .env.example; the local database is not reachable from outside
# the machine's Docker network unless the host publishes DB_PORT.
DB_PASSWORD_LOCAL="${DB_PASSWORD:-smartupcms_local_dev}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mError: %s\033[0m\n' "$*" >&2; exit 1; }

compose() {
    local files=(-f "$ROOT/docker-compose.yml" -f "$ROOT/scripts/dev/local.compose.yml")
    docker compose -p "$PROJECT" --project-directory "$ROOT" "${files[@]}" "$@"
}

require_tools() {
    command -v docker >/dev/null 2>&1 || fail "Docker is not installed (https://docs.docker.com/get-docker/)."
    docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is missing (the 'docker compose' command)."
    docker info >/dev/null 2>&1 || fail "The Docker daemon does not answer: start Docker first."
    if [ "$COMMAND" = "infra" ]; then return; fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA="$(command -v java || true)"; fi
    [ -n "$JAVA" ] || fail "Java is not found: install JDK 25 and put it on PATH or set JAVA_HOME."
    local java_major
    java_major="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
    [ "${java_major:-0}" -ge 25 ] || fail "JDK 25 or newer is required, found: ${java_major:-unknown} ($JAVA)."
    if [ -z "${JAVA_HOME:-}" ]; then export JAVA_HOME="$(cd "$(dirname "$JAVA")/.." && pwd)"; fi
    if [ "$COMMAND" = "up" ]; then
        command -v node >/dev/null 2>&1 || fail "Node.js is not found: install the version in .node-version."
        command -v npm >/dev/null 2>&1 || fail "npm is not found (it comes with Node.js)."
        local want have
        want="$(cut -d. -f1 < "$ROOT/.node-version")"
        have="$(node -p 'process.versions.node.split(".")[0]')"
        [ "$have" = "$want" ] || echo "Warning: Node.js $have found, .node-version asks for $want." >&2
    fi
}

admin_password() {
    mkdir -p "$STATE"
    if [ ! -s "$STATE/admin-password" ]; then
        # 20 characters, the longest the password policy accepts; upper, lower, digit and sign are all present.
        printf 'Dv1!%s' "$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')" > "$STATE/admin-password"
        chmod 600 "$STATE/admin-password"
    fi
}

start_infra() {
    say "Infrastructure (Compose project $PROJECT): PostgreSQL on $DB_PORT, Mailpit on $MAILPIT_HTTP_PORT"
    local services=(postgres mailpit)
    if [ "$SEARCH" = 1 ]; then services+=(typesense); fi
    compose up -d --wait --wait-timeout 300 "${services[@]}"
}

# The runnable jar has the exec classifier; the plain and testkit jars next to it are libraries.
server_jar() { ls "$ROOT"/apps/server/target/server-*-exec.jar 2>/dev/null | head -n 1 || true; }

build_server() {
    if [ "$SKIP_BUILD" = 1 ] && [ -n "$(server_jar)" ]; then return; fi
    say "Building the server (Maven wrapper, tests skipped)"
    (cd "$ROOT" && ./mvnw -B -q -DskipTests -Djacoco.skip=true -pl apps/server -am package)
    [ -n "$(server_jar)" ] || fail "The build produced no apps/server/target/server-*-exec.jar."
}

# The environment the server reads (ADR-0027 names), for the host processes.
server_env() {
    export DB_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/smartupcms"
    export DB_USER="smartupcms" DB_PASSWORD="$DB_PASSWORD_LOCAL"
    export WAREHOUSE_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/smartupcms_dwh"
    export WAREHOUSE_USERNAME="smartupcms" WAREHOUSE_PASSWORD="$DB_PASSWORD_LOCAL" WAREHOUSE_CONNECT_TIMEOUT="5s"
    export SMC_STORAGE_LOCAL_PATH="$STATE/storage" SMC_AUDIT_ARCHIVE_LOCAL_PATH="$STATE/audit-archive"
    export SMC_BACKUP_STATUS_FILE="$STATE/backup/status.json"
    export SMC_PUBLIC_URL="http://localhost:$WEB_PORT"
    export SMC_PROVIDER_MAIL="smtp" SMTP_HOST="127.0.0.1" SMTP_PORT="$MAILPIT_SMTP_PORT" SMTP_AUTH="false" SMTP_STARTTLS="false"
    export SMC_MAIL_FROM="no-reply@localhost"
    if [ "$SEARCH" = 1 ]; then
        export SMC_TYPESENSE_ENABLED="true" SMC_TYPESENSE_URL="http://127.0.0.1:$TYPESENSE_PORT"
    else
        export SMC_TYPESENSE_ENABLED="false"
    fi
}

migrate() {
    say "Migrations: pg-dwh, then the main database"
    local jar
    jar="$(server_jar)"
    server_env
    # As the migrator role of the PostgreSQL image, the same steps as the migrate service of docker-compose.yml.
    DB_USER="smartupcms_migrator" SMC_MIGRATE_SCOPE="warehouse" "$JAVA" -cp "$jar" \
        -Dloader.main=com.smartup24.cms.instance.warehouse.migration.MigrateMain \
        org.springframework.boot.loader.launch.PropertiesLauncher
    DB_USER="smartupcms_migrator" "$JAVA" -jar "$jar" --spring.profiles.active=migrate > "$STATE/migrate.log" 2>&1 \
        || { tail -n 60 "$STATE/migrate.log"; fail "Migrations failed (full log: .local/migrate.log)."; }
}

wait_http() {
    local name="$1" url="$2" pid_file="$3" seconds="$4" log="$5"
    local start=$SECONDS
    until curl -fsS -o /dev/null "$url" 2>/dev/null; do
        if ! kill -0 "$(cat "$pid_file")" 2>/dev/null; then
            tail -n 80 "$log" >&2
            fail "$name stopped before it answered (full log: ${log#"$ROOT"/})."
        fi
        if [ $((SECONDS - start)) -ge "$seconds" ]; then
            tail -n 80 "$log" >&2
            fail "$name did not answer at $url within ${seconds}s."
        fi
        sleep 2
    done
}

start_server() {
    local profiles="dev"
    if [ "$DEMO" = 1 ]; then profiles="dev,demo"; fi
    say "Server ($profiles) on $SERVER_PORT, management on $MANAGEMENT_PORT"
    server_env
    export SMC_INSTANCE_ADMIN_PASSWORD
    SMC_INSTANCE_ADMIN_PASSWORD="$(cat "$STATE/admin-password")"
    local args="--server.port=$SERVER_PORT --management.server.port=$MANAGEMENT_PORT"
    # shellcheck disable=SC2086 # the arguments are two words on purpose
    nohup "$JAVA" -jar "$(server_jar)" --spring.profiles.active="$profiles" $args \
        > "$STATE/server.log" 2>&1 &
    echo $! > "$STATE/server.pid"
    unset SMC_INSTANCE_ADMIN_PASSWORD
    wait_http "The server" "http://127.0.0.1:$MANAGEMENT_PORT/actuator/health/readiness" "$STATE/server.pid" 300 \
        "$STATE/server.log"
}

install_web() {
    if [ ! -d "$ROOT/apps/web/node_modules/@angular/cli" ]; then
        (cd "$ROOT/apps/web" && npm ci --no-audit --no-fund --loglevel=error)
    fi
}

start_web() {
    say "Web dev server on http://$WEB_HOST:$WEB_PORT (API proxied to $SERVER_PORT)"
    # The proxy of apps/web/proxy.conf.json with the port of this run.
    cat > "$STATE/proxy.conf.json" <<EOF
{ "/api": { "target": "http://127.0.0.1:$SERVER_PORT", "secure": false, "changeOrigin": true } }
EOF
    (cd "$ROOT/apps/web" && nohup node node_modules/@angular/cli/bin/ng.js serve --host "$WEB_HOST" \
        --port "$WEB_PORT" --proxy-config "$STATE/proxy.conf.json" > "$STATE/web.log" 2>&1 \
        & echo $! > "$STATE/web.pid")
    wait_http "The web dev server" "http://localhost:$WEB_PORT/" "$STATE/web.pid" 300 "$STATE/web.log"
    wait_http "The API through the web origin" "http://localhost:$WEB_PORT/api/v1/i18n/languages" "$STATE/server.pid" \
        60 "$STATE/server.log"
}

stop_process() {
    local name="$1" pid_file="$STATE/$1.pid"
    [ -f "$pid_file" ] || return 0
    local pid
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" 2>/dev/null; then
        # The children first (npm, Maven and node start their own), then the process itself.
        pkill -TERM -P "$pid" 2>/dev/null || true
        kill -TERM "$pid" 2>/dev/null || true
        for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
        kill -KILL "$pid" 2>/dev/null || true
        echo "Stopped $name (pid $pid)."
    fi
    rm -f "$pid_file"
}

stop_apps() { stop_process web; stop_process server; }

print_summary() {
    cat <<EOF

SmartupCMS is running.
  UI:          http://localhost:$WEB_PORT
  Sign in:     login "admin", password in .local/admin-password (cat .local/admin-password);
               the first sign-in asks for a new password.
  Mail stub:   http://localhost:$MAILPIT_HTTP_PORT (invitations and reset links of new users)
  API health:  http://127.0.0.1:$MANAGEMENT_PORT/actuator/health
  Logs:        .local/server.log, .local/web.log
  Stop:        scripts/dev/run-local.sh down   (add --volumes to delete the data)
EOF
    if [ "$DEMO" = 1 ]; then
        echo "  Demo data:   users demo.anna, demo.bobur, demo.dilnoza get their invitation in the mail stub."
    fi
}

case "$COMMAND" in
    status)
        compose ps
        for name in server web; do
            if [ -f "$STATE/$name.pid" ] && kill -0 "$(cat "$STATE/$name.pid")" 2>/dev/null; then
                echo "$name: running (pid $(cat "$STATE/$name.pid"))"
            else
                echo "$name: stopped"
            fi
        done
        ;;
    down)
        stop_apps
        if [ "$VOLUMES" = 1 ]; then
            compose down --volumes --remove-orphans
            rm -rf "$STATE"
        else
            compose down --remove-orphans
        fi
        ;;
    infra)
        require_tools
        start_infra
        ;;
    migrate)
        require_tools
        start_infra
        build_server
        mkdir -p "$STATE"
        migrate
        ;;
    up)
        require_tools
        admin_password
        stop_apps
        # npm ci and the infrastructure run while Maven builds: a fresh clone waits for the longest of them only.
        install_web > "$STATE/npm-ci.log" 2>&1 &
        npm_pid=$!
        start_infra
        build_server
        migrate
        start_server
        wait "$npm_pid" || { tail -n 40 "$STATE/npm-ci.log"; fail "npm ci failed (full log: .local/npm-ci.log)."; }
        start_web
        print_summary
        if [ "$DETACH" = 1 ]; then exit 0; fi
        trap 'echo; stop_apps; echo "The infrastructure keeps running: scripts/dev/run-local.sh down stops it."; exit 0' INT TERM
        echo "  Ctrl+C stops the server and the web dev server."
        while kill -0 "$(cat "$STATE/server.pid")" 2>/dev/null && kill -0 "$(cat "$STATE/web.pid")" 2>/dev/null; do
            sleep 2
        done
        stop_apps
        fail "The server or the web dev server stopped; see .local/server.log and .local/web.log."
        ;;
esac
