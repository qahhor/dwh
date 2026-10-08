# SLO, метрики и алерты SmartupCMS

**Версия:** 1.0

**Обновлено:** 2026-10-07

**Основание:** план 10/10, п. 7.3; [ADR-0009](../adr/ADR-0009-observability.md)
(каталог алертов и дисциплина телеметрии), `NFR-OBS-01`, `NFR-OBS-02`,
`NFR-PERF-02`, `AC-12` [технического задания](../technical-specification.md).

Документ описывает, что сервер отдаёт на `/actuator/prometheus`, какие SLO по
этим метрикам считаются, какие алерты из них следуют и где лежат правила,
дашборды и runbook. Сбор метрик, Alertmanager, маршрут дежурному и Grafana
разворачивает оператор установки (`NFR-OBS-02`): поставка даёт готовые
правила и дашборды, но не запускает их сама.

## 1. Статус порогов

| Порог | Значение | Статус |
|---|---|---|
| Доступность API | 99.9 % за 30 дней | **утверждён** (ТЗ, раздел 7) |
| Задержка API и entity runtime, p95 / p99 | 300 мс / 1 с | **предложение по умолчанию**, не утверждено (`NFR-PERF-02`) |
| Очередь заданий, отставание головы | 5 мин (предупреждение), 15 мин (инцидент) | предложение по умолчанию |
| Outbox вебхуков и уведомлений, отставание головы | 15 мин (предупреждение, ADR-0009), 1 ч (инцидент) | предложение по умолчанию |
| Свежесть бэкапа | `SMC_BACKUP_MAX_AGE`, без него 26 ч | значение установки (RPO, `AC-12`) |
| Диск | 20 % свободно (предупреждение), 10 % (инцидент) | ADR-0009 |
| Пул соединений | занят > 90 % 10 мин; ожидание соединения 5 мин | предложение по умолчанию |

Пороги со статусом «предложение» — рабочие значения правил, пока владельцы
продукта и эксплуатации установки не утвердили свои (`AC-12`). Утверждённое
значение меняется в `deploy/observability/prometheus/rules/alerts.yml` вместе с
юнит-тестом правила.

## 2. Метрики сервера

Все метрики несут метку `application="smartupcms"`
(`management.metrics.tags.application`). В метках — только конечные
перечисления сервера: коды обработчиков заданий, имя outbox, исход; никаких
идентификаторов пользователей, запросов или записей.

| Метрика Prometheus | Тип | Метки | Что значит | Источник |
|---|---|---|---|---|
| `http_server_requests_seconds_bucket/_count/_sum` | гистограмма | `method`, `uri`, `status`, `outcome`, `exception` | Время HTTP-запроса. Гистограмма процентилей (5 мс … 30 с) плюс корзины SLO 0.1, 0.3 и 1 с | Spring Boot, `application.yml` |
| `smc_jobs_execution_seconds_*` | таймер, корзины 1 с … 30 мин | `handler`, `outcome` | Одна попытка задания очереди; разбор и применение загрузок УПЛ — это `handler="upl.parse"` / `"upl.apply"` | `JobMetrics` |
| `smc_jobs_queue_due` | gauge | — | Задания, срок которых наступил и которые никто не арендовал | `JobMetrics` (опрос раз в 30 с) |
| `smc_jobs_queue_lag_seconds` | gauge | — | Сколько самое старое из них ждёт сверх своего срока | `JobMetrics` |
| `smc_jobs_queue_failed` | gauge | — | Задания, исчерпавшие попытки, ждут оператора | `JobMetrics` |
| `smc_outbox_delivery_seconds_*` | таймер | `outbox` (`webhook`, `notification`), `outcome` (`success`, `retry`, `dead_letter`) | Одна попытка доставки | `OutboxMetrics` |
| `smc_outbox_dead_letters_total` | счётчик | `outbox` | Доставки, исчерпавшие попытки | `OutboxMetrics` |
| `smc_outbox_pending`, `smc_outbox_lag_seconds` | gauge | `outbox` | Доставки к отправке сейчас и ожидание самой старой сверх срока | воркеры outbox (опрос раз в 30 с) |
| `smc_task_run_seconds_*` | таймер | `task` (`audit_archive`, `retention`), `outcome` | Ночной архив журнала аудита и очистка журналов | `TaskRunMetrics` |
| `smc_retention_deleted_rows_total` | счётчик | `policy` | Строки, удалённые очисткой | `RetentionJob` |
| `smc_search_query_duration_seconds_*` | таймер | `entity`, `source`, `outcome` | Поисковый запрос | `SearchMetrics` |
| `smc_search_delivery_lag_seconds`, `smc_search_delivery_pending` | gauge | `role` | Отставание индекса поиска от базы | `SearchMetrics` |
| `smc_health_readiness` | gauge | — | 1, пока группа readiness (основная БД) в состоянии UP | `SystemMetrics` |
| `smc_backup_status` | gauge | `status` | 1 у состояния последней попытки бэкапа: `SUCCESS`, `FAILED`, `NEVER`, `UNKNOWN` | `SystemMetrics` |
| `smc_backup_age_seconds` | gauge | — | Возраст последнего успешного бэкапа; NaN, если последняя попытка не успешна | `SystemMetrics` |
| `smc_backup_max_age_seconds` | gauge | — | Допустимый возраст: `SMC_BACKUP_MAX_AGE`, без него 26 ч (суточный интервал плюс 2 ч на сам бэкап) | `SystemMetrics` |
| `hikaricp_connections_active/idle/pending/max`, `hikaricp_connections_acquire_seconds_*`, `hikaricp_connections_timeout_total` | gauge, таймер, счётчик | `pool` | Пулы соединений HikariCP; основной пул — `SmartupCmsHikariPool` | Spring Boot |
| `disk_free_bytes`, `disk_total_bytes` | gauge | `path` | Рабочий каталог и файловое хранилище (в production-сборке — том данных сервера с журналами и архивом аудита) | Spring Boot, `management.metrics.system.diskspace.paths` |
| `smc_file_scanner_seconds_*`, `smc_storage_operation_seconds_*` | таймер | `provider`, `operation`, `outcome` | Антивирус и S3 (`NFR-OBS-01`) | `ClamAvFileScanner`, `S3StorageProvider` |
| `jvm_*`, `process_*`, `system_*` | разные | — | JVM и процесс | Spring Boot |

Очередь заданий и outbox — таблицы, которые каждый узел видит одинаково:
правила берут максимум по экземплярам. Таймеры и счётчики у каждого узла свои,
правила их суммируют.

## 3. SLO и SLI

| SLO | SLI (recording rule) | Цель |
|---|---|---|
| Доступность API | доля запросов `/api/…` без ответа 5xx: `1 - smc:api_error_ratio:rate*` | 99.9 % за 30 дней; бюджет ошибок 0.1 % |
| Задержка API | `smc:api_latency_seconds:p95_5m`, `…:p99_5m`; доля ответов ≤ 300 мс `smc:api_requests_within_300ms:ratio_rate5m` | p95 ≤ 300 мс, p99 ≤ 1 с (предложение) |
| Задержка entity runtime | `smc:entity_latency_seconds:p95_5m`, `…:p99_5m` (`/api/v1/entities/…`, ADR-0032) | p95 ≤ 300 мс (предложение) |
| Очередь заданий | `smc:jobs_queue_lag_seconds:max` | голова ждёт не дольше 5 мин |
| Outbox вебхуков и уведомлений | `smc:outbox_lag_seconds:max`; рост `smc_outbox_dead_letters_total` | голова не старше 15 мин; новых dead letters нет |
| Readiness | `smc_health_readiness`, `up{job="smartupcms"}` | каждый экземпляр готов |
| Свежесть бэкапа | `smc_backup_age_seconds` против `smc_backup_max_age_seconds`; `smc_backup_status` | последний бэкап успешен и не старше допустимого |
| Насыщение | `smc:db_pool_utilisation:ratio`, `hikaricp_connections_pending`, `smc:disk_free:ratio` | пул занят < 90 %, нет ожидания соединения, диска > 20 % |

Из SLI API исключён поток событий `/api/v1/events` (SSE): один запрос живёт,
пока открыта вкладка. Длинные выгрузки и загрузки файлов входят в SLI
задержки; если они смещают p99 установки, их `uri` исключают тем же способом
(«предположение»: на эталонной нагрузке это не проверено).

Сгорание бюджета доступности считается по двум окнам (ADR-0009, раздел 4):
×14.4 за 1 ч и 5 мин — инцидент (2 % месячного бюджета за час), ×6 за 6 ч и
30 мин — предупреждение (5 % за шесть часов).

## 4. Алерты

Серьёзность `critical` — вызов дежурного сразу, `warning` — разбор в рабочее
время. У каждого алерта есть `runbook_url` на файл в `docs/runbooks` и
юнит-тест promtool.

| Алерт | Серьёзность | Runbook |
|---|---|---|
| `SmartupcmsInstanceDown`, `SmartupcmsScrapeTargetMissing`, `SmartupcmsReadinessDown` | critical | [RB-01](../runbooks/RB-01-instance-unavailable.md) |
| `SmartupcmsApiErrorBudgetBurnFast`, `SmartupcmsApiLatencyP99High` | critical | [RB-02](../runbooks/RB-02-api-errors-and-latency.md) |
| `SmartupcmsApiErrorBudgetBurnSlow`, `SmartupcmsApiLatencyP95High`, `SmartupcmsEntityRuntimeLatencyHigh` | warning | [RB-02](../runbooks/RB-02-api-errors-and-latency.md) |
| `SmartupcmsBackupFailed`, `SmartupcmsBackupStale` | critical | [RB-03](../runbooks/RB-03-backup.md) |
| `SmartupcmsBackupMissing` | warning | [RB-03](../runbooks/RB-03-backup.md) |
| `SmartupcmsJobQueueStalled` | critical | [RB-05](../runbooks/RB-05-job-queue.md) |
| `SmartupcmsJobQueueLagHigh`, `SmartupcmsJobsFailed` | warning | [RB-05](../runbooks/RB-05-job-queue.md) |
| `SmartupcmsOutboxStalled` | critical | [RB-06](../runbooks/RB-06-outbox-delivery.md) |
| `SmartupcmsOutboxLagHigh`, `SmartupcmsOutboxDeadLetters` | warning | [RB-06](../runbooks/RB-06-outbox-delivery.md) |
| `SmartupcmsDbPoolStarved`, `SmartupcmsDiskSpaceCritical` | critical | [RB-07](../runbooks/RB-07-database-pool-and-disk.md) |
| `SmartupcmsDbPoolSaturated`, `SmartupcmsDiskSpaceLow` | warning | [RB-07](../runbooks/RB-07-database-pool-and-disk.md) |
| `SmartupcmsAuditArchiveFailed`, `SmartupcmsRetentionFailed` | warning | [RB-08](../runbooks/RB-08-maintenance-tasks.md) |
| `SmartupcmsSearchDeliveryLagHigh` | warning | [RB-09](../runbooks/RB-09-search-delivery.md) |

Провал миграции (ADR-0009) — не метрика: его видно по коду выхода сервиса
`migrate`, разбор — [RB-04](../runbooks/RB-04-migration-failure-triage.md).

## 5. Где что лежит и как подключить

| Путь | Содержимое |
|---|---|
| `deploy/observability/prometheus/prometheus.yml` | пример конфигурации: job `smartupcms`, `/actuator/prometheus` на порту управления 9090 |
| `deploy/observability/prometheus/rules/recording.yml` | recording rules SLI |
| `deploy/observability/prometheus/rules/alerts.yml` | алерты |
| `deploy/observability/prometheus/tests/*.yml` | юнит-тесты promtool, по тесту на каждый алерт |
| `deploy/observability/grafana/dashboards/*.json` | дашборды: обзор, API и SLO, задания и outbox, база данных и диск |

1. Откройте Prometheus доступ к порту управления сервера (9090) только из сети
   мониторинга; наружу порт не публикуется.
2. Возьмите `prometheus.yml` за основу: имя job `smartupcms` ожидают правила
   `SmartupcmsInstanceDown` и `SmartupcmsScrapeTargetMissing`.
3. Подключите оба файла правил и настройте Alertmanager: маршрут
   `severity="critical"` — дежурному, `warning` — в рабочий канал.
4. Импортируйте дашборды в Grafana; при импорте выберите источник данных
   Prometheus (переменная `datasource`).
5. Диски хоста и PostgreSQL сервер не видит: для них оператор добавляет
   node_exporter и postgres_exporter (ADR-0009, раздел 4).

## 6. Проверки

- `scripts/observability/test-prometheus-rules.ps1` (Linux и macOS —
  `test-prometheus-rules.sh`): `promtool check config`, `check rules`,
  `test rules` в образе `prom/prometheus`, закреплённом по digest; каждый алерт
  имеет юнит-тест; дашборды — корректный JSON с `uid` и запросами. В CI — job
  `observability` в `.github/workflows/ci.yml`.
- `scripts/docs/test-repository-hygiene.ps1`: у каждого алерта есть
  `severity` и `runbook_url` на существующий файл `docs/runbooks/RB-*.md`, на
  каждый runbook ссылается алерт (кроме RB-04) и индекс документации.
- `PrometheusScrapeIntegrationTest`: `/actuator/prometheus` отдаёт гистограмму
  `http_server_requests_seconds` с корзинами SLO, и каждая метрика, которую
  читают правила и дашборды, сервер действительно экспортирует.
