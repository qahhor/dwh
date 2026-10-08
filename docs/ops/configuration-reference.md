# Справочник конфигурации сервера

**Статус:** действующий справочник; таблицы ниже генерируются из кода

Здесь перечислены переменные окружения и свойства, которые читает сервер
SmartupCMS. Имена разведены по
[ADR-0027](../adr/ADR-0027-configuration-names.md):

- `smc.*` / `SMC_*` — настройки самого продукта (CMS);
- `warehouse.*` / `WAREHOUSE_*` — настройки хранилища, второй базы pg-dwh
  ([ADR-0001](../adr/ADR-0001-architecture-model.md));
- остальное — стандартные свойства Spring Boot и общие переменные
  (`DB_*`, `SMTP_*`, `TELEGRAM_*`).

Значения для конкретной установки задаются в `.env` (production — файл,
созданный `scripts/prod/init-production-env.*`); Compose передаёт их серверу
(`deploy/compose/docker-compose.prod.yml`). Свойство без своей переменной в
`application.yml` можно задать через relaxed binding Spring Boot: точки и дефисы
заменяются на `_`, буквы — заглавные (`smc.sse.timeout-ms` → `SMC_SSE_TIMEOUT_MS`).

## Как обновить

Таблицы между маркерами строит `ConfigurationReferenceTest` из
`application.yml`, классов `@ConfigurationProperties` и ключей `@Value` /
`@ConditionalOnProperty`. Тест входит в обычный `mvn verify` и падает, если
справочник разошёлся с кодом. После изменения конфигурации:

```text
mvn test -pl apps/server -Dtest=ConfigurationReferenceTest -Dconfig.reference.update=true
```

Текст вне маркеров пишется вручную.

## Переменные и свойства

<!-- generated:start ConfigurationReferenceTest -->
### Продукт (`smc.*`)

| Переменная окружения | Свойство | По умолчанию |
|---|---|---|
| `SMC_AUDIT_ARCHIVE_CRON` | `smc.audit.archive.cron` | `0 45 3 * * *` |
| `SMC_AUDIT_ARCHIVE_DELETE_AFTER_ARCHIVE` | `smc.audit.archive.delete-after-archive` | `false` |
| `SMC_AUDIT_ARCHIVE_ENABLED` | `smc.audit.archive.enabled` | `true` |
| `SMC_AUDIT_ARCHIVE_INTERVAL` | `smc.audit.archive.interval` | `7d` |
| `SMC_AUDIT_ARCHIVE_LOCAL_PATH` | `smc.audit.archive.local-path` | `/var/lib/smartupcms/audit-archive` |
| `SMC_AUDIT_ARCHIVE_RETENTION` | `smc.audit.archive.retention` | `90d` |
| `SMC_AUDIT_ARCHIVE_S3_ACCESS_KEY` | `smc.audit.archive.s3.access-key` | пусто |
| `SMC_AUDIT_ARCHIVE_S3_BUCKET` | `smc.audit.archive.s3.bucket` | пусто |
| `SMC_AUDIT_ARCHIVE_S3_ENDPOINT` | `smc.audit.archive.s3.endpoint` | пусто |
| `SMC_AUDIT_ARCHIVE_S3_PATH_STYLE` | `smc.audit.archive.s3.path-style-access` | `true` |
| `SMC_AUDIT_ARCHIVE_S3_PREFIX` | `smc.audit.archive.s3.prefix` | `audit/` |
| `SMC_AUDIT_ARCHIVE_S3_REGION` | `smc.audit.archive.s3.region` | `auto` |
| `SMC_AUDIT_ARCHIVE_S3_SECRET_KEY` | `smc.audit.archive.s3.secret-key` | пусто |
| `SMC_AUDIT_ARCHIVE_SIZE_THRESHOLD` | `smc.audit.archive.size-threshold` | `100MB` |
| `SMC_AUDIT_ARCHIVE_TARGET` | `smc.audit.archive.target` | `local` |
| `SMC_AUDIT_PARTITION_CRON` | `smc.audit.partition-cron` | `0 30 3 * * *` |
| `SMC_AUDIT_PARTITION_RUNWAY_DAYS` | `smc.audit.partition-runway-days` | `31` |
| `SMC_AUDIT_RETENTION_MONTHS` | `smc.audit.retention-months` | `12` |
| `SMC_BACKUP_MAX_AGE` | `smc.backup.max-age` | `0s` |
| `SMC_BACKUP_STATUS_FILE` | `smc.backup.status-file` | `/var/lib/smartupcms/backup/status.json` |
| `SMC_DB_IDLE_IN_TRANSACTION_TIMEOUT` | `smc.database.idle-in-transaction-timeout` | `60s` |
| `SMC_DB_STATEMENT_TIMEOUT` | `smc.database.statement-timeout` | `60s` |
| `SMC_DELIVERY_ENFORCE` | `smc.delivery.enforce` | `true` |
| `SMC_ENTITIES_SCHEMA_GATE_ENABLED` | `smc.entities.schema-gate-enabled` | `true` |
| `SMC_FILES_MAX_CONCURRENT_UPLOADS` | `smc.files.max-concurrent-uploads` | `10` |
| `SMC_FILE_SCANNER_CLAMAV_CONNECT_TIMEOUT` | `smc.files.scanner.clamav.connect-timeout` | `3s` |
| `SMC_FILE_SCANNER_CLAMAV_ENABLED` | `smc.files.scanner.clamav.enabled` | `false` |
| `SMC_FILE_SCANNER_CLAMAV_HOST` | `smc.files.scanner.clamav.host` | `clamav` |
| `SMC_FILE_SCANNER_CLAMAV_MAX_STREAM_SIZE` | `smc.files.scanner.clamav.max-stream-size` | `60MB` |
| `SMC_FILE_SCANNER_CLAMAV_PORT` | `smc.files.scanner.clamav.port` | `3310` |
| `SMC_FILE_SCANNER_CLAMAV_READ_TIMEOUT` | `smc.files.scanner.clamav.read-timeout` | `60s` |
| `SMC_FILE_SCANNER_REQUIRED` | `smc.files.scanner.required` | `false` |
| `SMC_IDEMPOTENCY_CLEANUP_CRON` | `smc.idempotency.cleanup-cron` | `0 15 2 * * *` |
| `SMC_IDEMPOTENCY_LEASE_SECONDS` | `smc.idempotency.lease-seconds` | `120` |
| `SMC_IDEMPOTENCY_RETENTION_DAYS` | `smc.idempotency.retention-days` | `14` |
| `SMC_INSTANCE_ADMIN_EMAIL` | `smc.instance.admin-email` | — |
| `SMC_INSTANCE_ADMIN_LOGIN` | `smc.instance.admin-login` | — |
| `SMC_INSTANCE_ADMIN_PASSWORD` | `smc.instance.admin-password` | — |
| `SMC_INSTANCE_CLIENT_CODE` | `smc.instance.client-code` | — |
| `SMC_INSTANCE_CLIENT_NAME` | `smc.instance.client-name` | — |
| `SMC_INSTANCE_RESOURCE_PROFILE` | `smc.instance.resource-profile` | — |
| `SMC_JOBS_LEASE` | `smc.jobs.lease` | `5m` |
| `SMC_JOBS_MAX_ATTEMPTS` | `smc.jobs.max-attempts` | `5` |
| `SMC_JOBS_RETRY_BACKOFF` | `smc.jobs.retry-backoff` | `30s` |
| `SMC_JOBS_RETRY_BACKOFF_MAX` | `smc.jobs.retry-backoff-max` | `1h` |
| `SMC_JOBS_TICK` | `smc.jobs.tick` | `PT5S` |
| `SMC_JOBS_TICKER_ENABLED` | `smc.jobs.ticker-enabled` | `true` |
| `SMC_MAIL_FROM` | `smc.mail.from` | `no-reply@localhost` |
| `SMC_MAIL_FROM_NAME` | `smc.mail.from-name` | `SmartupCMS` |
| `SMC_METRICS_BACKLOG_INTERVAL` | `smc.metrics.backlog-interval` | `PT30S` |
| `SMC_PROVIDER_MAIL` | `smc.providers.mail` | `console_mail` |
| `SMC_PROVIDER_MESSENGER` | `smc.providers.messenger` | `console_messenger` |
| `SMC_PROVIDER_SMS` | `smc.providers.sms` | `console_sms` |
| `SMC_PROVIDER_STORAGE` | `smc.providers.storage` | `local_disk` |
| `SMC_PUBLIC_URL` | `smc.public-url` | пусто |
| `SMC_RATE_LIMIT_ENABLED` | `smc.rate-limit.enabled` | `true` |
| `SMC_RATE_LIMIT_EXPENSIVE_PATHS` | `smc.rate-limit.expensive-paths` | `/api/v1/audit/stats,/api/v1/audit/logs,/api/v1/audit/security-events,/api/v1/search/**,/api/v1/entities/*/imports,/api/v1/entities/*/import-template` |
| `SMC_RATE_LIMIT_EXPENSIVE_PER_MINUTE` | `smc.rate-limit.expensive-per-minute` | `10` |
| `SMC_RATE_LIMIT_IP_PER_MINUTE` | `smc.rate-limit.ip-per-minute` | `60` |
| `SMC_RATE_LIMIT_MAX_ENTRIES` | `smc.rate-limit.max-entries` | `10000` |
| `SMC_RATE_LIMIT_PUBLIC_READ_PER_MINUTE` | `smc.rate-limit.public-read-per-minute` | `600` |
| `SMC_RATE_LIMIT_TOKEN_PER_MINUTE` | `smc.rate-limit.token-per-minute` | `300` |
| `SMC_RATE_LIMIT_USER_PER_MINUTE` | `smc.rate-limit.user-per-minute` | `600` |
| `SMC_REPORTS_EXPORT_MAX_ROWS` | `smc.reports.export.max-rows` | `50000` |
| `SMC_RETENTION_BATCH_SIZE` | `smc.retention.batch-size` | `5000` |
| `SMC_RETENTION_CLOSED_SESSIONS_DAYS` | `smc.retention.days.closed-sessions` | `90` |
| `SMC_RETENTION_CRON` | `smc.retention.cron` | `0 30 3 * * *` |
| `SMC_RETENTION_FAILED_JOBS_DAYS` | `smc.retention.days.failed-jobs` | `90` |
| `SMC_RETENTION_INBOX_DAYS` | `smc.retention.days.inbox` | `180` |
| `SMC_RETENTION_JOB_RUNS_DAYS` | `smc.retention.days.job-runs` | `90` |
| `SMC_RETENTION_LOGIN_ATTEMPTS_DAYS` | `smc.retention.days.login-attempts` | `30` |
| `SMC_RETENTION_MAX_BATCHES` | `smc.retention.max-batches` | `200` |
| `SMC_RETENTION_NOTIFICATION_OUTBOX_DAYS` | `smc.retention.days.notification-outbox` | `30` |
| `SMC_RETENTION_OTP_CODES_DAYS` | `smc.retention.days.otp-codes` | `7` |
| `SMC_RETENTION_PASSWORD_RESET_CODES_DAYS` | `smc.retention.days.password-reset-codes` | `7` |
| `SMC_RETENTION_SEARCH_JOBS_DAYS` | `smc.retention.days.search-jobs` | `90` |
| `SMC_RETENTION_SECURITY_EVENTS_DAYS` | `smc.retention.days.security-events` | `365` |
| `SMC_RETENTION_WEBHOOK_LOGS_DAYS` | `smc.retention.days.webhook-logs` | `90` |
| `SMC_RETENTION_WEBHOOK_OUTBOX_DAYS` | `smc.retention.days.webhook-outbox` | `30` |
| `SMC_S3_ACCESS_KEY` | `smc.storage.s3.access-key` | пусто |
| `SMC_S3_BUCKET` | `smc.storage.s3.bucket` | пусто |
| `SMC_S3_CONNECT_TIMEOUT` | `smc.storage.s3.connect-timeout` | `5s` |
| `SMC_S3_ENDPOINT` | `smc.storage.s3.endpoint` | пусто |
| `SMC_S3_PATH_STYLE` | `smc.storage.s3.path-style-access` | `true` |
| `SMC_S3_READ_TIMEOUT` | `smc.storage.s3.read-timeout` | `30s` |
| `SMC_S3_REGION` | `smc.storage.s3.region` | `auto` |
| `SMC_S3_SECRET_KEY` | `smc.storage.s3.secret-key` | пусто |
| `SMC_SCHEMA_GATE_ENABLED` | `smc.schema-gate.enabled` | `true` |
| `SMC_SEARCH_MAXIMUM_GENERATIONS` | `smc.search.maximum-generations` | `4` |
| `SMC_SECRETS_KEY` | `smc.secrets.key` | пусто |
| `SMC_SECURITY_TRUSTED_PROXIES` | `smc.security.trusted-proxies` | `127.0.0.1/32,::1/128` |
| `SMC_SESSION_ABSOLUTE_TTL` | `smc.session.absolute-ttl` | `7d` |
| `SMC_SESSION_CLEANUP_INTERVAL` | `smc.session.cleanup-interval` | `1h` |
| `SMC_SESSION_IDLE_TIMEOUT` | `smc.session.idle-timeout` | `12h` |
| `SMC_SESSION_TOUCH_INTERVAL` | `smc.session.touch-interval` | `1m` |
| `SMC_SSE_HEARTBEAT_MS` | `smc.sse.heartbeat-ms` | `25000` |
| `SMC_SSE_MAX_CONNECTIONS_PER_USER` | `smc.sse.max-connections-per-user` | `5` |
| `SMC_SSE_TIMEOUT_MS` | `smc.sse.timeout-ms` | `1800000` |
| `SMC_STORAGE_LOCAL_PATH` | `smc.storage.local-path` | `./data/storage` |
| `SMC_SYSTEM_HEALTH_TIMEOUT` | `smc.system.health-timeout` | `2s` |
| `SMC_TYPESENSE_API_KEY` | `smc.typesense.api-key` | `smartupcms_typesense_local_dev` |
| `SMC_TYPESENSE_ENABLED` | `smc.typesense.enabled` | `true` |
| `SMC_TYPESENSE_SYNC_ON_STARTUP` | `smc.typesense.sync-on-startup` | `true` |
| `SMC_TYPESENSE_URL` | `smc.typesense.url` | `http://typesense:8108` |
| `SMC_UPLOADS_XLSX_MAX_COLUMNS` | `smc.uploads.xlsx.max-columns` | `16384` |
| `SMC_UPLOADS_XLSX_MAX_COMPRESSION_RATIO` | `smc.uploads.xlsx.max-compression-ratio` | `200` |
| `SMC_UPLOADS_XLSX_MAX_ENTRIES` | `smc.uploads.xlsx.max-entries` | `1000` |
| `SMC_UPLOADS_XLSX_MAX_ROWS` | `smc.uploads.xlsx.max-rows` | `1048576` |
| `SMC_UPLOADS_XLSX_MAX_SHARED_STRINGS` | `smc.uploads.xlsx.max-shared-strings` | `5000000` |
| `SMC_UPLOADS_XLSX_MAX_SHARED_STRINGS_SIZE` | `smc.uploads.xlsx.max-shared-strings-size` | `256MB` |
| `SMC_UPLOADS_XLSX_MAX_UNPACKED_SIZE` | `smc.uploads.xlsx.max-unpacked-size` | `1GB` |
| `SMC_WEBHOOKS_ALLOWED_HOSTS` | `smc.webhooks.allowed-hosts` | пусто |
| `SMC_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES` | `smc.webhooks.allow-private-addresses` | `false` |
| `SMC_WEBHOOKS_CONNECT_TIMEOUT` | `smc.webhooks.connect-timeout` | `3s` |
| `SMC_WEBHOOKS_ENABLED` | `smc.webhooks.enabled` | `false` |
| `SMC_WEBHOOKS_READ_TIMEOUT` | `smc.webhooks.read-timeout` | `10s` |
| `TELEGRAM_API_URL` | `smc.telegram.api-url` | `https://api.telegram.org` |
| `TELEGRAM_BOT_TOKEN` | `smc.telegram.bot-token` | пусто |

### Хранилище (`warehouse.*`)

| Переменная окружения | Свойство | По умолчанию |
|---|---|---|
| `WAREHOUSE_CONNECT_TIMEOUT` | `warehouse.connect-timeout` | обязательна |
| `WAREHOUSE_MAINTENANCE_STATEMENT_TIMEOUT` | `warehouse.maintenance-statement-timeout` | `30m` |
| `WAREHOUSE_PASSWORD` | `warehouse.password` | обязательна |
| `WAREHOUSE_RAW_WRITE_TIMEOUT` | `warehouse.raw-write-timeout` | `30m` |
| `WAREHOUSE_STATEMENT_TIMEOUT` | `warehouse.statement-timeout` | `60s` |
| `WAREHOUSE_URL` | `warehouse.url` | обязательна |
| `WAREHOUSE_USERNAME` | `warehouse.username` | обязательна |

### Платформа (Spring Boot и общие)

| Переменная окружения | Свойство | По умолчанию |
|---|---|---|
| `DB_PASSWORD` | `spring.datasource.password` | `postgres` |
| `DB_URL` | `spring.datasource.url` | `jdbc:postgresql://localhost:5432/smartupcms` |
| `DB_USER` | `spring.datasource.username` | `postgres` |
| `SMC_LOG_FILE` | `logging.file.name` | пусто |
| `SMC_LOG_MAX_FILE_SIZE` | `logging.logback.rollingpolicy.max-file-size` | `100MB` |
| `SMC_LOG_MAX_HISTORY` | `logging.logback.rollingpolicy.max-history` | `12` |
| `SMC_LOG_TOTAL_SIZE_CAP` | `logging.logback.rollingpolicy.total-size-cap` | `2GB` |
| `SMC_TRACING_EXPORT_ENABLED` | `management.tracing.export.enabled` | `false` |
| `SMC_TRACING_OTLP_ENDPOINT` | `management.opentelemetry.tracing.export.otlp.endpoint` | `http://localhost:4318/v1/traces` |
| `SMC_TRACING_SAMPLING_PROBABILITY` | `management.tracing.sampling.probability` | `0.0` |
| `SMTP_AUTH` | `spring.mail.properties.mail.smtp.auth` | `true` |
| `SMTP_HOST` | `spring.mail.host` | пусто |
| `SMTP_PASSWORD` | `spring.mail.password` | пусто |
| `SMTP_PORT` | `spring.mail.port` | `587` |
| `SMTP_STARTTLS` | `spring.mail.properties.mail.smtp.starttls.enable` | `true` |
| `SMTP_USER` | `spring.mail.username` | пусто |

### Секреты, которые вне профилей dev и test не могут сохранять значение по умолчанию

- `SMC_TYPESENSE_API_KEY` (в `.env` для Compose — `TYPESENSE_API_KEY`) — `smc.typesense.api-key`
- `SMC_INSTANCE_ADMIN_PASSWORD` (в `.env` для Compose — `ADMIN_PASSWORD`) — `smc.instance.admin-password`
- `DB_PASSWORD` — `spring.datasource.password`
- `WAREHOUSE_PASSWORD` (в `.env` для Compose — `DB_PASSWORD`) — `warehouse.password`

<!-- generated:end -->

## Старые имена не читаются

Имена до [ADR-0027](../adr/ADR-0027-configuration-names.md) (свойства
`dwh.*`, `app.dwh.*` и переменные с теми же префиксами) и старые имена
продукта из пункта 4.7 плана 10/10 (cookie сессии, префикс токенов API, ключи
браузера) сервер, Compose, образы и скрипты развёртывания не читают.
Переходный период отменён 2026-10-01: установок у клиентов нет. Сервер
запускается только с именами из таблиц выше; переменная со старым именем
просто игнорируется.

`dwh` теперь означает только хранилище (pg-dwh). `scripts/docs/test-repository-hygiene.ps1`
падает на новом вхождении `dwh`/`DWH`/`Dwh` вне закрытого списка: хранилище,
выпущенные миграции, тесты, доказывающие, что старые имена не читаются, и
документы об истории.

## Секреты разработки вне dev

Значения секретов из `application*.yml`, `docker-compose.yml` и `.env.example`
опубликованы в репозитории. Сервер отказывается стартовать, если такой секрет
сохранил значение по умолчанию, а активен не профиль `dev` или `test` (и не
шаг `migrate`, который строит схему и не использует эти секреты). Ошибка
называет переменные, которые нужно задать; значения не печатаются. Список
секретов — в конце сгенерированной части выше.

## Настройки разработки вне dev

`ProductionStartGuard` (сразу после проверки секретов) отказывает в старте
вне профилей `dev` и `test`, если включена настройка, допустимая только при
разработке. Ошибка перечисляет все такие настройки сразу:

| Настройка | Почему вне dev запрещена | Что сделать |
|---|---|---|
| профиль `demo` без `dev` или `test` | заполняет установку демонстрационными пользователями и записями | запускать `demo` только вместе с `dev` (локальный стенд) |
| пустой `SMC_PUBLIC_URL` | из него строятся ссылки приглашения и сброса пароля; без него они не уходят | задать публичный адрес, например `https://cms.example.com` (в Compose он обязателен) |
| `SMC_ENTITIES_SCHEMA_GATE_ENABLED=false` | **только для `cms migration diff`**, который запускается как тест: старт на схеме, расходящейся с объявлениями сущностей, ломает запросы позже | не задавать; при выключенном шлюзе сервер пишет `entity_schema_gate_disabled` в журнал |
| `SMC_SCHEMA_GATE_ENABLED=false` | схема должна совпадать с миграциями | не задавать |
| `SMC_PROVIDER_MAIL=console_mail` при `SMC_DELIVERY_ENFORCE=true` | заглушка почты ничего не доставляет, а приглашение задаёт первый пароль, сброс — новый | настроить SMTP (`SMC_PROVIDER_MAIL=smtp`, `SMTP_HOST`) или осознанно задать `SMC_DELIVERY_ENFORCE=false` |

Шаг `migrate` проверяет только профиль `demo`: остальные настройки он не
использует.

## Заглушки каналов доставки

Провайдеры `console_mail`, `console_sms` и `console_messenger` ничего не
отправляют и пишут в журнал только факт недоставки: замаскированного
получателя (`u***@example.com`, `***42`), тему письма и длину текста. Тело
сообщения в журнал не попадает никогда: в нём ссылки приглашения и сброса
пароля и одноразовые коды. Код для входа при разработке читается из
настоящего канала (почтовый перехватчик локального стенда), а не из журнала.

Пока `SMC_DELIVERY_ENFORCE=true` (по умолчанию), приглашение нового
пользователя на заглушке почты отклоняется с 409
`error.auth.channel_not_deliverable` вместе с созданием пользователя; привязка
канала двухфакторного входа к заглушке отклоняется так же.
