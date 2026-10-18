# Карта модулей сервера

Одна строка — один модуль сервера `apps/server`
(`com.smartup24.cms.instance.*`): что он делает, какими таблицами владеет,
какие области прав проверяет и откуда в него входят. Список бизнес-модулей
задаёт `ModuleBoundariesTest.MODULES`; `common` и `config` — инфраструктура.
`jobs`, `units` и `warehouse` — инфраструктурные модули
(`ModuleBoundariesTest.INFRASTRUCTURE`): они, как и `common`, не зависят от
бизнес-модулей. Сборка `config` видит модули только через их `api` и `service`,
и от неё не зависит ни один модуль; те же правила по импортам исходников
(включая константы, которых нет в байт-коде) проверяет
`ModuleSourceBoundariesTest` (план 10/10, пункт 1.3).
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
| `analytics` | `com.smartup24.cms.instance.analytics` | Показатели дашборда: сводка задач, тренды, нагрузка, распределение по проектам; читает `ms_task_pub_*` и `md_pub_users`, каждую цифру — только по записям области зрителя (предикаты `MdScopeService`, ADR-0013 §2.5). Виджеты зрителя на том же экране — отчёты списков сущностей без кода (ADR-0032, §10.2), их строит общий runtime, а не этот модуль | своих нет | `analytics` (`analytics.dashboard`) | `AnalyticsController` — `/api/v1/analytics` |
| `audit` | `com.smartup24.cms.instance.audit` | Секционированный журнал изменений, журнал событий безопасности, история записи, архив старых секций на диск или в S3 | `audit_log*`, `security_events` | `audit` (`audit.log`); опубликованная `md.profile` | `AuditLogController` — `/api/v1/audit`, `RecordHistoryController` — `/api/v1/history`; `AuditLogService` для других модулей; `AuditPartitionWorker`, `AuditArchiveWorker` |
| `example` | `com.smartup24.cms.instance.example` | Эталонные модули low-code и [cookbook](../cookbook/README.md) ([ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §9.4; план 10/10, пункт 6.6), у каждого — объявление без своего экрана: справочник «товары» (`ExampleProductsEntity`: код, единица, цена, архив, импорт по коду, поиск), документ со строками «заказы» (`ExampleOrderEntity`: строки, итог, процесс «черновик → проведён → отменён») и документ со статусами «заявки» (`ExampleRequestsEntity` и `ExampleRequestsHooks`: ссылка на товар, процесс «черновик → на рассмотрении → утверждена или отклонена», право на поле решения); в поставке выключен (§19, В3) | `ex_*` | `example` (`example.products`, `example.orders`, `example.requests`) | runtime `/api/v1/entities/example.products` (`ExampleProductsEntity`), `/api/v1/entities/example.orders` (строки `lines`, переходы `post`, `unpost`, `cancel`; `ExampleOrderEntity`), `/api/v1/entities/example.requests` (переходы `submit`, `recall`, `approve`, `reject`; `ExampleRequestsEntity`) |
| `jobs` | `com.smartup24.cms.instance.jobs` | Общая очередь фоновых заданий: аренда, повторы, расписание, история запусков ([ADR-0030](../adr/ADR-0030-fnd-split.md)) | `fnd_job_*` | `jobs` (форм нет) | `jobs.api` (`JobQueue`, `JobHandler`); такт очереди — `config.jobs.JobQueueWorker` через `jobs.api.JobQueueDriver`, выключатель `jobs_enabled` — `config.jobs.JobSwitchConfiguration` |
| `kauth` | `com.smartup24.cms.instance.kauth` | Аутентификация: вход по паролю и одноразовому коду, сессии, API-токены, смена и сброс пароля, приглашение нового пользователя (`UserInvitations` модуля `md`), административный список настроенных OAuth2/SSO-провайдеров (сам вход через них не реализован), каналы получения кодов | `kauth_*`, `md_sso_providers` | `kauth` (своих форм нет); опубликованные `md.profile`, `md.users`, `md.settings` | `KauthAuthController` — `/api/v1/auth`, `OAuth2AuthController`, `KauthSessionController`, `KauthApiTokenController`, `KauthChannelController`, `KauthPasswordController`, `KauthPasswordResetController` |
| `md` | `com.smartup24.cms.instance.md` | Мастер-данные: пользователи, роли и права, оргструктура и область данных, настройки, динамические поля, языки и переводы, виды списков (таблица, отчёт, виджет — [ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §10.2), меню, реестр модулей, черновики и пакеты Студии ([ADR-0035](../adr/ADR-0035-declaration-lifecycle.md)) | `md_*` (кроме `md_sso_providers`); `md_pub_users` | `md` (`md.users`, `md.profile`, `md.roles`, `md.assignments`, `md.org_units`, `md.settings`, `md.navigation`, `md.modules`, `md.custom_fields`) | сущность `md.users` на общем runtime — `/api/v1/entities/md.users` (`MdUserEntity`, `MdUserHooks`, `MdUserActions`; ADR-0032 §8), `MdRoleController`, `MdAssignmentController` — роли и персональные права `/api/v1/iam/users/{id}/…`, `MdOrgUnitController` (в том числе `/api/v1/iam/org-units/scope` — подразделения области текущего пользователя), `MdSettingController` — `/api/v1/settings`, `MdCustomFieldController`, `MdI18nController` — `/api/v1/i18n`, `MdListViewController`, `MdReportViewController` — каталог отчётов `/api/v1/report-views` (`q`, `owner`, `kind`) и доступ ролей, `MdReportWidgetController` — виджеты зрителя `/api/v1/report-widgets`, `MdEntityTexts` реализует `common.entity.EntityTexts` (отчёт виджета строит `common.entity.report`, `MdReportWidgetService` реализует `EntityReportViews`), `NavigationItemController`, `ModuleRegistryController`, `MdFormStudioController` — черновики и версии `/api/v1/form-studio/drafts/{code}`, `MdSimpleFormController` / `MdSimpleFormDraftController` — подготовка create-only пакета и проверка локального черновика `/api/v1/form-studio/new-forms/{prepare,check-draft}` без записи схемы ([ADR-0037](../adr/ADR-0037-simple-form-cli-delivery.md)); `MdSettingService` для других модулей |
| `mf` | `com.smartup24.cms.instance.mf` | Файлы: загрузка, скачивание, удаление, проверка содержимого и антивирус, хранение на диске или в S3 через SPI; прикрепления файлов к полям `FILE`/`IMAGE` записей сущностей (`MfAttachments` реализует `common.entity.EntityFiles`, ADR-0032 §4.7) | `mf_*` (в т. ч. `mf_record_files`); `mf_pub_files` | `mf` (`mf.files`) | `MfFileController` — `/api/v1/files`; `MfFileService` |
| `ms.note` | `com.smartup24.cms.instance.ms.note` | Заметки — эталонная сущность low-code: одно объявление `MsNoteEntity` ([ADR-0019](../adr/ADR-0019-low-code-entity-model.md)), записи обслуживает общий runtime ([ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §6) — своих контроллера, сервиса и репозитория нет; экран — доска `/notes` (свой экран другого способа работы, §7.2), те же записи открывает общий экран `/e/ms.notes` (§7.1) | `ms_note*`; `ms_note_pub_notes` | `notes` | runtime `/api/v1/entities/ms.notes`; `MsNoteEntity` |
| `ms.notify` | `com.smartup24.cms.instance.ms.notify` | Оповещения: входящие, настройки доставки, outbox (почта, SMS, мессенджер через провайдеров), события SSE, объявления | `ms_notification*`, `ms_announcement*` | `notify` (`notify.inbox`, `notify.preferences`, `notify.announcements`, `notify.dead_letter`) | `MsNotificationController` — `/api/v1/notifications`, `MsAnnouncementController` и `MsAnnouncementAdminController` — `/api/v1/announcements`, `MsSseController` — `/api/v1/events`; `MsOutboxWorker` |
| `ms.task` | `com.smartup24.cms.instance.ms.task` | Задачи: типы и статусы, проекты с участниками, задачи, комментарии, файлы задач; типы, статусы, проекты и задачи — сущности общего runtime (ADR-0032 §8) | `ms_task*`; `ms_task_pub_*` | `tasks` (`tasks.types`, `tasks.statuses`, `tasks.projects`, `tasks.items`, `tasks.comments`) | `/api/v1/entities/ms.task_types`, `ms.task_statuses`, `ms.projects`, `ms.tasks` (`MsTaskTypeEntity`, `MsTaskStatusEntity`, `MsProjectEntity`, `MsTaskEntity`); свои: `MsProjectController` (участники страницей, прогресс), `MsTaskController` (участники задачи, отметка «просмотрена»), `MsTaskCommentController`, `MsTaskFileController` |
| `report` | `com.smartup24.cms.instance.report` | Выгрузки списков в файлы в фоне через очередь заданий, импорт записей сущностей из xlsx (журнал, задание, шаблон, отчёт ошибок; строки — через runtime `EntityImporter`), отчёт по задачам; ограниченный агрегированный XLSX через `AggregateExportService` (ADR-0036) | `report_*` | `report` (своих форм нет); опубликованные `md.profile`, `tasks.items` | `ReportExportController` — `/api/v1/exports`, `ReportImportController` — `/api/v1/entities/{code}/import-template`, `/api/v1/entities/{code}/imports`, `/api/v1/imports/{id}`, `ReportController` — `/api/v1/reports`; `AggregateExportController` — `/api/v1/entities/{code}/report/export`, `/api/v1/entities/{code}/reports/{viewId}/export`, `/api/v1/dashboards/{id}/widgets/{viewId}/export`; `ReportExportJob`, `ReportImportJob`, `ReportImportCleanupJob` |
| `search` | `com.smartup24.cms.instance.search` | Полнотекстовый поиск по производному индексу Typesense: коллекция на каждую сущность с `SEARCH` (`search_generation_collections`), поколения индекса, сверка, настройки; индекс не источник авторизации — находки перепроверяются скоупом сущности | `search_*` | `search`; опубликованная `md.settings` | `SearchController` и `SearchManagementController` — `/api/v1/search` |
| `units` | `com.smartup24.cms.instance.units` | Единицы измерения и коэффициенты пересчёта с датой действия ([ADR-0030](../adr/ADR-0030-fnd-split.md)) | `fnd_unit*` | `units` (форм нет) | `units.api` (`Units`) |
| `upl` | `com.smartup24.cms.instance.upl` | Загрузки данных: источники и форматы, пакеты, разбор, проверка, применение в хранилище | `upl_*` | `upl` (`upl.sources`, `upl.packages`) | `UplSourceController` — `/api/v1/upl/sources`, `UplPackageController` — `/api/v1/upl/packages`, `UplUnitController`, `UplOverviewController`; `UplParseJob`, `UplApplyJob` |
| `warehouse` | `com.smartup24.cms.instance.warehouse` | Вторая база (pg-dwh): источник данных, миграции и шаг `MigrateMain`, слой raw, чтение витрин; журнал загрузок ([ADR-0030](../adr/ADR-0030-fnd-split.md)) | `fnd_load*` в базе CMS; схемы второй базы, `raw.rows` секционирована по загрузке (`raw.rows_<load_id>`, ADR-0030, раздел 7) | `warehouse` (форм нет) | `warehouse.api` (`WarehouseLoads`, `RawWriter`); `warehouse.migration.MigrateMain`; `LoadCleanupJob`, `CrossDatabaseCheckJob` |
| `webhook` | `com.smartup24.cms.instance.webhook` | Исходящие вебхуки: подписки внешних систем на события, outbox доставки с подписью HMAC-SHA256, проверка цели (`FR-COMM-03`, `NFR-SEC-06`); события сущностей на runtime — из `EntityChanged` (ADR-0032, §6.9); охват подписки `creator`/`all` — записи, видимые автору подписки (право `view` через `MdPermissionService`, область — `EntityReads.inScopeOf`; удаление — по `common.entity.EntityRemoving`), или все (ADR-0041, решение 2026-10-18); до пункта 4.3 — `kwh` | `kwh_*` | `webhook` (`webhook.subscriptions`) | `WebhookSubscriptionController` — `/api/v1/webhooks/subscriptions` (DTO — `webhook.api.WebhookDtos`); `WebhookService.publishEvent`; `EntityWebhookListener`; `WebhookOutboxWorker` |
| `studio` | `com.smartup24.cms.instance.studio` | Живые формы без deploy ([ADR-0038](../adr/ADR-0038-live-simple-forms.md)): неизменяемые версии, совместимые изменения, архив и восстановление, справочники администратора, поля выбора, ссылки и пользователь, область данных формы, раскладка (разделы, вкладки) и заголовок записи формата 4 ([ADR-0041](../adr/ADR-0041-live-form-evolution.md)), формулы формата 5 — вычисляемые поля, умолчания, условия видимости, обязательности и только чтения, правила проверки ([ADR-0042](../adr/ADR-0042-live-form-formulas.md)), документы формата 6 — строки, номер, статусы с согласованием ([ADR-0043](../adr/ADR-0043-live-form-documents.md)); общий runtime, без online DDL | `studio_forms` (версия 1), `studio_form_versions`, `studio_form_state`, `studio_records` (с `form_version`, `org_unit_id`, `record_number`, `doc_number`), `studio_form_counters`, `studio_form_number_counters`, `studio_record_lines_1..3`, `studio_form_quarantine`, `studio_lists`, `studio_list_items` | `studio.forms.publish`, `studio.forms.assist` + `md.settings.update`; `studio.lists` (`view`, `manage`); CRUD динамических `studio.*` | `StudioFormController` — `/api/v1/studio/{activation,forms}`; `StudioFormVersionController` — `/api/v1/studio/forms/{code}`, `/versions`, `/archive`, `/restore`; запись архивной формы закрыта общим `EntityWriteLock`; `StudioQuarantineController` — `/api/v1/studio/catalog` и `/quarantine` (карантин сохранённых форм); `StudioListController` — справочники `/api/v1/studio/lists`, их значения — перечисления runtime через `StudioListEnums` (`common.entity.EntityEnumSource`); `StudioAssistantController` — `/api/v1/studio/assistant` и `/drafts`, несохранённое предложение по ADR-0039; `StudioDefinitions` — request snapshot (`StudioSources` читает все декларации до компиляции: ссылки называют строки полем заголовка цели); `StudioTitles` реализует `common.entity.EntityTitles` (`titleField` в form-meta); `StudioFormLogic` компилирует логику формата 5 в `common.entity.EntityLogic` декларации и правила сущности; `StudioFormulaController` — проверка одной формулы `/api/v1/studio/formulas/check`; `StudioDocumentCompiler` — строки, номер и процесс формата 6, `StudioDocumentHooks` — номер, комментарий перехода и уведомление (`common.entity.EntityNotice`, доставляет `ms.notify`); записи `/api/v1/entities/studio.<code>`, переходы — `/actions/<code>` |
| `common` | `com.smartup24.cms.instance.common` | Платформа, не бизнес-модуль: сущность и реестр полей, общий runtime сущностей (`common.entity.runtime`, `store`; ADR-0032, §6) по объявлениям публичного API `libs/platform-api` (хуки, события, строки документа, процесс — `platform.api.entity.*`, ADR-0033), проверка манифестов модулей (`common.module`, `config.module`) и схемы сущностей (`EntitySchemaCheck`), модель ошибок, веб-соглашения, JSON-колонки, пределы чтения загруженных xlsx (`common.xlsx`, план 10/10, п. 7.6), язык формул (`common.formula`) и логика формы в формулах на всех путях записи (`EntityLogic`, `EntityLogicRun`, часовой пояс запроса `common.web.RequestZone`; ADR-0042), сроки хранения, версии с датой действия, актор аудита; не зависит от бизнес-модулей (аудит, доп. поля, файлы, выключатель модуля, заголовок записи и словарь — через интерфейсы `EntityAuditLog`, `EntityAttributes`, `EntityFiles`, `InstalledModules`, `EntityTitles`, `EntityTexts`; отчёты по спискам — `common.entity.report`: порядок групп и top-N, подписи групп `EntityReportLabels` — ADR-0036 §7) | реестры `fnd_versioned_tables`, `fnd_audit_tables`; таблицы сущностей — по их объявлениям | любая форма по правилу (ADR-0028, п. 3) | `EntityController` (CRUD, архив и действия каждой сущности с таблицей), `EntityMenuController`, `EntityBulkController` и `EntityFileController` (файл поля записи, ADR-0032 §4.7) — `/api/v1/entities`, `FormMetaController` — `/api/v1/form-meta`, `QueryMetaController` — `/api/v1/query-meta` |
| `config` | `com.smartup24.cms.instance.config` | Конфигурация приложения, не бизнес-модуль: безопасность и фильтры, наблюдаемость (маскирование JSON-логов, span JDBC — `config.observability`), обработчик ошибок, кэш кластера, идемпотентность, OpenAPI, задание очистки журналов, такт очереди заданий, проверка схемы, health | `idempotency_keys` | любая форма по правилу (ADR-0028, п. 3) | `SystemInfoController` — `/api/v1/system`, `GlobalExceptionHandler`, `JobQueueWorker`, `SchemaVersionGate` |

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

## Публичный API и модули вне монорепо

Объявление сущности, хуки, правила, действия и события — артефакт
`libs/platform-api` (`com.smartup24.cms.platform.api..`, ADR-0033); модули
сервера импортируют их оттуда. Модуль вне монорепо — jar с манифестом
`META-INF/smartupcms/modules/<код>.json` на classpath сервера; строки в этой
карте у него нет (карта описывает пакеты `apps/server`), его код и версия видны
в реестре модулей. Образец — `examples/external-module` (модуль `library`).

Провайдеры почты, SMS, мессенджеров, хранилища файлов и проверки файлов —
SPI `libs/provider-spi` (`com.smartup24.cms.spi..`,
[ADR-0011](../adr/ADR-0011-provider-spi.md)); он версионируется вместе с
`libs/platform-api`, и совместимость обоих артефактов проверяет japicmp при
сборке (ADR-0033, 4.1).

## Состав low-code framework

Решение владельца продукта от 2026-10-12: ядро и отключаемые прикладные
модули. Отключение не удаляет данные и не выгружает Java-классы.

| Слой | Состав | Почему остаётся |
|---|---|---|
| Ядро и администрирование | `common`, `config`, `kauth`, `md`, `audit`, `mf`, `ms.notify`, `jobs`, `report`, `search`, `webhook` | runtime, безопасность, эксплуатация и расширения, нужные разным приложениям |
| Прикладные модули | `ms.task` (реестр `tasks`, включая проекты и task-аналитику), `ms.note` (`notes`), `upl` | рабочие приложения и примеры платформы; администратор может отключить |
| Инфраструктура загрузок | `units`, `warehouse` | общие контракты единиц и второй БД, используются UPL; удаление ломает действующий pipeline |
| Эталоны | `example` | проверяемые рецепты low-code, выключены в обычной поставке; demo включает их явно |

Общая страница аналитики также содержит пользовательские отчётные виджеты
ядра. Выключение `tasks` убирает task-аналитику, но не эти виджеты.
UPL после выключения не принимает новые пользовательские операции;
уже принятые задания, восстановление и очистка завершаются штатно.

## Новый модуль

1. Пакет `com.smartup24.cms.instance.<код>` с `package-info.java`: абзац о
   назначении модуля.
2. Код в `ModuleBoundariesTest.MODULES`, префикс таблиц — в
   `ModuleBoundariesTest.ownerOf`, область прав — в `PermissionAreas`
   (ADR-0028), порог покрытия — в `apps/server/coverage-floors.csv`.
3. Манифест `apps/server/src/main/resources/META-INF/smartupcms/modules/<код>.json`,
   если модуль заводит строку реестра модулей (ADR-0033, §6.4).
4. Строка в этой карте; порядок работы — в
   [руководстве по модулям](../guidelines/module-development-guide.md).

STU-03 / ADR-0036: модуль `md` владеет общими назначениями отчётов и именованными
панелями (`md_list_view_roles`, `md_dashboards`, `md_dashboard_roles`);
`MdReportViewController` обслуживает каталог и назначения, `MdDashboardController` —
панели и результаты карточек. `common.entity.report.EntityDashboardReports` отделяет
потребителя экспорта от `md`; зависимость платформы от бизнес-модуля не добавляется.
