#!/usr/bin/env bash
# Plan 10/10, item 7.4 (ADR-0034): the security headers of the web origin, checked on a running container.
# The same rules as scripts/security/test-security-headers.ps1 (Mozilla HTTP Observatory tests that run
# offline); the online grade (A+) is a release step, docs/ops/production-launch-checklist.md.
# Usage: scripts/security/test-security-headers.sh [base-url]   (default http://localhost:4200)
set -euo pipefail

base_url="${1:-${SECURITY_HEADERS_BASE_URL:-http://localhost:4200}}"
base_url="${base_url%/}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
value=""

fail() {
    echo "FAIL $1: $2" >&2
    failures=$((failures + 1))
}

# fetch PATH -> $work/headers (lower-case names), $work/body, prints the status code
fetch() {
    curl -sS -o "$work/body" -D "$work/raw" -w '%{http_code}' --max-time 30 "$base_url$1"
    tr -d '\r' < "$work/raw" | awk -F': *' 'NF > 1 { name = tolower($1); sub(/^[^:]*: */, ""); print name "\t" $0 }' > "$work/headers"
}

# single PATH NAME -> sets $value to a header that must be present exactly once. It runs in this shell
# (never inside $(...)), so the failures it records count.
single() {
    local count
    value=""
    count="$(awk -F'\t' -v n="$2" '$1 == n' "$work/headers" | wc -l | tr -d ' ')"
    if [ "$count" -eq 0 ]; then fail "$1" "missing header $2"; return 0; fi
    if [ "$count" -gt 1 ]; then fail "$1" "header $2 is sent $count times"; fi
    value="$(awk -F'\t' -v n="$2" '$1 == n { print $2; exit }' "$work/headers")"
}

# directive POLICY NAME -> the sources of one CSP directive
directive() {
    printf '%s' "$1" | tr ';' '\n' | sed 's/^ *//' | awk -v n="$2" '$1 == n { $1 = ""; sub(/^ /, ""); print; exit }'
}

has_directive() {
    printf '%s' "$1" | tr ';' '\n' | sed 's/^ *//' | awk -v n="$2" '$1 == n { found = 1 } END { exit !found }'
}

common() {
    local path="$1" policy max_age
    single "$path" strict-transport-security
    if [ -n "$value" ]; then
        max_age="$(printf '%s' "$value" | sed -n 's/.*max-age=\([0-9]*\).*/\1/p')"
        if [ -z "$max_age" ] || [ "$max_age" -lt 31536000 ]; then fail "$path" "HSTS max-age below one year: $value"; fi
        printf '%s' "$value" | grep -qi 'includeSubDomains' || fail "$path" "HSTS without includeSubDomains: $value"
    fi
    single "$path" x-content-type-options
    [ -z "$value" ] || [ "$value" = nosniff ] || fail "$path" "X-Content-Type-Options is '$value'"
    single "$path" x-frame-options
    case "$value" in ''|DENY|SAMEORIGIN) ;; *) fail "$path" "X-Frame-Options is '$value'" ;; esac
    single "$path" referrer-policy
    case "$value" in ''|no-referrer|same-origin|strict-origin|strict-origin-when-cross-origin) ;;
        *) fail "$path" "Referrer-Policy leaks the address: '$value'" ;; esac
    single "$path" permissions-policy
    [ -z "$value" ] || printf '%s' "$value" | grep -q 'camera=()' || fail "$path" "Permissions-Policy does not deny the camera"
    if awk -F'\t' '$1 == "server" && $2 ~ /[0-9]/' "$work/headers" | grep -q .; then fail "$path" "Server header reveals a version"; fi
    if awk -F'\t' '$1 == "x-powered-by"' "$work/headers" | grep -q .; then fail "$path" "X-Powered-By is sent"; fi
    if awk -F'\t' '$1 == "access-control-allow-origin" && $2 == "*"' "$work/headers" | grep -q .; then fail "$path" "CORS allows every origin"; fi
    single "$path" content-security-policy
    policy="$value"
    if [ -n "$policy" ]; then
        case "$(directive "$policy" frame-ancestors)" in "'none'"|"'self'") ;;
            *) fail "$path" "CSP frame-ancestors is not 'none'/'self'" ;; esac
        has_directive "$policy" default-src || fail "$path" "CSP without default-src"
    fi
    printf '%s' "$policy" > "$work/policy"
}

wide_source() {
    case "$1" in '*'|http:|https:|data:|blob:|ws:|wss:) return 0 ;; *) return 1 ;; esac
}

document_policy() {
    local path="$1" policy scripts source name
    policy="$(cat "$work/policy")"
    [ -n "$policy" ] || return 0
    if has_directive "$policy" script-src; then scripts="$(directive "$policy" script-src)"; else scripts="$(directive "$policy" default-src)"; fi
    for source in $scripts; do
        if [ "$source" = "'unsafe-inline'" ] || [ "$source" = "'unsafe-eval'" ] || wide_source "$source"; then
            fail "$path" "CSP script-src allows $source"
        fi
    done
    for source in $(directive "$policy" default-src); do wide_source "$source" && fail "$path" "CSP default-src allows $source"; done
    [ "$(directive "$policy" object-src)" = "'none'" ] || fail "$path" "CSP object-src is not 'none'"
    for name in base-uri form-action frame-src; do has_directive "$policy" "$name" || fail "$path" "CSP without $name"; done
    for source in $(directive "$policy" frame-src); do wide_source "$source" && fail "$path" "CSP frame-src allows every $source source"; done
    if printf '%s' "$policy" | grep -Eq 'fonts\.googleapis\.com|fonts\.gstatic\.com'; then fail "$path" "CSP allows third-party font hosts; fonts are local"; fi
    if printf '%s' "$policy" | grep -Eq '(^|[ ;])http://'; then fail "$path" "CSP loads a source over plain http"; fi
    return 0
}

set -f
status="$(fetch /)"
[ "$status" = 200 ] || fail / "status $status"
common /
document_policy /
bundle="$(grep -o 'src="main-[A-Za-z0-9]*\.js"' "$work/body" | head -n 1 | sed 's/^src="//; s/"$//')"
[ -n "$bundle" ] || { echo "The SPA document names no main-*.js bundle." >&2; exit 1; }

status="$(fetch "/$bundle")"
[ "$status" = 200 ] || fail "/$bundle" "status $status"
single "/$bundle" content-type
printf '%s' "$value" | grep -q javascript || fail "/$bundle" "not served as JavaScript"
common "/$bundle"
document_policy "/$bundle"

status="$(fetch /healthz)"
[ "$status" = 200 ] || fail /healthz "status $status"
common /healthz
document_policy /healthz

status="$(fetch /api/v1/i18n/languages)"
[ "$status" = 200 ] || fail /api/v1/i18n/languages "status $status"
common /api/v1/i18n/languages

# Unauthenticated: the server answers with its problem document; nginx's own 404 page means the request
# never left the web container (an API path ending in .js must not hit the asset location).
for path in /api/v1/auth/me /api/v1/edge-probe.js; do
    status="$(fetch "$path")"
    [ "$status" = 401 ] || fail "$path" "status $status, expected the server's 401"
    single "$path" content-type
    printf '%s' "$value" | grep -q 'application/problem+json' || fail "$path" "answered by nginx, not the server"
    common "$path"
done

if [ "$failures" -gt 0 ]; then
    echo "Security headers of $base_url failed $failures check(s)." >&2
    exit 1
fi
echo "Security headers of $base_url passed."
