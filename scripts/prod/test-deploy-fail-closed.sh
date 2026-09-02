#!/usr/bin/env bash
# Behavioural regression: a failed pre-deploy backup must prevent migrations.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$(mktemp -d)"
fake_log="${test_dir}/docker.log"
output_log="${test_dir}/deploy.log"
trap 'rm -rf "$test_dir"' EXIT

docker() {
    printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"

    if [[ "$*" == *"ps -a -q postgres" ]]; then
        printf '%s\n' "fake-postgres"
        return 0
    fi
    if [[ "$*" == *"run --rm --no-deps -e BACKUP_RUN_ONCE=true backup"* ]]; then
        return 42
    fi
    return 0
}
export -f docker
export FAKE_DOCKER_LOG="$fake_log"

set +e
(
    cd "$repo_root"
    COMPOSE_FILE="deploy/compose/docker-compose.prod.yml" \
    ENV_FILE="scripts/prod/release-config.test.env" \
    BACKUP_DIR="$test_dir/backups" \
    bash scripts/prod/deploy.sh
) >"$output_log" 2>&1
deploy_status=$?
set -e

if [[ $deploy_status -eq 0 ]]; then
    echo "Expected deployment to fail when backup fails." >&2
    cat "$output_log" >&2
    exit 1
fi

if grep -q 'run --rm migrate' "$fake_log"; then
    echo "Migration ran after a failed backup." >&2
    cat "$fake_log" >&2
    exit 1
fi

if ! grep -q 'run --rm --no-deps -e BACKUP_RUN_ONCE=true backup' "$fake_log"; then
    echo "The pre-deploy backup was not attempted." >&2
    cat "$fake_log" >&2
    exit 1
fi

echo "Fail-closed deployment behaviour verified."
