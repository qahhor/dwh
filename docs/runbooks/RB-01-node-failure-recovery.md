# Runbook RB-01: Восстановление после отказа физического узла клиента

**Версия:** 1.0
**Область действия:** Экземпляр клиента в Nomad (Single-tenant).
**Целевые метрики:** RTO ≤ 1 час, RPO ≤ 15 минут (WAL-G S3).
**Актор:** Дежурный инженер эксплуатации.

---

## 1. Симптомы и детекция

1. Алерт в Telegram-канале флота: `[CRITICAL] InstanceUnreachable: client-042 (Node down)`.
2. В Control Plane дашборде статус клиента перешёл в `DISCONNECTED` (heartbeat отсутствует > 5 минут).
3. Grafana: метрики узла `node-042` показывают `NodeExporterDown`.

---

## 2. Диагностика (≤ 5 минут)

1. Проверить статус узла в Nomad CLI:
   ```bash
   nomad node status -filter 'Meta.client_code == "client-042"'
   ```
2. Если узел `ineligible` или `dead`:
   - Stateless-компоненты (`app`, `web`, `alloy`) автоматически перепланируются на резервный узел кластера (если есть кворум);
   - Stateful-компоненты (`pg-oltp`, `garage`) требуют монтирования томов или восстановления из S3-бэкапа.

---

## 3. Процедура восстановления (RTO ≤ 1 час)

### Шаг 3.1. Перевод клиента на резервный сервер
Если физический сервер клиента не подлежит быстрому перезапуску:
1. Выбрать свободный целевой сервер (например, `node-standby-03`).
2. Привязать метаданные клиента к новому узлу в Consul:
   ```bash
   consul kv put "fleet/instances/client-042/node_id" "node-standby-03"
   ```

### Шаг 3.2. Восстановление базы данных PostgreSQL из WAL-G бэкапа
1. Запустить разовый restore-job в Nomad:
   ```bash
   nomad job dispatch -meta client_code="client-042" -meta target_node="node-standby-03" dwh-db-restore
   ```
2. Что выполняет restore-job:
   - Создаёт чистую директорию данных PostgreSQL;
   - Скачивает последний полный basebackup из S3 Garage (`wal-g backup-fetch /var/lib/postgresql/data LATEST`);
   - Настраивает `recovery.signal` и `restore_command = 'wal-g wal-fetch "%f" "%p"'`;
   - Накатывает инкрементальные WAL-логи до последней доступной транзакции (RPO ≤ 15 мин);
   - Успешно завершается и запускает постоянный сервис `pg-oltp`.

### Шаг 3.3. Запуск приложения и проверка health-check
1. Перезапустить job клиента в Nomad:
   ```bash
   nomad job run -var="client_code=client-042" deploy/nomad/client-instance.nomad.hcl
   ```
2. Убедиться, что `app` и `web` перешли в статус `running` (зелёный).
3. Проверить `/actuator/health` экземпляра.

---

## 4. Верификация и закрытие инцидента

1. Убедиться в получении свежего Heartbeat в Control Plane:
   ```bash
   curl -s -k https://cp.smartup.internal/api/v1/internal/instances/client-042/status
   ```
2. Выполнить тестовый вход под технической сервисной учётной записью.
3. Поставить отметку о закрытии инцидента в Grafana OnCall.
