#!/usr/bin/env bash
# Настройка Vault для спайка. Выполняется с админ-токеном (после init/unseal).
# Шаг A — интеграция Nomad workload identity (JWT), шаги B-C — секреты демо-клиента.
set -euo pipefail

: "${VAULT_ADDR:?export VAULT_ADDR=https://10.0.0.11:8200}"
NOMAD_ADDR="${NOMAD_ADDR:-http://10.0.0.11:4646}"

echo "== A. JWT-auth для Nomad workload identity =="
# Синтаксис сверить с документацией установленных версий Nomad/Vault —
# механизм workload identity менялся между версиями.
vault auth enable -path=jwt-nomad jwt || true
vault write auth/jwt-nomad/config \
  jwks_url="${NOMAD_ADDR}/.well-known/jwks.json" \
  jwt_supported_algs="RS256,EdDSA" \
  default_role="nomad-workloads"

vault write auth/jwt-nomad/role/nomad-workloads \
  role_type="jwt" \
  bound_audiences="vault.io" \
  user_claim="/nomad_job_id" \
  user_claim_json_pointer=true \
  claim_mappings='{"nomad_namespace":"nomad_namespace","nomad_job_id":"nomad_job_id"}' \
  token_type="service" \
  token_policies="instance-demo" \
  token_period="30m"

echo "== B. KV v2 и секрет демо-клиента =="
vault secrets enable -path=kv kv-v2 || true
PG_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
vault kv put kv/instances/demo/pg password="${PG_PASSWORD}"
echo "   пароль PG сгенерирован и записан в kv/instances/demo/pg (в консоль не выводим)"

echo "== C. Политика изоляции =="
vault policy write instance-demo "$(dirname "$0")/policy-instance-demo.hcl"

echo "== Готово. Проверка: vault kv get kv/instances/demo/pg (только метаданные) =="
vault kv metadata get kv/instances/demo/pg
