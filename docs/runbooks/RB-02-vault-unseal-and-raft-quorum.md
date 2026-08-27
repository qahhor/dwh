# Runbook RB-02: Аварийный Unseal Vault и восстановление кворума Raft

**Версия:** 1.0
**Область действия:** Центральный кластер HashiCorp Vault (3 узла, Raft Integrated Storage).
**Целевые метрики:** RTO ≤ 15 минут.
**Актор:** Инженер безопасности / Дежурный инфраструктурный инженер (держатели ключей Шамира).

---

## 1. Симптомы и детекция

1. Алерт в Telegram: `[FATAL] VaultClusterSealed` или `[CRITICAL] VaultQuorumLost`.
2. Приложения не могут получить секреты БД и токены подписи лицензий:
   - В логах `app`: `org.springframework.vault.VaultException: Status 503 Service Unavailable (Vault is sealed)`.

---

## 2. Диагностика состояния кластера (≤ 3 минуты)

1. Проверить статус узлов Vault:
   ```bash
   vault status -address=https://vault-01.smartup.internal:8200
   vault status -address=https://vault-02.smartup.internal:8200
   vault status -address=https://vault-03.smartup.internal:8200
   ```
2. Проверить статус Raft-кворума:
   ```bash
   vault operator raft list-peers -address=https://vault-01.smartup.internal:8200
   ```

---

## 3. Процедура аварийного Unseal (Схема Шамира 3 из 5)

Если узел перезагрузился и находится в состоянии `Sealed: true`:
1. Запросить ключи разблокировки у 3 назначенных держателей ключей (Key Custodians).
2. На каждом узле кластера последовательно выполнить ввод 3 ключей:
   ```bash
   export VAULT_ADDR="https://vault-01.smartup.internal:8200"
   vault operator unseal <unseal_key_1>
   vault operator unseal <unseal_key_2>
   vault operator unseal <unseal_key_3>
   ```
3. Повторить операцию для `vault-02` и `vault-03`.
4. Убедиться, что выбран лидер: `vault status` показывает `HA Mode: active` на одном из узлов и `HA Mode: standby` на остальных.

---

## 4. Восстановление кворума Raft при потере узла

Если 1 из 3 узлов физически уничтожен:
1. Поднять новый узел `vault-04` с чистым диском.
2. Присоединить новый узел к существующему лидеру:
   ```bash
   vault operator raft join -address=https://vault-04.smartup.internal:8200 https://vault-01.smartup.internal:8200
   ```
3. Выполнить unseal на `vault-04` (шаг 3).
4. Удалить мёртвый узел из списка пиров:
   ```bash
   vault operator raft remove-peer -peer-id=vault-02
   ```

---

## 5. Верификация

1. Проверить доступность движка транзитного шифрования:
   ```bash
   vault read transit/keys/license-signer-2026-v1
   ```
2. Убедиться, что алерты в Grafana перешли в статус `Resolved`.
