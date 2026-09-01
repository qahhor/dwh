# Control Plane Instance API v1

**Статус:** реализованный контракт Fleet Foundation, 2026-09-01

**Base path:** `/api/v1`
**Формат:** JSON; ошибки — `application/problem+json`

Этот документ описывает только существующий HTTP-контракт регистрации экземпляра,
его runtime-связи с Control Plane и операторских действий, которые формируют desired
state. Регистрация проверенного release выполняется доверенным build-процессом через
внутренний `CpReleaseService`; публичного HTTP endpoint для загрузки release manifest
в текущем срезе нет.

Источники контракта:

- `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceApiController.java`;
- `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/api/*`;
- `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpFleetController.java`;
- `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpTargetController.java`;
- `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/release/CpReleaseController.java`;
- `apps/control-plane/src/main/resources/application.yml`.

## 1. Границы доверия и токены

| Секрет | Кто получает | Назначение | Срок и отзыв |
|---|---|---|---|
| Operator session cookie | сотрудник Control Plane после login | операторские endpoints | управляется сессией; изменяющие запросы требуют CSRF |
| `enrollmentToken` | оператор видит один раз при регистрации | единственный обмен на runtime credential | 15 минут, строго одноразовый |
| Runtime `credential` | экземпляр получает при enrollment/rotation | `X-Instance-Token` для instance endpoints | действует до явного отзыва; при rotation прежний credential живёт ещё 24 часа |

Enrollment token и runtime credential не взаимозаменяемы. Control Plane хранит только
SHA-256 hash. Сырые значения нельзя писать в логи, URL, query string, browser storage,
тестовые отчёты или скриншоты. Передача разрешена только по HTTPS.

Instance-authenticated endpoints:

```http
X-Instance-Token: <runtime-credential>
Content-Type: application/json
```

Операторские endpoints используют session cookie. Для `POST`/`PUT` клиент передаёт
double-submit CSRF header `X-XSRF-TOKEN`, соответствующий cookie `XSRF-TOKEN`.

## 2. Общие ограничения

- Для каждого `POST /api/v1/instances/*` тело ограничено **16 384 байт** как при
  известном `Content-Length`, так и при chunked transfer.
- Heartbeat ограничен **двумя запросами за одну минуту на instance**. При `429`
  ответ содержит `Retry-After: 60` при конфигурации по умолчанию.
- Неизвестные JSON-поля запрещены (`fail-on-unknown-properties: true`).
- Все даты и время — ISO-8601 UTC, например `2026-09-01T08:00:00Z`.
- Каждый ответ содержит `X-Trace-Id`; этот же идентификатор попадает в problem body.
- Значения enum чувствительны к регистру.

Стандартная ошибка:

```json
{
  "type": "https://api.dwh.internal/errors/instance_credential_invalid",
  "title": "Unauthorized",
  "status": 401,
  "errorCode": "instance_credential_invalid",
  "detail": "Instance credential is invalid, expired or revoked",
  "instance": "/api/v1/instances/heartbeat",
  "traceId": "0123456789abcdef0123456789abcdef"
}
```

## 3. Регистрация и credentials

### 3.1 `POST /api/v1/instances` — зарегистрировать экземпляр

Требуется operator role `cp-admin` и CSRF. Ответ содержит одноразовый secret.

| Поле запроса | Тип | Ограничение |
|---|---|---|
| `clientCode` | string | обязательно; клиент должен существовать |
| `environment` | string | `production`, `staging` или `dev` |
| `url` | URI | абсолютный `http`/`https` URI с host |
| `deploymentMode` | string | `MANAGED_CLOUD` или `CUSTOMER_HOSTED` |
| `jurisdiction` | string | 1–64 символа |
| `cloudProvider` | string | 1–64 символа |
| `storageProvider` | string | 1–64 символа |
| `edgeProvider` | string/null | до 64 символов; null разрешён только для customer-hosted |
| `supportTier` | string | 1–64 символа |

Для `MANAGED_CLOUD` принимается только утверждённое размещение:
`EU / HETZNER / CLOUDFLARE_R2 / CLOUDFLARE / MANAGED_995`. Для
`CUSTOMER_HOSTED` провайдеры задаются клиентом, а `supportTier` равен
`CUSTOMER_HOSTED_SUPPORT`.

```json
{
  "clientCode": "example-client",
  "environment": "production",
  "url": "https://example-client.invalid",
  "deploymentMode": "MANAGED_CLOUD",
  "jurisdiction": "EU",
  "cloudProvider": "HETZNER",
  "storageProvider": "CLOUDFLARE_R2",
  "edgeProvider": "CLOUDFLARE",
  "supportTier": "MANAGED_995"
}
```

`201 Created`:

| Поле ответа | Тип | Значение |
|---|---|---|
| `instanceId` | integer | созданный instance |
| `enrollmentToken` | string | одноразовый secret, показывается один раз |
| `expiresAt` | instant | момент истечения через 15 минут |

Ошибки: `400 validation_failed`, `404 client_not_found`,
`422 instance_placement_invalid`, `401 unauthorized`, `403 http_403`.

### 3.2 `POST /api/v1/instances/enroll` — обменять enrollment token

Endpoint публичен только в смысле отсутствия предварительной instance identity.
Знание одноразового enrollment token является credential для этого обмена.

```json
{ "enrollmentToken": "<one-time-enrollment-token>" }
```

`enrollmentToken`: непустая строка, максимум 128 символов.

`200 OK`:

```json
{
  "instanceId": 42,
  "credential": "<runtime-credential>"
}
```

Первый успешный запрос атомарно помечает enrollment token использованным. Повтор,
истёкший или неизвестный token возвращает `401 instance_enrollment_invalid`.

### 3.3 `POST /api/v1/instances/credentials/rotate`

Требуется действующий `X-Instance-Token`; тело отсутствует.

`200 OK`:

| Поле ответа | Тип | Значение |
|---|---|---|
| `instanceId` | integer | instance из authenticated principal |
| `credential` | string | новый runtime credential |
| `previousValidUntil` | instant | прежний credential действителен ещё 24 часа |

Ошибка: `401 instance_credential_invalid`.

### 3.4 `POST /api/v1/instances/{instanceId}/credentials/{credentialId}/revoke`

Требуется `cp-admin` и CSRF. `instanceId` и `credentialId` — положительные integer
path parameters. Успех: `204 No Content`. Неизвестный или не принадлежащий instance
credential: `404 instance_credential_not_found`.

## 4. Heartbeat

### `POST /api/v1/instances/heartbeat`

Требуется runtime credential. Instance identity берётся только из credential; request
не содержит `instanceId` или `clientCode`.

Поля верхнего уровня:

| Поле | Тип | Ограничение |
|---|---|---|
| `appVersion` | string | обязательно, 1–64 |
| `schemaVersion` | string | обязательно, 1–32 |
| `releaseVersion` | string/null | до 64 |
| `configVersion` | string/null | до 64 |
| `components` | object/null | фиксированный allowlist ниже |
| `storage` | object/null | фиксированный allowlist ниже |
| `backup` | object/null | фиксированный allowlist ниже |
| `agents` | object/null | фиксированный allowlist ниже |
| `deploymentState` | string/null | состояние deployment из списка ниже |
| `capacity` | object/null | фиксированный allowlist ниже |

Вложенные поля:

| Объект | Поля |
|---|---|
| `components` | `app`, `database`, `typesense`, `objectStorage`: `UP`, `DEGRADED`, `DOWN`, `UNKNOWN` или null |
| `storage` | `usedBytes`, `quotaBytes`: integer ≥ 0 |
| `backup` | `lastCompletedAt`: instant/null; `status`: `UNKNOWN`, `UPLOADED`, `VERIFIED`, `FAILED` или null |
| `agents` | `tunnel`, `telemetry`: health enum или null |
| `capacity` | `activeUsers`, `outboxPending`, `outboxDeadLetter`: integer ≥ 0 |

`deploymentState`: `IDLE`, `REQUESTED`, `PREFLIGHT`, `PREFLIGHT_FAILED`,
`BACKUP_VERIFIED`, `BACKUP_FAILED`, `MIGRATING`, `DEPLOYING`, `VERIFYING`,
`SUCCEEDED`, `ROLLING_BACK`, `ROLLED_BACK`, `RECOVERY_REQUIRED` или `CANCELLED`.

```json
{
  "appVersion": "1.0.0",
  "schemaVersion": "006",
  "releaseVersion": "1.0.0",
  "configVersion": "config-17",
  "components": {
    "app": "UP",
    "database": "UP",
    "typesense": "UP",
    "objectStorage": "UP"
  },
  "storage": { "usedBytes": 1073741824, "quotaBytes": 10737418240 },
  "backup": { "lastCompletedAt": "2026-09-01T08:00:00Z", "status": "UPLOADED" },
  "agents": { "tunnel": "UP", "telemetry": "UP" },
  "deploymentState": "IDLE",
  "capacity": { "activeUsers": 3, "outboxPending": 0, "outboxDeadLetter": 0 }
}
```

`200 OK`:

| Поле | Тип | Значение |
|---|---|---|
| `accepted` | boolean | `true` после фиксации heartbeat |
| `instanceId` | integer | authenticated instance |
| `licenseStatus` | string | текущее состояние лицензии |
| `resourceProfile` | string | профиль ресурсов instance |
| `desiredGeneration` | integer | назначенная generation или 0 |

Ошибки: `400 validation_failed`, `400 request_malformed`,
`401 instance_credential_invalid`, `413 instance_payload_too_large`,
`429 instance_rate_limited`.

## 5. Отчёты о backup artifact

### 5.1 `POST /api/v1/instances/backup-reports`

Требуется runtime credential. Владение всегда определяется authenticated instance;
поля `instanceId` и `clientCode` запрещены и дают `400 request_malformed`.

| Поле | Тип | Ограничение |
|---|---|---|
| `backupId` | UUID | обязательно; глобальный idempotency key |
| `status` | string | `UPLOADED` или `FAILED` |
| `checksumSha256` | string/null | 64 lowercase hex; обязателен для `UPLOADED`, запрещён для `FAILED` |
| `durationSec` | integer/null | 0–86 400; null сохраняется как 0 |
| `completedAt` | instant | обязательно, не в будущем |
| `reasonCode` | string/null | `[a-z0-9_]{1,64}` |

```json
{
  "backupId": "11111111-1111-1111-1111-111111111111",
  "status": "UPLOADED",
  "checksumSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "durationSec": 19,
  "completedAt": "2026-09-01T08:00:00Z",
  "reasonCode": null
}
```

Успешная первая запись и byte-exact replay: `202 Accepted`, без тела. Повтор того
же `backupId` с другим instance или содержимым: `409 backup_report_conflict`.
Несогласованные `status`/checksum: `400 backup_report_invalid`.

`VERIFIED` не принимается от instance. Это состояние зарезервировано для доверенного
restore verifier. Публичного endpoint перехода `UPLOADED → VERIFIED` в текущем срезе
репозитория нет; до его появления UI не должен трактовать `UPLOADED` как проверенный.

### 5.2 `GET /api/v1/backup-reports?limit=50`

Операторская проекция для `cp-engineer` или `cp-admin`. `limit` по умолчанию 50,
сервер ограничивает диапазоном 1–500.

Каждый элемент `200 OK`:

| Поле | Тип |
|---|---|
| `backupId` | UUID |
| `instanceId` | integer |
| `clientCode` | string |
| `artifactStatus` | `UPLOADED`, `VERIFIED` или `FAILED` |
| `checksumSha256` | string/null |
| `durationSec` | integer |
| `reasonCode` | string/null |
| `completedAt` | instant |
| `receivedAt` | instant |
| `verifiedAt` | instant/null |

Эта проекция артефактов не заменяет legacy `GET /api/v1/backup-checks`, который
показывает отдельные результаты тестового восстановления.

## 6. Release и desired state

### 6.1 `GET /api/v1/releases`

Требуется `cp-engineer` или `cp-admin`. Возвращает массив:

| Поле | Тип/ограничение |
|---|---|
| `id` | UUID |
| `version` | SemVer, до 128 |
| `sourceCommit` | 40 lowercase hex |
| `manifestDigest` | `sha256:` + 64 lowercase hex |
| `manifestLocation` | абсолютный URI, до 2048 |
| `verificationBundleDigest` | sha256 digest |
| `configSchemaVersion` | string, до 64 |
| `minimumAgentVersion` | string, до 64 |
| `deploymentModes` | array: `MANAGED_CLOUD`, `CUSTOMER_HOSTED` |
| `status` | `READY` или `REVOKED` |
| `components` | массив component metadata |
| `createdAt` | instant |

Component: `name` (до 64), digest-pinned `imageReference` (до 1024),
`imageDigest`, `sbomDigest`, `provenanceDigest`, `minimumSchemaVersion` и
`maximumRollbackSchemaVersion` (последние два nullable, до 64).

### 6.2 `POST /api/v1/releases/{releaseId}/revoke`

Требуется `cp-admin` и CSRF. Body: `{ "reason": "<1..500 chars>" }`.
Успех и идемпотентный повтор возвращают `200` с release в статусе `REVOKED`.
Ошибки: `400 release_revoke_reason_invalid`, `404 release_not_found`,
`409 release_not_ready`, `409 release_state_conflict`.

### 6.3 `PUT /api/v1/instances/{instanceId}/target`

Требуется `cp-admin` и CSRF. Назначается только immutable release в состоянии
`READY`, совместимый с deployment mode instance.

| Поле запроса | Тип/ограничение |
|---|---|
| `releaseId` | UUID, обязательно |
| `configVersion` | string, 1–64 |
| `ring` | `R0`, `R1`, `R2`, `R3` |
| `maintenanceWindow.weekOfMonth` | integer 1–5 |
| `maintenanceWindow.dayOfWeek` | integer 1–7 (ISO: понедельник = 1) |
| `maintenanceWindow.start` | local time |
| `maintenanceWindow.durationMinutes` | integer 15–240 |
| `maintenanceWindow.timezone` | валидный IANA Zone ID, 1–64 |

`200 OK` возвращает: `instanceId`, монотонную `generation`, `releaseId`,
`releaseVersion`, `manifestDigest`, `manifestLocation`, `configVersion`, `ring`,
весь `maintenanceWindow`, `requestedBy`, `requestedAt`, а также фактические
`currentReleaseId`, `currentConfigVersion`, `currentGeneration` (поля current могут
быть null/0 до первого reconciliation).

Ошибки: `400 target_invalid`, `404 instance_not_found`,
`409 release_not_assignable`, `409 target_generation_exhausted`.

### 6.4 `GET /api/v1/instances/desired-state`

Требуется runtime credential. Request не принимает `instanceId`: target выбирается
только по authenticated principal.

- `204 No Content` — target ещё не назначен;
- `200 OK` — target существует.

| Поле ответа | Тип |
|---|---|
| `generation` | integer |
| `releaseId` | UUID |
| `releaseVersion` | string |
| `manifestDigest` | sha256 digest |
| `manifestLocation` | absolute URI |
| `configVersion` | string |
| `maintenanceWindow` | объект из раздела 6.3 |
| `allowedAction` | `APPLY_RELEASE` или `NONE` |

`NONE` возвращается только когда фактические generation, release и config совпадают
с target. Иначе разрешено только декларативное действие `APPLY_RELEASE`; произвольные
shell-команды Control Plane не передаёт.

## 7. Матрица стабильных ошибок

| HTTP | `errorCode` | Когда |
|---:|---|---|
| 400 | `validation_failed` | Bean Validation не прошла |
| 400 | `request_malformed` | JSON повреждён или содержит неизвестные поля |
| 400 | `backup_report_invalid` | status/checksum несовместимы |
| 400 | `target_invalid` | target или maintenance window некорректен |
| 401 | `instance_enrollment_invalid` | enrollment token неизвестен, истёк или уже использован |
| 401 | `instance_credential_invalid` | runtime credential отсутствует, истёк или отозван |
| 404 | `client_not_found` | clientCode регистрации не существует |
| 404 | `instance_not_found` | target назначается неизвестному instance |
| 409 | `backup_report_conflict` | тот же backupId связан с другим содержимым/instance |
| 409 | `release_not_assignable` | release отсутствует, отозван, не READY или несовместим |
| 409 | `target_generation_exhausted` | generation достигла bigint max |
| 413 | `instance_payload_too_large` | тело instance POST больше 16 KiB |
| 422 | `instance_placement_invalid` | deployment placement нарушает утверждённую матрицу |
| 429 | `instance_rate_limited` | превышен heartbeat limit |
| 500 | `internal_error` | непредвиденная серверная ошибка без раскрытия деталей |

## 8. Совместимость N/N-1

Control Plane обновляется раньше экземпляров и по архитектурному контракту должен
принимать tenant protocol **N и N-1**. В текущем репозитории опубликован только
`/api/v1`; отдельного negotiation header или второго protocol adapter не найдено.
Поэтому совместимость N/N-1 для v1 означает:

1. новые request-поля остаются nullable/optional;
2. существующие enum/поля не удаляются и не меняют смысл без нового API version;
3. Control Plane обязан пройти contract tests с текущим и предыдущим agent release;
4. release с `minimumAgentVersion` выше установленного агента не должен назначаться.

Пункт 4 зафиксирован в release metadata, но автоматическая проверка версии агента при
назначении target в текущем срезе не найдена. До реализации этого gate оператор обязан
проверять `minimumAgentVersion` вручную; это остаётся известным release-risk.
