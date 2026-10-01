# ADR-0030: Разделение fnd на очередь заданий, хранилище и единицы

**Статус:** Принято (2026-10-01)
**Дата:** 2026-10-01
**Зависит от:** ADR-0001 (две базы), ADR-0006 (модульный монолит), ADR-0020
(именование, таблицы не переименовываются), ADR-0021 (модель ошибок),
ADR-0026 (опубликованные представления), ADR-0027 (имена конфигурации),
ADR-0028 (коды прав); план 10/10, пункт 4.2

---

## 1. Контекст

Модуль `fnd` («основа») держал четыре разные вещи: общую очередь фоновых
заданий всего продукта, вторую базу pg-dwh (источник данных, миграции и CLI
`MigrateMain`, слой `raw`, чтение витрин), справочник единиц измерения и
механизм версий с датой действия. Рядом жили актор аудита
(`app.user_id` для `fnd_audit_trigger`) и собственная иерархия ошибок с одним
перечнем `ConstraintErrorCode` на все таблицы `fnd_*`. SQL был написан прямо в
сервисах. Из-за этого очередь заданий, нужная отчётам и загрузкам, формально
зависела от хранилища, а граница «модуль — свои таблицы» не проверялась.

## 2. Решение

1. **Четыре места вместо одного.**

   | Было | Стало | Что внутри |
   |---|---|---|
   | `fnd.api.FndJob*`, `fnd.jobs`, `fnd.service.FndJobQueries` | модуль `jobs` (`com.smartup24.cms.instance.jobs`) | `api`: `JobHandler`, `JobQueue`, `JobAttempt`, `JobFailures`, `JobNotRetryableException`, `JobError`; `runner`: `JobRunner`, `JobLease`, `JobSwitch`; `config`: `JobProperties`, `JobsConfig`, `JobRetentionPolicies`; `service.JobQueries`; `repository.JobQueueRepository` |
   | `fnd.config`, `fnd.migration`, `fnd.dwh`, `fnd.load`, задания обслуживания | модуль `warehouse` | `datasource`, `migration` (`MigrateMain`, `Migrator`, `MigrationCatalog`, `WarehouseSchemaVersionGate`), `raw` (`JdbcRawWriter`), `mart` (`MartReader`), `load` (`WarehouseLoadService`), `jobs` (`LoadCleanupJob`, `CrossDatabaseCheckJob`), `repository` (`LoadRepository`, `RawRowRepository`, `SourceFileRepository`); `api`: `WarehouseLoads`, `WarehouseLoad`, `RawWriter`, `RawSource`, `RawRow`, `WarehouseUnavailableException`, `WarehouseError` |
   | `fnd.units`, `fnd.api.FndUnit*` | модуль `units` | `api`: `Units`, `Unit`, `UnitConversion`, `CoefficientMissingException`, `UnitError`; `service.UnitService`, `repository.UnitRepository` |
   | `fnd.versioning`, `fnd.api.FndVersion*`, актор, `FndSqlErrors` | платформа `common` | `common.versioning` (`Versions`, `VersioningService`, `VersionRepository`, `VersionError`, `StaleVersionException`), `common.actor` (`AuditActor`, `AuditActorContext`, `ActorError`), `common.error` (`ConstraintCode`, `ConstraintCodes`, `ConstraintViolationException`, `ConstraintErrors`, `TransientFailure`) |

   План называл очередь `platform/jobs`; отдельного корня `platform` в
   пакетах нет, поэтому модуль — `instance.jobs`, как остальные модули.
   Префикс `Fnd` у типов снят: имя пакета уже говорит, где тип живёт.

2. **Таблицы своей базы.** Таблицы единиц (`fnd_unit*`) и журнал загрузок
   (`fnd_loads`, `fnd_load_log`) лежат в базе CMS, строки загрузок — в pg-dwh.
   Поэтому `units` — обычный предметный модуль базы CMS, а `warehouse` держит
   и вторую базу, и учёт того, что в неё загружено. Имена таблиц не меняются
   (ADR-0020): `ModuleBoundariesTest.ownerOf` относит `fnd_job_*` к `jobs`,
   `fnd_unit*` к `units`, `fnd_load*` к `warehouse`; реестры
   `fnd_versioned_tables` и `fnd_audit_tables` — платформа (`common`).
   Коды заданий в расписании (`fnd.load_cleanup`, `fnd.xdb_check`) — данные
   миграции V105, они тоже не меняются.

3. **Версии и актор — платформа.** Версионность — один механизм для любой
   таблицы, объявленной `fnd_versioning_enable` (коэффициенты единиц, форматы
   загрузок), своих бизнес-таблиц у неё нет, как у реестра полей; поэтому она в
   `common`. Контракт актора (`AuditActorContext`) тоже в `common`, а
   реализует его `md` (`MdAuditActors`, `MdSystemAccountRepository`,
   `MdSystemAccountBootstrap`): технической учёткой `system` в `md_users`
   владеет модуль пользователей.

4. **Очередь не знает хранилище.** `JobFailures.isTransient` узнаёт временный
   сбой модуля по маркеру `common.error.TransientFailure`
   (`WarehouseUnavailableException` его реализует), а не по типу хранилища.
   Выключатель `jobs_enabled` очередь читает через `MdSettingService`
   (ADR-0026, пункт 4), а не из `md_settings`. Правило
   `WarehouseArchitectureTest.jobsDependOnNoWarehouse` проверяет это строго.

5. **SQL только в репозиториях.** `ServicesRunNoSqlTest` — строгое правило на
   всё приложение: ни класс `*Service`, ни вложенный в него класс не вызывает
   `JdbcClient.sql`, методы `JdbcTemplate`/`NamedParameterJdbcTemplate` и не
   готовит запрос на `Connection`. Вне `fnd` таких мест было три (архив аудита,
   сведения о системе, сверка поиска), их SQL перенесён в репозитории.
   Фасады второй базы (`JdbcRawWriter`, `MartReader`) — слой доступа к данным
   pg-dwh (COPY и чтение на уровне соединения), а не сервисы.

6. **Ошибки — в общей модели.** Вместо одного перечня каждый модуль объявляет
   свои коды перечнем, реализующим `ConstraintCode` (`JobError`, `UnitError`,
   `WarehouseError`, `VersionError`, `ActorError`); `ConstraintErrors`
   переводит ошибку PostgreSQL по коду ограничения или тексту триггера.
   Версионность пишет таблицы версий других модулей, поэтому модуль
   публикует свои коды бином `ConstraintCodes` (`UnitsConfig`), и нарушение
   ограничения таблицы коэффициентов по-прежнему приходит с кодом единиц.
   **Ключи текстов не меняются:** `error.fnd.<код>` — часть ответа
   (`messageKey`) и уже есть во всех каталогах; переименование ключей — пункт
   4.5 плана (семантические ключи) с переходным периодом.

7. **Переходный период для установок.** Классы и пакеты — не контракт
   установки, кроме имени класса шага миграции в Compose. Поэтому
   `com.smartup24.cms.instance.fnd.migration.MigrateMain` остаётся
   устаревшим псевдонимом (наследует `main` нового класса) до 2026-12-31, как
   и старые имена конфигурации (ADR-0027); Compose в репозитории уже вызывает
   `com.smartup24.cms.instance.warehouse.migration.MigrateMain`. Имена бинов
   `dwhDataSource`/`dwhJdbcClient`, квалификатор `dwh`, имя пула и компонент
   health `dwh` не меняются: на них смотрят мониторинг и алерты.

8. **Область прав.** Область `fnd` в `PermissionAreas` (ADR-0028) заменена
   областями `jobs`, `units`, `warehouse`; выданных прав с кодом `fnd.*` не
   было, миграция прав не нужна.

## 3. Проверки

- `ModuleBoundariesTest` — модули `jobs`, `units`, `warehouse` вместо `fnd`;
  репозитории трогают только свои таблицы (сверка файлов идёт через
  `mf_pub_files`, события безопасности пишет `AuditLogService`).
- `WarehouseArchitectureTest` (бывший `FndArchitectureTest`) — квалификатор
  `dwh` только в `warehouse`, очередь не зависит от хранилища, модули данных
  не зависят от `upl`, не держат своего хранилища файлов, без контроллеров и
  `@Scheduled`.
- `ServicesRunNoSqlTest` — 0 вызовов SQL в классах `*Service`.
- `ConstraintCodeMappingTest` — каждое ограничение таблиц `fnd_*` есть в
  перечне модуля-владельца таблицы, и наоборот.
- `MigrateMainAliasTest` — старое имя класса запускает тот же шаг.
- Хранилище замороженных нарушений ArchUnit пусто; пороги покрытия модуля
  `fnd` разнесены на `jobs`, `units`, `warehouse` без понижения
  (`apps/server/coverage-floors.csv`).

## 4. Последствия

- Вызывающий код (`upl`, `report`, `config.jobs`) поменял импорты и имена
  типов; поведение, REST и схема БД прежние.
- Новый модуль, которому нужна очередь, зависит только от `jobs.api`; модулю
  нужны строки загрузок — `warehouse.api`; версии — `common.versioning`.
- Псевдоним `fnd.migration.MigrateMain` удаляется после 2026-12-31 вместе с
  синонимами конфигурации ADR-0027.
