# RB-10: Диск хоста

**Версия:** 1.0

**Обновлено:** 2026-10-10

**Алерты:** `SmartupcmsHostDiskSpaceCritical` (critical);
`SmartupcmsHostDiskSpaceLow`, `SmartupcmsHostDiskFillingUp`,
`SmartupcmsHostExporterDown` (warning) — [SLO и алерты](../ops/slo.md),
раздел 7. Метрики даёт необязательный node-exporter
(`deploy/observability/docker-compose.observability.yml`).

## Сигнал и цель

- `HostDiskSpaceLow` / `Critical`: на файловой системе хоста `mountpoint`
  (`device`) свободно меньше 20 % 15 мин / 10 % 5 мин (ADR-0009, раздел 4).
  На этих дисках лежат тома Docker: данные PostgreSQL (`postgres-data`),
  бэкапы (`backups`), файлы и журналы сервера (`server-data`), индекс поиска
  и базы ClamAV.
- `HostDiskFillingUp`: свободно меньше 40 %, и при скорости последних шести
  часов диск заполнится меньше чем за сутки (`predict_linear`).
- `HostExporterDown`: Prometheus 10 мин не получает метрики node-exporter —
  остальные алерты этого runbook слепы.

Цель — вернуть запас места до того, как PostgreSQL перейдёт в аварийную
остановку записи, а бэкап и загрузки начнут падать.

## Немедленные действия

1. Дашборд «database and disk», панели хоста: какая точка монтирования и с
   какого момента растёт.
2. На хосте: `df -h` и `docker system df -v` — какой том Docker занимает
   место.
3. `HostExporterDown`: `docker compose ... ps node-exporter`, журнал
   контейнера; сеть `monitoring` должна быть доступна Prometheus.

## Диагностика

1. Что растёт:
   - `postgres-data` — размер баз (`SmartupcmsPostgresDatabaseGrowthHigh`,
     [RB-11](RB-11-postgresql-health.md)), WAL при долгой транзакции или
     неудачном архивировании;
   - `backups` — срок хранения `BACKUP_RETENTION_DAYS`, бэкапы не удаляются
     ([RB-03](RB-03-backup.md));
   - `server-data` — журналы `SMC_LOG_*`, архив аудита
     (`SMC_AUDIT_ARCHIVE_RETENTION`), загрузки при `local_disk`
     ([RB-07](RB-07-database-pool-and-disk.md));
   - журналы контейнеров Docker (ротация 100 МБ × 5 на сервис) и
     неиспользуемые образы после обновлений.
2. Резкий рост за часы — импорт, массовая загрузка, зациклившееся задание;
   медленный — естественный рост, нужен план ёмкости.

## Восстановление

- Увеличьте диск или том — основной путь.
- Освобождайте только то, что разрешено политикой хранения: старые образы
  (`docker image prune` после проверки, что откат на них не нужен), старые
  архивы журналов, бэкапы старше утверждённого RPO-окна после проверки, что
  свежий бэкап успешен.
- Не удаляйте файлы внутри `postgres-data`, `server-data/storage` и
  `typesense-data` вручную.

## Эскалация и закрытие

Свободно меньше 5 % или прогноз заполнения меньше 4 часов — эскалация
владельцу установки (P1). Закрытие: запас больше 20 %, причина роста
записана, при естественном росте — заявка на расширение.
