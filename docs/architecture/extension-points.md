# Точки расширения SmartupCMS

**Версия:** 1.7

**Обновлено:** 2026-10-02

**Основание:** [ADR-0016](../adr/ADR-0016-field-registry-query-dsl.md),
[ADR-0019](../adr/ADR-0019-low-code-entity-model.md),
[ADR-0032](../adr/ADR-0032-low-code-platform-v2.md),
[ADR-0011](../adr/ADR-0011-provider-spi.md) и текущий код.

SmartupCMS расширяется **модулями в коде**: модуль — пакет внутри монолита
(`com.smartup24.cms.instance.<префикс>.<модуль>`); экран его сущностей —
общий `/e/<код>` из метаданных, свой экран в `apps/web/src/app/features` —
только для другого способа работы (ADR-0032, §7.2). Модуль объявляет Spring-бины из списка ниже, и
платформа строит из них API, экран и служебные функции. Подключаемых во время
работы плагинов (jar/bundle) нет — это сознательное решение модульного монолита
([ADR-0006](../adr/ADR-0006-modular-monolith.md)).

Сокращения путей: `S` — `apps/server/src/main/java/com/smartup24/cms/instance`,
`W` — `apps/web/src/app`.

## 1. Сущность: объявление и записи

| Точка | Где | Что даёт |
|---|---|---|
| `EntityDefinition` (`@Bean`) через `Entity.define(...)` | `S/common/entity/Entity.java`, `S/common/entity/EntityDefinition.java` | Одно объявление сущности: таблица и псевдоним (`table`), обязательный скоуп (`scope`: `EntityScope.owner`/`orgUnit`/`all`/`custom`; без него `build()` отказывает; предикат — `EntityScopes`, правило оргединиц — `common.security.DataScopes`, его реализует `md`; ADR-0032 §5.1), поля (`field`, см. ниже), секции формы, действия с правом каждое, названия права (`EntityRights`), пункт меню (`EntityMenu`), сортировка списка по умолчанию, возможности (`EntityCapability`; `archivable()` — `ARCHIVE`: список без архивных записей, фильтр `archived`, чтение по id с `archived: true`, массовое `archive`; ADR-0032 §5.4). Отдаётся `GET /api/v1/form-meta/{code}`; список сущности (`query-meta/{code}`) выводится из тех же полей. `new EntityDefinition(...)` вне `common.entity` запрещён (`EntityFieldsSingleSourceTest`). |
| `EntityField` | `S/common/entity/field/EntityField.java`, построитель `EntityFields` | Поле объявляется **один раз** (ADR-0032, §3; план 10/10, пункт 5.1): тип (`FieldType`: `TEXT`, `TEXTAREA`, `MARKDOWN`, `NUMBER`, `DATE`, `DATETIME`, `TIME`, `BOOLEAN`, `SELECT`, `REF`, с пункта 5.2 — `EMAIL`, `PHONE`, `URL`, `MONEY`, `ENUM`, `MULTI_REF`, `FILE`, `IMAGE`, `JSON`), источник значения (`FieldSource`: колонка, выражение, вычисляемое `computed` — в форме только для чтения, атрибут в `attributes` — скалярные типы с приведением, системная колонка, пара колонок денег `money(amount, currency)`, таблица связи `link(table, owner, target)`), признаки формы (`FormPart`: обязательность, `FieldRules` — длина, диапазон, шаблон, масштаб, число элементов, размер и типы файла; `readonly()`/`readonlyOnUpdate()`/`readonlyWhen(...)`, `defaultValue(FieldDefault...)`, `visibleWhen(FieldCondition...)`), признаки списка (`ListPart`), права на поле (`FieldAccess`: `.requires(...)` — поля нет в `form-meta`, списке, выгрузке, истории, чтении и данных вебхука, запись — 422 `unknown_field`; `.readonlyUnless(...)` — поле только для чтения, изменённое значение — 422 `readonly`; применяет `EntityFieldRights`, пункт 5.3), параметры типа (`FieldOptions`: варианты, ссылка, валюты, справочник `ENUM`, корень JSON). Что нужно каждому типу, проверяет `FieldTypeRules` при старте (ключ с `password`/`secret`/`apiKey` отвергается — секреты живут в запечатанных колонках, ADR-0029). Из поля выводятся `FormField` формы и `QueryField` списка (у денег — ещё скрытое поле валюты `<key>Currency`); у типов пункта 5.2 поле списка называет тип в `format`. Поле только для чтения, если так говорит объявление **или** у зрителя нет права `readonlyUnless`: `form-meta` отдаёт `readonly: true` (нет права, `readonly()`, вычисляемое), `readonlyOnUpdate: true` (после создания) и `readonlyWhen` (условие на существующей записи); ошибка одна — `readonly`, ключ `error.field.readonly`. Каждый тип проверяют `FieldTypeMatrixTest` и `field-type-matrix.spec.ts`. |
| `EntityLists` | `S/common/entity/EntityLists.java` | Список сущности, выведенный из её полей: код = код сущности, право `<форма>.view`, `select` — системные колонки, поля под ключами записи (`<sql> as "<key>"`) и `attributes`. Реестр списков получает его через `QueryListSource`; бин `QueryList` с тем же кодом не даёт приложению стартовать. |
| Общий runtime `/api/v1/entities/{code}` | `S/common/entity/runtime/EntityController.java`, `EntityRuntime`, `EntityWrites`, `S/common/entity/store/EntityStoreRepository.java` | CRUD каждой сущности с таблицей **без кода модуля** (ADR-0032, §6; план 10/10, пункт 5.4): список (`q`, `filter`, `sort`, `limit`, `cursor`), чтение, создание (201 + `Location` + `ETag`), `PATCH` с `If-Match`, `DELETE` (необязательный `If-Match`), `PUT …/{id}/archived`, `POST …/{id}/actions/{action}`. Порядок §6.3 фиксирован: право и `If-Match` до транзакции, запись со скоупом `for update` (вне скоупа — 404), тело по полям (неизвестное, системное свойство, неверный тип JSON — 422), значения по умолчанию, readonly, правила полей, ссылки в скоупе цели, `EntityRule`, доп. поля — один 422; хуки, запись, аудит платформой, `EntityChanged` в транзакции, `afterCommit` после коммита. Транзакция — фильтра идемпотентности, если в запросе есть ключ. Тело — до 512 КБ (`EntityBodyLimitFilter`). |
| `EntityHooks` (`@Bean`) | `S/common/entity/hook/EntityHooks.java` | Второй файл автора сущности (ADR-0032, §6.5): `beforeSave` (может изменить значения через `EntityValues.set` и добавить ошибки `reject`), `afterSave`, `beforeArchive` (может отказать в архиве или восстановлении), `beforeDelete`, `afterDelete` — в транзакции, `afterCommit` — после коммита (сбой пишется в журнал и не меняет ответ). Один бин на сущность с таблицей, иначе приложение не стартует. |
| `EntityRule` | `S/common/entity/hook/EntityRule.java`, `Rules` | Кросс-полевое правило — чистая функция значений (ADR-0032, §6.6): `Entity.rule("period", Rules.notBefore("endsOn", "startsOn"))`; готовые — `notBefore` (даты или моменты времени), `requiredIf`, `atLeastOne`. Ошибки — в том же 422, что и ошибки полей. |
| `EntityActionHandler` (`@Bean`) | `S/common/entity/hook/EntityActionHandler.java` | Обработчик действия записи (ADR-0032, §6.7): `POST …/{id}/actions/{action}` с `If-Match`, запись читается со скоупом `for update`, обработчик меняет значения (`EntityActionCall`), runtime пишет, повышает ревизию, пишет аудит с `_action` и событие. Объявленное действие без обработчика не даёт приложению стартовать. |
| `EntityChanged` | `S/common/entity/event/EntityChanged.java` | Событие изменения записи (ADR-0032, §6.9): публикуется runtime в транзакции изменения; вебхуки (`S/webhook/service/EntityWebhookListener.java`) пишут `kwh_outbox` в той же транзакции, модуль слушает `@EventListener`/`@TransactionalEventListener`. |
| `EntityRecords` (`@Bean`) | `S/common/entity/EntityRecords.java` | Только для сущности **без таблицы**: видимость записи, страница, удаление. Записи сущности с таблицей держит runtime (`EntityRecordStore`); бин `EntityRecords` для неё не даёт приложению стартовать. |
| `EntityValidator` | `S/common/entity/EntityValidator.java` | Проверка сохранения по объявлению: 422 с ошибкой на каждом поле (ветка на каждый тип, типы пункта 5.2 — `FieldValueRules`); скрытое условием и вычисляемое поле не проверяются; `readonlyProblems` — поля только для чтения, которые сохранение изменило бы. Вызывает runtime (через `EntityFieldValues`) и `EntityValues.set` хука. |
| `EntityFieldValues` | `S/common/entity/EntityFieldValues.java` | Подготовка сохранения в порядке runtime (ADR-0032, §4.2–4.4): отказ на изменение поля только для чтения — по объявлению или по праву `readonlyUnless`, поле без права `requires` — 422 `unknown_field` (`EntityFieldRights`), хранимая форма значения (почта в нижнем регистре, телефон E.164), значения по умолчанию при создании (`fixed`, `now`, `today`, `current_user`, `current_org_unit` — основная оргединица автора через `DataScopes.homeUnit`, `sequence`), `null` у скрытого поля, проверка правил и проверки по БД — элемент справочника `ENUM` (новое значение не в архиве — 422 `archived`), файл, который можно прикрепить, его тип и размер. Вызывает runtime (`prepareAll` — без отказа, чтобы ответить одним 422). |
| `Entity.reference(code, name)` и `EntityEnums` | `S/common/entity/Entity.java`, `S/common/entity/EntityEnums.java` | Сущность-справочник для `ENUM` (ADR-0032, §4.5): колонка кода, колонка названия, порядок `sort_order`, до 500 элементов. Элементы читаются целиком и кэшируются (`entityEnums`, очистка по кластеру — `EntityEnums.evict`; каждое изменение записи справочника очищает его кэш сразу и после коммита — `EntityEnumEviction`); `EntityEnumResolver` даёт полю списка коды и названия во время запроса, `form-meta` — `options` (у справочника с `archivable()` — только действующие элементы) и `optionLabels` (названия всех, чтобы старое значение было подписано). |
| `EntityFiles` | `S/common/entity/EntityFiles.java` | Файлы полей `FILE`/`IMAGE` (ADR-0032, §4.7): какой файл можно положить в поле (свой загруженный или уже прикреплённый к записи), прикрепление к полю записи, снятие прикреплений удалённой записи, чтение файла через запись. Реализует модуль `mf` (`MfAttachments`, таблица `mf_record_files`); `GET /api/v1/entities/{code}/{id}/files/{fileId}` отдаёт файл, если запись видна зрителю (чтение runtime в скоупе) и файл прикреплён к ней. Прикрепления пишет и снимает runtime. |
| `FieldSource.Link` | `S/common/entity/field/FieldSource.java`, `EntityStoreRepository` | Значение `MULTI_REF` в таблице связи (ADR-0032, §4): `link(таблица, владелец, цель)`; с колонкой вида — `link(таблица, владелец, цель, колонка вида, вид)`, несколько списков одной таблицы (исполнители и наблюдатели задачи в `ms_task_members`). Строки своего вида runtime пишет и удаляет сам, строки других видов не трогает. |
| `EntityRowMapper`, `EntitySelect` | `S/common/entity/EntityRowMapper.java`, `S/common/entity/EntitySelect.java` | Как поле попадает в строку списка и обратно в значения записи: деньги — `{"amount":"1250.00","currency":"UZS"}`, несколько ссылок — массив ключей, файл — id, имя, размер и тип из `mf_pub_files`, JSON — как есть. Один `RowMapper` на все сущности. |
| `FormFieldExtender` | `S/common/entity/FormFieldExtender.java` | Поля, добавляемые в форму во время запроса. Реализация — дополнительные поля (`S/md/service/MdCustomFieldFormFields.java`). |

Возможности (`EntityCapability`) и что им нужно:

| Возможность | Требует | Что появляется |
|---|---|---|
| `CUSTOM_FIELDS` | `customEntity` (тип сущности дополнительных полей) | Дополнительные поля администратора в форме, списке, фильтре и экспорте |
| `SAVED_VIEWS` | `listCode` | Сохранённые виды списка |
| `EXPORT` | `listCode` (страницы даёт runtime) | Выгрузка списка в Excel (журнал «Мои выгрузки») |
| `HISTORY` | `auditTable` (аудит пишет runtime) | Вкладка истории изменений |
| `BULK` | хотя бы одно действие над записью | `POST /api/v1/entities/{code}/bulk`: `delete`, `archive`, `update` (поля из `params`) и любое объявленное действие записи (`params` — его параметры) над выбранными записями, каждое через одиночную операцию runtime со своим правом, проверками и хуками |

Объявление, которому не хватает нужного, не даёт приложению стартовать.

Контракт сущности в тестах (ADR-0032, §11; план 10/10, пункт 6.2): у каждой
сущности с таблицей — ровно один наследник `EntityContractTestKit`
(`apps/server/src/test/java/com/smartup24/cms/instance/support/entity`), его
требует `EntityContractCoverageTest`. Кит выводит случаи из объявления: CRUD,
ревизия, архив, права, скоуп «404, а не 403», права на поля, проверка по типам
полей, аудит, выгрузка и события (строка `kwh_outbox` на изменение, без
полей с правом; отказ — ни строки). Транспорт по умолчанию — общий runtime
`EntityTransport.runtime(code)`; `EntityTransport.module(путь)` остаётся для
сущности со своим контроллером; данные, которые кит не придумает, —
`EntityFixture`. Общие помощники тестов — `TestUsers` (пользователь
с заданными правами в своей оргединице) и `TestSession` (вход через настоящий
`/auth/login`).

## 2. Списки: реестр полей

| Точка | Где | Что даёт |
|---|---|---|
| `QueryList` (`@Bean`) | `S/common/query/QueryList.java` | Список, который не является сущностью (аудит, события безопасности, файлы, пакеты загрузок): SQL выборки, поля с типами, фильтрами, сортировкой и поиском, лимиты. Отдаётся `GET /api/v1/query-meta/{code}`; экран фильтрует и сортирует через JSON DSL, включая группы «любое из условий» и поля-ссылки (`QueryRef`). Список сущности бином не объявляется — его выводит `EntityLists`. |
| `QueryListSource` | `S/common/query/QueryListSource.java` | Списки, построенные из других объявлений (реализация — `EntityLists`); реестр отвергает совпадение их кодов с бинами `QueryList`. |
| `QueryList.withCustomFields` | там же | Дополнительные поля как поля списка. |
| `QueryListExtender` | `S/common/query/QueryListExtender.java` | Поля списка, добавляемые во время запроса (реализация — дополнительные поля). |
| `QueryFieldResolver` | `S/common/query/QueryFieldResolver.java` | Значения объявленного поля списка во время запроса (реализация — `EntityEnumResolver`: элементы `ENUM` из справочника). Типы списка пункта 5.2: `REF_SET` (несколько ссылок: `in`, `empty`, `not_empty`) и `OBJECT` (файл, JSON: только `empty`/`not_empty`); ни один не сортируется. |
| `QueryListExporter` | `S/common/query/QueryListExporter.java` | Экспорт списка, если он не берётся из объявления (`EXPORT`). |

## 3. Права, меню, языки

| Точка | Где | Что даёт |
|---|---|---|
| `@RequiresPermission(form, action)` и объявление сущности | `S/common/annotation/RequiresPermission.java`, `S/md/service/MdFormCatalogSynchronizer.java` | Источники существования права: пары аннотаций и пары объявлений (`view`, право каждого действия, формы `FieldAccess`; ADR-0032, §6.10) при старте попадают в каталог. Код формы — `<область>.<сущность>`, область называет модуль-владельца (`S/md/pref/PermissionAreas.java`, ADR-0028). |
| `EntityRights` | в объявлении | Ключи названий формы и действий в матрице прав (ADR-0031): каталог хранит русские слова, API отдаёт и ключи; тесты не дают выпустить пару без названия и ключ без перевода в ru/uz/en. |
| `EntityMenu` | в объявлении | Пункт бокового меню: подпись, иконка, раздел, порядок, модуль-выключатель и необязательный маршрут. Без маршрута пункт ведёт на общий экран `/e/<код>` (`EntityMenu.routeFor`, ADR-0032 §7.1); свой маршрут называет только свой экран другого способа работы (доска заметок — `/notes`). Отдаётся `GET /api/v1/entities/menu` по правам зрителя. |
| Модуль-выключатель | `md_installed_modules`, `S/md/service/ModuleRegistryService.java`, `S/common/module/InstalledModules.java` | Администратор включает и выключает модуль; экран охраняет `moduleActiveGuard`, runtime отвечает на сущность выключенного модуля (`EntityMenu.module`) как на неизвестную — 404. |
| Переводы | `apps/server/src/main/resources/i18n/{ru,uz,en}.json` | Ключи модуля (`nav.<код>`, подписи полей). Русский — канонический каталог; другие языки администратор добавляет в редакторе языков. |

## 4. Фоновые задания, события, интеграции

| Точка | Где | Что даёт |
|---|---|---|
| `JobHandler` (`@Bean`) | `S/jobs/api/JobHandler.java` | Задание по расписанию: `code()` и `run(args)`; расписание — строка в `fnd_job_schedule` (пример — `V121__upl_apply_recovery_job.sql`), разовый запуск — `JobQueue.enqueueOnce`. Очередь выполняет `S/config/jobs/JobQueueWorker.java`; обработчик работает вне транзакции очереди (свои короткие транзакции открывает сам) и может быть повторён после сбоя — он проверяет состояние, которое меняет. Обработчику, закрывающему свою запись при сбое, нужен `run(args, JobAttempt)`: пока попытка не последняя, временный сбой (`JobFailures.isTransient`; своё исключение модуль помечает `common.error.TransientFailure`) он пробрасывает для повтора, а сбой, который повтор не исправит, сообщает `JobNotRetryableException`. |
| События Spring | например, `S/ms/task/service/MsTaskMemberService.java` → `S/ms/notify/listener/MsTaskNotificationListener.java` | Модули общаются событиями, а не вызовами соседних сервисов. |
| Поиск | `S/search/service/SearchChangePublisher.java` | `changed(entityType, id)` в транзакции владельца ставит запись на переиндексацию. |
| Вебхуки | `S/webhook/service/WebhookService.java`, `S/webhook/service/EntityWebhookListener.java` | `publishEvent(type, payload)` доставляет событие подписчикам с подписью HMAC-SHA256. События сущностей на runtime (`<форма>.created`/`updated`/`deleted`/`archived`/`restored`/`<действие>`, например `notes.updated`) приходят без кода модуля: конверт `{id, type, occurredAt, entity, recordId, revision, changedFields, data}`, `data` — запись без полей с правом. |
| Провайдеры | `libs/provider-spi` (`StorageProvider`, `MailProvider`, `SmsProvider`, `MessengerProvider`) | Хранилище и каналы доставки; активный провайдер выбирает `S/common/provider/ProviderRegistry.java`. |

### Контракты очереди, хранилища, единиц и платформы

Бывший модуль `fnd` разделён (план 10/10, пункт 4.2,
[ADR-0030](../adr/ADR-0030-fnd-split.md)). Другие модули (сейчас `upl`,
`report` и `config.jobs`) обращаются к ним только через пакеты `api` и
`service`; правило «modules meet only through each other's service or api
package» (`ModuleBoundariesTest`) не даёт зависеть от внутренних пакетов, а
`WarehouseArchitectureTest` — очереди зависеть от хранилища.

| Тип | Вид | Реализация | Что даёт |
|---|---|---|---|
| `jobs.api.JobHandler` | интерфейс (`@Bean`) | — | Обработчик задания; `run(args, JobAttempt)` для повторов. |
| `jobs.api.JobQueue` | интерфейс | `jobs.runner.JobRunner` | `enqueueOnce(handler, args)` в транзакции вызывающего, `enqueue(scheduleCode)`. |
| `jobs.api.JobAttempt`, `JobFailures`, `JobNotRetryableException` | record, утилита, исключение | — | Номер попытки, временный ли сбой, сбой без повтора. |
| `jobs.service.JobQueries` | класс | он же | Вопросы к очереди только на чтение (`pendingArgumentValues`). |
| `warehouse.api.WarehouseLoads` | интерфейс | `warehouse.load.WarehouseLoadService` | Версии загрузок: `begin` → `apply`/`fail`, журнал пакета `log`, `find`, `appliedLoadIds`. |
| `warehouse.api.WarehouseLoad` | record | — | Версия загрузки и её статусы. |
| `warehouse.api.RawWriter`, `RawSource`, `RawRow` | интерфейсы, record | `warehouse.raw.JdbcRawWriter` | Потоковая запись строк загрузки в слой `raw` pg-dwh (`copy`, `count`, `read`). |
| `warehouse.api.WarehouseUnavailableException` | исключение | — | pg-dwh недоступна (503, временный сбой для очереди); пустой результат вместо данных не возвращается. |
| `units.api.Units` | интерфейс | `units.service.UnitService` | Единицы измерения и пересчёт по датированному коэффициенту. |
| `units.api.Unit`, `UnitConversion`, `CoefficientMissingException` | record, record, исключение | — | Единица, результат пересчёта со ссылкой на коэффициент, отказ без коэффициента (409). |
| `common.versioning.Versions` | интерфейс | `common.versioning.VersioningService` | Версии с датой действия для таблицы, объявленной через `fnd_versioning_enable`: `createDraft`, `updateDraft`, `publish`, `supersede`, `versionAt`, `find`. |
| `common.versioning.Version`, `StaleVersionException`, `VersionErrors` | record, исключение, утилита | — | Строка таблицы версий; устаревшая блокировка; перевод ошибок записи в таблицу версий модуля. |
| `common.actor.AuditActorContext` | интерфейс | `md.service.MdAuditActors` | Актор (`system()`, `user(id)`) и его установка в транзакцию (`apply`) для `fnd_audit_trigger`. |
| `common.actor.AuditActor` | record | — | Кто выполняет операцию: id в `md_users` и имя для журналов. |
| `common.error.ConstraintCode`, `ConstraintCodes`, `ConstraintErrors`, `ConstraintViolationException` | интерфейсы, утилита, исключение | перечни `JobError`, `UnitError`, `WarehouseError`, `VersionError`, `ActorError` | Код модуля для ограничения его таблицы или текста триггера; ответ с ключом `error.fnd.<код>` (ключи не менялись, ADR-0021). |

Правила контракта:

- Вызывающий модуль внедряет интерфейс (`WarehouseLoads`, а не
  `WarehouseLoadService`); реализация остаётся единственным бином.
- Новая потребность другого модуля — новый метод интерфейса или новый тип в
  пакете `api` нужного модуля, а не импорт внутреннего пакета (`runner`,
  `load`, `raw`, `mart`, `migration`, `datasource`, `repository`).
- SQL — только в репозиториях: класс `*Service` запросов не пишет
  (`ServicesRunNoSqlTest`).
- Модуль, чью таблицу версий пишет `common.versioning`, публикует свои коды
  бином `ConstraintCodes` (пример — `units.config.UnitsConfig`).
- `warehouse.mart.MartReader` пока не входит в контракт: им пользуется только
  хранилище. Когда чтение витрин понадобится модулю, в `warehouse.api`
  появится интерфейс.

## 5. Контракт API и платформенные сервисы

Как это выглядит для клиента — [docs/api/README.md](../api/README.md);
правила для автора модуля — в
[руководстве](../guidelines/module-development-guide.md#серверные-правила).

| Точка | Где | Что даёт |
|---|---|---|
| `ApiException` + `ErrorCode` | `S/common/error/ApiException.java`, `libs/core-types/.../core/error/ErrorCode.java` | Ошибка запроса: код, ключ каталога `error.<модуль>.<имя>`, параметры; `GlobalExceptionHandler` отвечает `application/problem+json` на языке запроса ([ADR-0021](../adr/ADR-0021-error-model.md)). Ключ — в ru, uz и en (`ErrorTextsTest`). |
| `Revisioned` / `Revisions` | `S/common/web/Revisioned.java`, `S/common/web/Revisions.java` | Ответ-record с `revision()` получает `ETag` (`S/config/web/RevisionETagAdvice.java`); `Revisions.required(ifMatch)` — ревизия из `If-Match` или 428, `Revisions.conflict()` — 409 ([ADR-0024](../adr/ADR-0024-optimistic-locking.md)). |
| `Created` | `S/common/web/Created.java` | `Created.at("/api/v1/<путь>/{id}", id, body)` — 201 с `Location` ([ADR-0023](../adr/ADR-0023-uniform-rest.md)). |
| `ApiDeprecations` | `S/common/web/ApiDeprecations.java` | Единый список устаревших путей и параметров: по нему `S/config/web/DeprecatedApiFilter.java` отвечает с `Deprecation`, `Sunset`, `Link`, а описание API помечает их `deprecated` (ADR-0023). Сейчас таблица `CURRENT` пуста (ADR-0023, §5); новый псевдоним — `ApiDeprecations.alias(...)` в её списке путей, параметр — строка в её словаре параметров. |
| `KeysetPage` | `libs/core-types/.../core/pagination/KeysetPage.java` | Страница коллекции: `items`, `nextCursor`, `hasMore`, `totalEstimated`, `totalExact` (план 10/10, пункт 3.5). |
| `TimePage` | `S/common/query/TimePage.java` | Страница коллекции вне реестра полей по времени и id: `TimePage.of(limit, cursor, default, max)` (422 выше максимума), `page(rows, position)`. |
| `QueryList.withEstimatedTotal()` | `S/common/query/QueryList.java` | Список реестра над таблицей без предела отдаёт оценку планировщика вместо подсчёта (`totalExact: false`, в интерфейсе «≈ N»). |
| `JsonColumns` | `S/common/json/JsonColumns.java` | Чтение и запись `jsonb` с общим `ObjectMapper`; сбой — ошибка с именем таблицы, а не пустой `{}` (план 10/10, пункт 3.11). |
| `RetentionPolicy` (`@Bean`) | `S/common/retention/RetentionPolicy.java` | Срок хранения журнальной таблицы: имя, таблица, условие с `:cutoff`, срок по умолчанию; `S/config/retention/RetentionJob.java` удаляет устаревшие строки ночью, срок меняет `smc.retention.days.<имя>` ([ADR-0025](../adr/ADR-0025-retention-and-cluster-cache.md)). |
| Имя кэша | `S/config/cache/CacheConfig.java` | Кэш объявляется константой и в `setCacheNames`; `ClusterCacheManager` рассылает его очистку всем узлам после коммита (`NOTIFY smc_cache`, ADR-0025). Кэш не из списка не создаётся. |

## 6. Интерфейс

| Точка | Где | Что даёт |
|---|---|---|
| Общий экран `/e/:code` | `W/shared/entity/page/` (`entity.routes.ts`, `smt-entity-page`, `smt-entity-list-page`, `smt-entity-edit-page`, `smt-entity-record-page`), `W/shared/entity/page/entity.guard.ts` | Экран любой объявленной сущности **без кода веба** (ADR-0032, §7.1; план 10/10, пункт 5.5): `/e/<код>` — список (`ui-server-table` по `query-meta`, фильтр, сохранённые виды, выгрузка, переключатель архива, массовые архив и удаление, «Создать» — всё по возможностям и `actions`), `/new` и `/:id/edit` — форма (`smt-entity-form`, `If-Match`, 422 под полями, 409/428 — `SaveErrorNotifier` с перечитыванием), `/:id` — карточка (поля по секциям, файлы полей через запись, вкладка истории, кнопки по `actions` записи: правка, архив, удаление, действия по коду). `form-meta` читается один раз на сущность (`EntityPageContext`); неизвестная, чужая сущность — «не найдено» по 404 сервера; `entityGuard` закрывает экран выключенного модуля по `EntityMenu.module`. Данные — `EntitiesApi` (`page`, `get`, `create`, `patch`, `remove`, `setArchived`, `action`). |
| `provideEntityOverrides(code, {cells, fields, sections, tabs})` | `W/shared/entity/page/entity-overrides.ts`, провайдеры в `apps/web/src/main.ts` | Точечная правка общего экрана без своего экрана (ADR-0032, §7.2): ячейка списка (входы `row`, `field`), контрол поля формы (`field`, `value`, `problem`, `disabled`, `set`), секция карточки (`meta`, `record`, `values`), своя вкладка карточки (`key`, `labelKey`, компонент со входами `meta`, `record`). Несколько вызовов для одной сущности складываются. |
| Помощники тестов общего экрана | `apps/web/src/testing/entity-page.ts` | `renderEntityScreen(url, {meta, list, records, …})` — страница настоящим роутером поверх API из фикстур, `queryMetaFixture`, `entityRecord`, `problem`. |
| `smt-entity-form` | `W/shared/entity/smt-entity-form.component.ts` | Форма по `form-meta`; отдельное поле заменяется шаблоном `smtEntityField` или компонентом из `[controls]` (так его заменяет `provideEntityOverrides`). Контрол каждого типа — `ENTITY_CONTROLS` (почта, телефон, адрес — поля ввода своего вида; `smt-money-field`, `smt-file-field`, `smt-multi-data-select`, JSON текстом); поле скрывается по `visibleWhen`, блокируется, если только для чтения (`[recordId]` — запись уже есть). Правила значений по типам — `W/core/services/field-values.ts` (`FIELD_VALUE_RULES`), слова на карточке и в истории — `FIELD_TEXT`. |
| `smt-entity-card` | `W/shared/entity/smt-entity-card.component.ts` | Просмотр записи по раскладке; с `[recordId]` поле `FILE`/`IMAGE` — ссылка на файл через запись. |
| `smt-entity-toolbar` | `W/shared/entity/smt-entity-toolbar.component.ts` | Виды, экспорт, переключатель архива, архив и удаление выбранных — по возможностям и правам; `[listTools]="false"` оставляет виды и выгрузку таблице, `[clearable]="false"` — снятие выбора её панели. |
| Помощники тестов формы | `apps/web/src/testing/entity-form.ts` | `formMetaFixture`, `renderEntityForm`, `EntityFormHarness` (заполнить поле по ключу и типу, прочитать ошибку и признак только для чтения) для спеков экранов на `smt-entity-form` (план 10/10, пункт 6.2). |
| `ui-server-table` + `registryTableConfig` | `W/shared/ui/ui-server-table.component.ts`, `W/shared/ui/registry-table-config.ts` | Таблица по `query-meta`: колонки, сортировка, фильтр, курсор. |
| UI kit | `W/shared/ui-kit` | Кнопки, диалоги, таблицы, поля (в том числе `smt-dynamic-field`, `smt-data-select`), загрузка файлов. |
| Маршрут своего экрана | `W/app.routes.ts` | Только для экрана другого способа работы (ADR-0032, §7.2): lazy `loadComponent` с `moduleActiveGuard` и `permissionGuard`. Сущности без своего экрана маршрут не нужен — её открывает общий `/e/:code`. |
| Пункты меню администратора | `md_navigation_items` | Внутренний маршрут, внешняя ссылка или встраивание (`EMBEDDED_IFRAME`, открывается на `/embed/:code`). |

## 7. Известные пробелы

Честный список того, что ещё не является точкой расширения:

1. **Поиск не подключается декларативно.** Коллекции Typesense заданы для
   задач, проектов и пользователей; новой сущности нужен код в модуле `search`.
2. **Вебхуки — только у сущностей на runtime.** События сущностей приходят из
   `EntityChanged`; модули вне runtime (задачи, проекты) `publishEvent` пока не
   вызывают. Каталог событий подписки (`GET /api/v1/webhooks/events`) и подпись
   метки времени (В9) — не сделаны.
3. **Runtime обслуживает заметки.** Задачи, проекты и пользователи ещё
   не переведены на `EntityDefinition` и runtime (пункт 5.6); подписей ссылок
   (`labels`) в ответе runtime нет — общий экран называет ссылку отдельным
   чтением её цели (`RefLookups`, по запросу на значение).
4. **Встраивание внешнего приложения — только iframe** из пункта меню, без SSO
   и передачи сессии.
5. **Маршрут своего экрана добавляется в `app.routes.ts` вручную.** Сущности
   экран даёт общий маршрут `/e/:code` (пункт 5.5); динамической регистрации
   своих экранов (доска, календарь) нет.
6. Прежняя модель «Plugin SDK» (таблица `md_custom_modules`, V017, выключена
   в V019) удалена миграцией V152 (план 10/10, пункт 4.7); модули
   регистрируются только в `md_installed_modules`.
