#!/bin/sh
set -eu

status="${1:-}"
completed_at="${2:-}"
failure_code="${3:-}"
status_file="${BACKUP_STATUS_FILE:-/status/status.json}"

case "$status" in
    NEVER|SUCCESS|FAILED) ;;
    *) echo "invalid backup status" >&2; exit 64 ;;
esac

if [ "$status" != "NEVER" ]; then
    printf '%s' "$completed_at" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' \
        || { echo "invalid completion timestamp" >&2; exit 64; }
fi

if [ -n "$failure_code" ]; then
    printf '%s' "$failure_code" | grep -Eq '^[A-Z][A-Z0-9_]{0,63}$' \
        || { echo "invalid failure code" >&2; exit 64; }
fi

status_dir="$(dirname "$status_file")"
mkdir -p "$status_dir"
umask 077
temporary="$(mktemp "${status_dir}/.status.XXXXXX")"
trap 'rm -f "$temporary"' EXIT HUP INT TERM

case "$status" in
    NEVER)
        printf '{"status":"NEVER","completedAt":null,"failureCode":null}\n' > "$temporary"
        ;;
    SUCCESS)
        printf '{"status":"SUCCESS","completedAt":"%s","failureCode":null}\n' "$completed_at" > "$temporary"
        ;;
    FAILED)
        [ -n "$failure_code" ] || failure_code="UNKNOWN"
        printf '{"status":"FAILED","completedAt":"%s","failureCode":"%s"}\n' \
            "$completed_at" "$failure_code" > "$temporary"
        ;;
esac

chmod 0600 "$temporary"
mv -f "$temporary" "$status_file"
trap - EXIT HUP INT TERM
