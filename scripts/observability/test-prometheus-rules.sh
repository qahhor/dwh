#!/usr/bin/env bash
# Plan 10/10, item 7.3 (docs/ops/slo.md): promtool checks the example configuration and the rules of
# deploy/observability/prometheus and runs their unit tests, and every alert has a test. The same checks as
# test-prometheus-rules.ps1 (which also validates the Grafana dashboards) for Linux and macOS.
set -euo pipefail

image='prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e'
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
prometheus="$root/deploy/observability/prometheus"

cd "$prometheus"
rules=(rules/*.yml)
tests=(tests/*.yml)

status=0
for alert in $(sed -n 's/^[[:space:]]*-[[:space:]]*alert:[[:space:]]*\([^[:space:]]*\)[[:space:]]*$/\1/p' "${rules[@]}"); do
  if ! grep -Eq "^[[:space:]]*alertname:[[:space:]]*${alert}[[:space:]]*$" "${tests[@]}"; then
    echo "Alert has no unit test in deploy/observability/prometheus/tests: $alert" >&2
    status=1
  fi
done
[ "$status" -eq 0 ] || exit 1

# Git Bash on Windows rewrites /work into a Windows path: mount the Windows form and turn the rewriting off.
mount="$prometheus"
if command -v cygpath >/dev/null 2>&1; then
  mount="$(cygpath -m "$prometheus")"
  export MSYS_NO_PATHCONV=1
fi
promtool() {
  docker run --rm -v "$mount:/work:ro" -w /work --entrypoint promtool "$image" "$@"
}

promtool check config prometheus.yml
promtool check rules "${rules[@]}"
promtool test rules "${tests[@]}"
echo "Prometheus rules passed (${#tests[@]} test files)."
