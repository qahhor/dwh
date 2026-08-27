#!/usr/bin/env bash
# День 1: проверка кластера. Критерий T-001.
set -euo pipefail

echo "== Nomad: серверы =="
ALIVE=$(nomad server members | grep -c alive || true)
echo "   alive: ${ALIVE} (нужно 3)"
[ "${ALIVE}" -eq 3 ] || { echo "FAIL: кворум не собран"; exit 1; }

echo "== Nomad: клиентские узлы =="
nomad node status
nomad node status -verbose | grep -q "client-nodes" \
  || { echo "FAIL: нет узла с node_class=client-nodes"; exit 1; }

echo "== Vault =="
vault status | grep -E "Sealed|HA Mode|Raft"
vault status | grep -q "Sealed.*false" || { echo "FAIL: Vault запечатан"; exit 1; }

echo "== Тестовый job =="
cat > /tmp/spike-hello.nomad.hcl <<'EOF'
job "spike-hello" {
  datacenters = ["dc1"]
  type = "batch"
  group "g" {
    task "t" {
      driver = "docker"
      config { image = "alpine:3", command = "echo", args = ["spike-hello-ok"] }
      resources { cpu = 50, memory = 32 }
    }
  }
}
EOF
nomad job run /tmp/spike-hello.nomad.hcl
sleep 5
nomad job status spike-hello | grep -q "complete" \
  && echo "OK: тестовый job выполнен" \
  || { echo "FAIL: тестовый job не завершился"; exit 1; }
nomad job stop -purge spike-hello >/dev/null

echo ""
echo "=== T-001 КРИТЕРИЙ ВЫПОЛНЕН: кластер готов ==="
