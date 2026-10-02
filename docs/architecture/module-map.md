# Карта модулей сервера

Одна строка — один модуль сервера `apps/server`
(`com.smartup24.cms.instance.*`): что он делает, какими таблицами владеет,
какие области прав проверяет и откуда в него входят. Список бизнес-модулей
задаёт `ModuleBoundariesTest.MODULES`; `common` и `config` — инфраструктура.
Соответствие карты коду проверяет `ModuleMapTest` (план 10/10, пункт 4.3): у
каждого модуля есть `package-info.java` с назначением, и у каждого модуля ровно
одна строка в таблице ниже; строки для несуществующего модуля нет.

## Префиксы Biruni

Короткие коды модулей — соглашение Biruni, они остаются как есть:

| Префикс | Значение | Модули |
|---|---|---|
| `md` | master data — мастер-данные | `md` |
| `ms` | messaging & services — сообщения и сервисы | `ms.task`, `ms.note`, `ms.notify` |
| `mf` | files — файлы | `mf` |
| `kauth` | authentication — аутентификация | `kauth` |

Остальные модули названы словом предметной области. Префикс `ms` объединяет
три самостоятельных модуля: у пакета `ms` есть свой `package-info.java`, но
строки в карте у него нет.

## Модули

Области прав — по [ADR-0028](../adr/ADR-0028-permission-codes.md): код формы
`<область>` или `<область>.<сущность>`, область называет модуль-владельца.
«Опубликованные» — формы другого модуля, которыми модулю разрешено закрывать
свои обработчики (`PermissionAreas.PUBLISHED`). Представления `*_pub_*` —
опубликованные представления для чтения ([ADR-0026](../adr/ADR-0026-published-read-views.md)).
Имена таблиц не меняются при переименовании модулей
([ADR-0020](../adr/ADR-0020-database-naming.md)).

| Код | Пакет | Назначение | Таблицы | Области прав | Точки входа |
|---|---|---|---|---|---|
| `analytics` | `com.smartup24.cms.instance.analytics` | Показатели дашборда: сводка задач, тренды, нагрузка, распределение по проектам; читает `ms_task_pub_*` и `md_pub_users`. Виджеты зрителя на том же экране — отчёты списков сущностей без кода (ADR-0032, §10.2), их строит общий runtime, а не этот модуль | своих нет | `analytics` (`analytics.dashboard`) | `AnalyticsController` — `/api/v1/analytics` |
| `audit` | `com.smartup24.cms.instance.audit` | Секционированный журнал изменений, журнал событий безопасности, история записи, архив старых секций на диск или в S3 | `audit_log*`, `security_events` | `audit` (`audit.log`); опубликованная `md.profile` | `AuditLogController` — `/api/v1/audit`, `RecordHistoryController` — `/api/v1/history`; `AuditLogService` для других модулей; `AuditPartitionWorker`, `AuditArchiveWorker` |
| `example` | `com.smartup24.cms.instance.example` | Эталон «документ со строками и статусами» low-code ([ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §9.4): заказы со строками, итогом и процессом «черновик → проведён → отменён» — одно объявление `ExampleOrderEntity` без хуков, своего экрана нет (общий экран `/e/example.orders`); в поставке выключен (§19, В3) | `ex_*` | `example` (`example.orders`) | runtime `/api/v1/entities/example.orders` (строки `lines`, переходы `post`, `unpost`, `cancel`); `ExampleOrderEntity` |
| `jobs` | `com.smartup24.cms.instance.jobs` | Общая очередь фоновых заданий: аренда, повторы, расписание, история запусков ([ADR-0030](../adr/ADR-0030-fnd-split.md)) | `fnd_job_*` | `jobs` (форм нет) | `jobs.api` (`JobQueue`, `JobHandler`); такт очереди — `config.jobs.JobQueueWorker` |
| `kauth` | `com.smartup24.cms.instance.kauth` | Аутентификация: вход по паролю и одноразовому коду, сессии, API-токены, смена и сброс пароля, приглашение нового пользователя (`UserInvitations` модуля `md`), вход через OAuth2/SSO, каналы получения кодов | `kauth_*`, `md_sso_providers` | `kauth` (своих форм нет); опубликованные `md.profile`, `md.users`, `md.settings` | `KauthAuthController` — `/api/v1/auth`, `OAuth2AuthController`, `KauthSessionController`, `KauthApiTokenController`, `KauthChannelController`, `KauthPasswordController`, `KauthPasswordResetController` |
| `md` | `com.smartup24.cms.instance.md` | Мастер-данные: пользователи, роли и права, оргструктура и область данных, настройки, динамические поля, языки и переводы, виды списков (таблица, отчёт, виджет — [ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §10.2), меню, реестр модулей | `md_*` (кроме `md_sso_providers`); `md_pub_users` | `md` (`md.users`, `md.profile`, `md.roles`, `md.assignments`, `md.org_units`, `md.settings`, `md.navigation`, `md.modules`, `md.custom_fields`) | сущность `md.users` на общем runtime — `/api/v1/entities/md.users` (`MdUserEntity`, `MdUserHooks`, `MdUserActions`; ADR-0032 §8), `MdRoleController`, `MdAssignmentController` — роли и персональные права `/api/v1/iam/users/{id}/…`, `MdOrgUnitController`, `MdSettingController` — `/api/v1/settings`, `MdCustomFieldController`, `MdI18nController` — `/api/v1/i18n`, `MdListViewController`, `MdReportWidgetController` — виджеты зрителя `/api/v1/report-widgets` (отчёт виджета строит `common.entity.report`, `MdReportWidgetService` реализует `EntityReportViews`), `NavigationItemController`, `ModuleRegistryController`; `MdSettingService` для других модулей |
| `mf` | `com.smartup24.cms.instance.mf` | Файлы: загрузка, скачивание, удаление, проверка содержимого и антивирус, хранение на диске или в S3 через SPI; прикрепления файлов к полям `FILE`/`IMAGE` записей сущностей (`MfAttachments` реализует `common.entity.EntityFiles`, ADR-0032 §4.7) | `mf_*` (в т. ч. `mf_record_files`); `mf_pub_files` | `mf` (`mf.files`) | `MfFileController` — `/api/v1/files`; `MfFileService` |
| `ms.note` | `com.smartup24.cms.instance.ms.note` | Заметки — эталонная сущность low-code: одно объявление `MsNoteEntity` ([ADR-0019](../adr/ADR-0019-low-code-entity-model.md)), записи обслуживает общий runtime ([ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §6) — своих контроллера, сервиса и репозитория нет; экран — доска `/notes` (свой экран другого способа работы, §7.2), те же записи открывает общий экран `/e/ms.notes` (§7.1) | `ms_note*`; `ms_note_pub_notes` | `notes` | runtime `/api/v1/entities/ms.notes`; `MsNoteEntity` |
| `ms.notify` | `com.smartup24.cms.instance.ms.notify` | Оповещения: входящие, настройки доставки, outbox (почта, SMS, мессенджер через провайдеров), события SSE, объявления | `ms_notification*`, `ms_announcement*` | `notify` (`notify.inbox`, `notify.preferences`, `notify.announcements`, `notify.dead_letter`) | `MsNotificationController` — `/api/v1/notifications`, `MsAnnouncementController` и `MsAnnouncementAdminController` — `/api/v1/announcements`, `MsSseController` — `/api/v1/events`; `MsOutboxWorker` |
| `ms.task` | `com.smartup24.cms.instance.ms.task` | Задачи: типы и статусы, проекты с участниками, задачи, комментарии, файлы задач; типы, статусы, проекты и задачи — сущности общего runtime (ADR-0032 §8) | `ms_task*`; `ms_task_pub_*` | `tasks` (`tasks.types`, `tasks.statuses`, `tasks.projects`, `tasks.items`, `tasks.comments`) | `/api/v1/entities/ms.task_types`, `ms.task_statuses`, `ms.projects`, `ms.tasks` (`MsTaskTypeEntity`, `MsTaskStatusEntity`, `MsProjectEntity`, `MsTaskEntity`); свои: `MsProjectController` (участники страницей, прогресс), `MsTaskController` (участники задачи, отметка «просмотрена»), `MsTaskCommentController`, `MsTaskFileController` |
| `report` | `com.smartup24.cms.instance.report` | Выгрузки списков в файлы в фоне через очередь заданий, отчёт по задачам | `report_*` | `report` (своих форм нет); опубликованные `md.profile`, `tasks.items` | `ReportExportController` — `/api/v1/exports`, `ReportController` — `/api/v1/reports`; `ReportExportJob` |
| `search` | `com.smartup24.cms.instance.search` | Полнотекстовый поиск по производному индексу Typesense: проекции, поколения индекса, сверка, настройки; индекс не источник авторизации | `search_*` | `search`; опубликованная `md.settings` | `SearchController` и `SearchManagementController` — `/api/v1/search` |
| `units` | `com.smartup24.cms.instance.units` | Единицы измерения и коэффициенты пересчёта с датой действия ([ADR-0030](../adr/ADR-0030-fnd-split.md)) | `fnd_unit*` | `units` (форм нет) | `units.api` (`Units`) |
| `upl` | `com.smartup24.cms.instance.upl` | Загрузки данных: источники и форматы, пакеты, разбор, проверка, применение в хранилище | `upl_*` | `upl` (`upl.sources`, `upl.packages`) | `UplSourceController` — `/api/v1/upl/sources`, `UplPackageController` — `/api/v1/upl/packages`, `UplUnitController`, `UplOverviewController`; `UplParseJob`, `UplApplyJob` |
| `warehouse` | `com.smartup24.cms.instance.warehouse` | Вторая база (pg-dwh): источник данных, миграции и шаг `MigrateMain`, слой raw, чтение витрин; журнал загрузок ([ADR-0030](../adr/ADR-0030-fnd-split.md)) | `fnd_load*` в базе CMS; схемы второй базы | `warehouse` (форм нет) | `warehouse.api` (`WarehouseLoads`, `RawWriter`); `warehouse.migration.MigrateMain`; `LoadCleanupJob`, `CrossDatabaseCheckJob` |
| `webhook` | `com.smartup24.cms.instance.webhook` | Исходящие вебхуки: подписки внешних систем на события, outbox доставки с подписью HMAC-SHA256, проверка цели (`FR-COMM-03`, `NFR-SEC-06`); события сущностей на runtime — из `EntityChanged` (ADR-0032, §6.9); до пункта 4.3 — `kwh` | `kwh_*` | `webhook` (`webhook.subscriptions`) | `WebhookSubscriptionController` — `/api/v1/webhooks/subscriptions`; `WebhookService.publishEvent`; `EntityWebhookListener`; `WebhookOutboxWorker` |
| `common` | `com.smartup24.cms.instance.common` | Платформа, не бизнес-модуль: сущность и реестр полей, общий runtime сущностей (`common.entity.runtime`, `store`, `hook`, `event`; ADR-0032, §6), коллекции строк, процесс и вкладки карточки (`common.entity.collection`, `workflow`; §9), модель ошибок, веб-соглашения, JSON-колонки, сроки хранения, версии с датой действия, актор аудита; не зависит от бизнес-модулей (аудит, доп. поля, файлы и выключатель модуля — через интерфейсы `EntityAuditLog`, `EntityAttributes`, `EntityFiles`, `InstalledModules`) | реестры `fnd_versioned_tables`, `fnd_audit_tables`; таблицы сущностей — по их объявлениям | любая форма по правилу (ADR-0028, п. 3) | `EntityController` (CRUD, архив и действия каждой сущности с таблицей), `EntityMenuController`, `EntityBulkController` и `EntityFileController` (файл поля записи, ADR-0032 §4.7) — `/api/v1/entities`, `FormMetaController` — `/api/v1/form-meta`, `QueryMetaController` — `/api/v1/query-meta` |
| `config` | `com.smartup24.cms.instance.config` | Конфигурация приложения, не бизнес-модуль: безопасность и фильтры, обработчик ошибок, кэш кластера, идемпотентность, OpenAPI, задание очистки журналов, такт очереди заданий, проверка схемы, health | `idempotency_keys` | любая форма по правилу (ADR-0028, п. 3) | `SystemInfoController` — `/api/v1/system`, `GlobalExceptionHandler`, `JobQueueWorker`, `SchemaVersionGate` |

## Переименование `kwh` → `webhook` (пункт 4.3)

Код модуля `kwh` объяснялся только в javadoc. С пункта 4.3 модуль называется
`webhook`: пакет `com.smartup24.cms.instance.webhook`, код модуля в
`WebhookPref.MODULE_CODE`, в каталоге форм (`md_forms.module`, миграция
`V155__webhook_module_code.sql`) и в группировке матрицы прав. Область прав
`webhook` уже носила новое имя (ADR-0028), выданные права не менялись.

Не меняются: таблицы `kwh_subscriptions`, `kwh_outbox`, `kwh_logs`
(ADR-0020), пути REST `/api/v1/webhooks/...`, имена правил хранения
`webhook-logs` и `webhook-outbox`, ключи ошибок `error.webhook.*`, настройки
`smc.webhooks.*`.

| Было | Стало |
|---|---|
| `kwh.controller.KwhSubscriptionController` | `webhook.controller.WebhookSubscriptionController` |
| `kwh.pref.KwhPref` | `webhook.pref.WebhookPref` |
| `kwh.repository.KwhOutboxRepository` (запись `KwhOutboxRecord`) | `webhook.repository.WebhookOutboxRepository` (запись `OutboxRecord`) |
| `kwh.repository.KwhSubscriptionRepository` | `webhook.repository.WebhookSubscriptionRepository` |
| `kwh.service.KwhRetentionPolicies` | `webhook.service.WebhookRetentionPolicies` |
| `kwh.service.KwhWebhookProperties` | `webhook.service.WebhookProperties` |
| `kwh.service.KwhWebhookService` | `webhook.service.WebhookService` |
| `kwh.service.WebhookTargetPolicy` | `webhook.service.WebhookTargetPolicy` |
| `kwh.worker.KwhOutboxWorker` | `webhook.worker.WebhookOutboxWorker` |

Классы и пакеты — не контракт установки: псевдонимов у старых имён нет. Тег
OpenAPI обработчиков сменился с `kwh-subscription-controller` на
`webhook-subscription-controller`; схемы и пути прежние.

## Новый модуль

1. Пакет `com.smartup24.cms.instance.<код>` с `package-info.java`: абзац о
   назначении модуля.
2. Код в `ModuleBoundariesTest.MODULES`, префикс таблиц — в
   `ModuleBoundariesTest.ownerOf`, область прав — в `PermissionAreas`
   (ADR-0028), порог покрытия — в `apps/server/coverage-floors.csv`.
3. Строка в этой карте; порядок работы — в
   [руководстве по модулям](../guidelines/module-development-guide.md).
