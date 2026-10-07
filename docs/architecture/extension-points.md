# Точки расширения SmartupCMS

**Версия:** 1.8

**Обновлено:** 2026-10-03

**Основание:** [ADR-0016](../adr/ADR-0016-field-registry-query-dsl.md),
[ADR-0019](../adr/ADR-0019-low-code-entity-model.md),
[ADR-0032](../adr/ADR-0032-low-code-platform-v2.md),
[ADR-0011](../adr/ADR-0011-provider-spi.md),
[ADR-0033](../adr/ADR-0033-platform-api-and-module-manifest.md) и текущий код.

SmartupCMS расширяется **модулями в коде**: встроенный модуль — пакет внутри
монолита (`com.smartup24.cms.instance.<префикс>.<модуль>`), сторонний — jar
вне монорепо, собранный против публичного API платформы и положенный на
classpath сервера (ADR-0033, §6); экран его сущностей — общий `/e/<код>` из
метаданных, свой экран в `apps/web/src/app/features` — только для другого
способа работы (ADR-0032, §7.2). Модуль объявляет Spring-бины из списка ниже, и
платформа строит из них API, экран и служебные функции. Горячей загрузки и
изоляции модулей нет: модуль подключается при старте, его манифест проверяется
до создания бинов.

Сокращения путей: `S` — `apps/server/src/main/java/com/smartup24/cms/instance`,
`A` — `libs/platform-api/src/main/java/com/smartup24/cms/platform/api`
(публичный API), `W` — `apps/web/src/app`.

## 0. Публичный API платформы и манифест модуля

| Точка | Где | Что даёт |
|---|---|---|
| Артефакты API | `libs/platform-api` (`com.smartup24.cms:platform-api`, пакеты `com.smartup24.cms.platform.api..`), `libs/provider-spi` (`com.smartup24.cms.spi..`) | Всё, против чего собирается сторонний модуль (ADR-0033, §3): объявление сущности, поля, скоуп, хуки, правила, действия, события, процесс, строки документа, импорт и поиск; провайдеры. Остальное под `com.smartup24.cms.instance..` — реализация. Артефакт API зависит только от JDK и `org.jspecify`. |
| `@PlatformApi(since, stability)` | `A/PlatformApi.java`, `A/Stability.java` | Метка каждого публичного типа API: версия появления и уровень `STABLE`/`EXPERIMENTAL` (ADR-0033, §5). `PlatformApiContractTest`, `ProviderSpiContractTest`: 100% публичных типов помечены, нет зависимостей вне JDK и API, ничего STABLE из базовой линии не исчезло без `@Deprecated`. |
| Версия API | `platform-api.version` в корневом `pom.xml`, `A/PlatformVersion.java` | SemVer API, отдельная от версии приложения; japicmp в `verify` сравнивает API с jar последней выпущенной версии (`libs/<артефакт>/baseline`) и валит сборку при несовместимом изменении без новой MAJOR-версии; изменения — в [журнале SPI](../api/spi-changelog.md). |
| `EntityRefusal` | `A/entity/hook/EntityRefusal.java` | Отказ хука или обработчика действия целиком (403, 409, 422 с ключом текста): сторонний модуль не видит `ApiException`; ответ — тот же `application/problem+json`. |
| Манифест модуля | `META-INF/smartupcms/modules/<код>.json` в jar модуля (встроенные — в `apps/server/src/main/resources`) | Код, название, версия, `minPlatform`, зависимости; у стороннего — `configuration` (класс `@Configuration`), `migrations` (свои Flyway-миграции, история `flyway_module_<код>`), `messages` (ключи ru/uz/en). Проверяется до создания бинов (`config.module.ModuleManifestSelector`): модуль, которому нужна более новая платформа или которого зависимость нет, не даёт приложению стартовать (`ModuleManifestFailureAnalyzer` называет модуль и версии). Реестр модулей показывает версию, `minPlatform` и зависимости из манифеста; манифест без строки реестра получает её при старте. |
| Проверка схемы | `S/common/entity/EntitySchemaCheck.java`, `S/config/db/EntitySchemaGate.java` | Объявление каждой сущности сравнивается с `information_schema` при старте: таблицы (сущности, строк, связей), колонки и их типы, `revision`, `attributes`, архив, FK единицы скоупа, обязательные колонки без умолчания; расхождение — приложение не стартует. В CI — `EntitySchemaContractTest`. |
| Тест-кит | `com.smartup24.cms:platform-testkit` (`libs/platform-testkit`, `<type>pom</type>`) | Сервер (jar классов), его тестовые помощники (jar с классификатором `testkit`: `EntityContractTestKit`, `EntityFixture`, `EntityTransport`, `TestUsers`, `TestSession`) и библиотеки тестов; пример — `examples/external-module` (модуль `library`, сущность `library.books`) проходит кит одним наследником. |

## 1. Сущность: объявление и записи

| Точка | Где | Что даёт |
|---|---|---|
| `EntityDefinition` (`@Bean`) через `Entity.define(...)` | `A/entity/Entity.java`, `A/entity/EntityDefinition.java` | Одно объявление сущности: таблица и псевдоним (`table`), обязательный скоуп (`scope`: `EntityScope.owner`/`orgUnit`/`all`/`custom`; без него `build()` отказывает; предикат — `EntityScopes`, правило оргединиц — `common.security.DataScopes`, его реализует `md`; ADR-0032 §5.1; `custom(name, ScopeProvider)` строит предикат модуля — у пользователей `md` бин-объявление получает `ObjectProvider<MdScopeService>` и спрашивает его только при чтении строк, пункт 5.6), поля (`field`, см. ниже), секции формы, действия с правом каждое, названия права (`EntityRights`), пункт меню (`EntityMenu`), сортировка списка по умолчанию, возможности (`EntityCapability`; `archivable()` — `ARCHIVE`: список без архивных записей, фильтр `archived`, чтение по id с `archived: true`, массовое `archive`; ADR-0032 §5.4). Отдаётся `GET /api/v1/form-meta/{code}`; список сущности (`query-meta/{code}`) выводится из тех же полей. `new EntityDefinition(...)` вне `common.entity` запрещён (`EntityFieldsSingleSourceTest`). |
| `EntityField` | `A/entity/field/EntityField.java`, построитель `EntityFields` | Поле объявляется **один раз** (ADR-0032, §3; план 10/10, пункт 5.1): тип (`FieldType`: `TEXT`, `TEXTAREA`, `MARKDOWN`, `NUMBER`, `DATE`, `DATETIME`, `TIME`, `BOOLEAN`, `SELECT`, `REF`, с пункта 5.2 — `EMAIL`, `PHONE`, `URL`, `MONEY`, `ENUM`, `MULTI_REF`, `FILE`, `IMAGE`, `JSON`), источник значения (`FieldSource`: колонка, выражение, вычисляемое `computed` — в форме только для чтения, атрибут в `attributes` — скалярные типы с приведением, системная колонка, пара колонок денег `money(amount, currency)`, таблица связи `link(table, owner, target)`; несколько ссылок можно и прочитать выражением — `multiRef(...).expression("array(select …)").listOnly(...)`, только в списке и записи, как роли пользователя `roleIds`), признаки формы (`FormPart`: обязательность, `FieldRules` — длина, диапазон, шаблон, масштаб, число элементов, размер и типы файла; `readonly()`/`readonlyOnUpdate()`/`readonlyWhen(...)`, `defaultValue(FieldDefault...)`, `visibleWhen(FieldCondition...)`), признаки списка (`ListPart`), права на поле (`FieldAccess`: `.requires(...)` — поля нет в `form-meta`, списке, выгрузке, истории, чтении и данных вебхука, запись — 422 `unknown_field`; `.readonlyUnless(...)` — поле только для чтения, изменённое значение — 422 `readonly`; применяет `EntityFieldRights`, пункт 5.3), параметры типа (`FieldOptions`: варианты, ссылка, валюты, справочник `ENUM`, корень JSON). Что нужно каждому типу, проверяет `FieldTypeRules` при старте (ключ с `password`/`secret`/`apiKey` отвергается — секреты живут в запечатанных колонках, ADR-0029). Из поля выводятся `FormField` формы и `QueryField` списка (у денег — ещё скрытое поле валюты `<key>Currency`); у типов пункта 5.2 поле списка называет тип в `format`. Поле только для чтения, если так говорит объявление **или** у зрителя нет права `readonlyUnless`: `form-meta` отдаёт `readonly: true` (нет права, `readonly()`, вычисляемое), `readonlyOnUpdate: true` (после создания) и `readonlyWhen` (условие на существующей записи); ошибка одна — `readonly`, ключ `error.field.readonly`. Каждый тип проверяют `FieldTypeMatrixTest` и `field-type-matrix.spec.ts`. |
| `EntityLists` | `S/common/entity/EntityLists.java` | Список сущности, выведенный из её полей: код = код сущности, право `<форма>.view`, `select` — системные колонки, поля под ключами записи (`<sql> as "<key>"`) и `attributes`. Реестр списков получает его через `QueryListSource`; бин `QueryList` с тем же кодом не даёт приложению стартовать. |
| Общий runtime `/api/v1/entities/{code}` | `S/common/entity/runtime/EntityController.java`, `EntityRuntime`, `EntityWrites`, `S/common/entity/store/EntityStoreRepository.java` | CRUD каждой сущности с таблицей **без кода модуля** (ADR-0032, §6; план 10/10, пункт 5.4): список (`q`, `filter`, `sort`, `limit`, `cursor`), чтение, создание (201 + `Location` + `ETag`), `PATCH` с `If-Match`, `DELETE` (необязательный `If-Match`), `PUT …/{id}/archived`, `POST …/{id}/actions/{action}`. Порядок §6.3 фиксирован: право и `If-Match` до транзакции, запись со скоупом `for update` (вне скоупа — 404), тело по полям (неизвестное, системное свойство, неверный тип JSON — 422), значения по умолчанию, readonly, правила полей, ссылки в скоупе цели, `EntityRule`, доп. поля — один 422; хуки, запись, аудит платформой, `EntityChanged` в транзакции, `afterCommit` после коммита. Транзакция — фильтра идемпотентности, если в запросе есть ключ. Тело — до 512 КБ (`EntityBodyLimitFilter`). |
| `EntityCollection` | `A/entity/collection/EntityCollection.java`, runtime `EntityLines`, `S/common/entity/store/EntityCollectionStore.java` | Строки документа (ADR-0032, §9.1; план 10/10, пункт 5.7): `.collection(EntityCollection.of("lines", подпись).table(таблица, псевдоним).parentColumn(...).positionColumn(...).field(...).maxRows(500).build())`. Строки читаются с записью (массив под ключом коллекции, у строки `id` и `position`), сохраняются в транзакции записи заменой по id (с `id` — изменение, без — вставка, отсутствующая — удаление), ревизия записи растёт на единицу за сохранение. Поле строки — колонка, деньги (`money(сумма, null).currencyFrom("currency")` — в валюте документа) или вычисляемое; проверка — правилами полей строки, ошибки `lines[3].qty`, чужая строка — `lines[i].id` `not_found`, больше `maxRows` — `lines` `too_many`. Аудит — одно свойство коллекции со сводкой (добавлено, изменено, удалено, число строк). Хук видит строки `EntityValues.collection("lines")`. |
| `EntityWorkflow` | `A/entity/workflow/`, runtime `EntityProcess`, `EntityWrites.transition` | Процесс документа (ADR-0032, §9.2): `.workflow(EntityWorkflow.on("status").state(...).initial().state(...).locks("lines", ...).state(...).terminal().transition("post", "draft", "posted").rule(Rules.hasRows("lines")).confirm(ключ).build())`. Поле статуса — выбор колонки с вариантами-состояниями, `readonly()` и умолчанием начального состояния (иначе старт отказывает). Переход — действие записи с правом (по умолчанию — код перехода) `POST …/{id}/actions/{переход}` с `If-Match`: из состояния, из которого он не ведёт, — 422 `entity_transition_not_allowed` (`from`, `action`); хуки видят его в `beforeSave`/`afterSave` с `ACTION` и кодом, обработчик не нужен (и не принимается). Состояние блокирует поля и строки (`locks`), конечное — всё; `actions` записи — переходы её состояния. |
| `EntityTab` | `A/entity/EntityTab.java`, `FormDocumentMetas` | Вкладки карточки (ADR-0032, §9.3): `.tab(EntityTab.sections(...))`, `collection(...)`, `related(ключ, подпись, сущность, поле-ссылка)`, `history(...)`; `form-meta` отдаёт `tabs`, связанный список — только зрителю с `view` его сущности, поле — фильтруемый `REF` той сущности. Пример — проекты (вкладка задач проекта) и заказы `example.orders`. |
| `Entity.importKey(ключ)` и `EntityImporter` | `A/entity/importing/` (`EntityImportSpec`), `S/common/entity/importing/` (`EntityImporter`), runtime `EntityImports`, `EntityImportBatch`, `S/common/entity/store/EntityKeyLookup.java`; журнал и задание — `S/report/imports/` | Импорт записей из xlsx (ADR-0032, §10.1; план 10/10, пункт 5.8): `.importKey("code")` даёт возможность `IMPORT`; ключ — текстовое поле формы в своей колонке с уникальным индексом (`EntityImportDeclaredTest`). Шаблон `GET /api/v1/entities/{code}/import-template` — колонки полей, которые зритель может писать (подпись, во второй скрытой строке ключ, листы-подсказки выборов); `POST …/imports {fileId, mode: dry_run\|apply, lang}` ставит задание `report.import`; `GET /api/v1/imports/{id}` — ход и первые 100 ошибок, `…/report` — файл с колонкой ошибок. Строка — создание или изменение по ключу в скоупе импортирующего через тот же порядок §6.3 (права `create`/`update`, права на поля, правила, хуки — `CREATE`/`UPDATE` с `EntitySave.imported()`, аудит с `_action: import`, события), пачка 500 строк в транзакции, точка сохранения на строку; `dry_run` откатывает пачку. Право — `<форма>.import`. |
| `EntityHooks` (`@Bean`) | `A/entity/hook/EntityHooks.java` | Второй файл автора сущности (ADR-0032, §6.5): `beforeSave` (может изменить значения через `EntityValues.set` и добавить ошибки `reject`), `afterSave`, `beforeArchive` (может отказать в архиве или восстановлении), `beforeDelete`, `afterDelete` — в транзакции, `afterCommit` — после коммита (сбой пишется в журнал и не меняет ответ). Один бин на сущность с таблицей, иначе приложение не стартует. |
| `EntityRule` | `A/entity/hook/EntityRule.java`, `Rules` | Кросс-полевое правило — чистая функция значений (ADR-0032, §6.6): `Entity.rule("period", Rules.notBefore("endsOn", "startsOn"))`; готовые — `notBefore` (даты или моменты времени), `requiredIf`, `atLeastOne`. Ошибки — в том же 422, что и ошибки полей. |
| `EntityActionHandler` (`@Bean`) | `A/entity/hook/EntityActionHandler.java` | Обработчик действия записи (ADR-0032, §6.7): `POST …/{id}/actions/{action}` с `If-Match`, запись читается со скоупом `for update`, обработчик меняет значения (`EntityActionCall`; `EntityValues.set` принимает и поле `readonly()` — его пишет только сервер), runtime пишет, повышает ревизию, пишет аудит с `_action` и событие; побочные эффекты (сессии, генерация входа) — `afterSave` хуков с `save.action()`. Объявленное действие без обработчика не даёт приложению стартовать; переходу процесса (`EntityWorkflow`) обработчик не нужен. Кнопку называет ключ `entity.action.<код>`, подтверждение — `entity.action_confirm.<код>` с `{name}` записи, если ключ есть (пункт 5.6). Примеры — действия пользователя `MdUserActions`, смена статуса задачи `MsTaskStatusAction`. |
| `EntityChanged` | `A/entity/event/EntityChanged.java` | Событие изменения записи (ADR-0032, §6.9): публикуется runtime в транзакции изменения; вебхуки (`S/webhook/service/EntityWebhookListener.java`) пишут `kwh_outbox` в той же транзакции, модуль слушает `@EventListener`/`@TransactionalEventListener`. |
| `EntityRecords` (`@Bean`) | `S/common/entity/EntityRecords.java` | Только для сущности **без таблицы**: видимость записи, страница, удаление. Записи сущности с таблицей держит runtime (`EntityRecordStore`); бин `EntityRecords` для неё не даёт приложению стартовать. |
| `EntityValidator` | `S/common/entity/EntityValidator.java` | Проверка сохранения по объявлению: 422 с ошибкой на каждом поле (ветка на каждый тип, типы пункта 5.2 — `FieldValueRules`); скрытое условием и вычисляемое поле не проверяются; `readonlyProblems` — поля только для чтения, которые сохранение изменило бы. Вызывает runtime (через `EntityFieldValues`) и `EntityValues.set` хука. |
| `EntityFieldValues` | `S/common/entity/EntityFieldValues.java` | Подготовка сохранения в порядке runtime (ADR-0032, §4.2–4.4): отказ на изменение поля только для чтения — по объявлению или по праву `readonlyUnless`, поле без права `requires` — 422 `unknown_field` (`EntityFieldRights`), хранимая форма значения (почта в нижнем регистре, телефон E.164), значения по умолчанию при создании (`fixed`, `now`, `today`, `current_user`, `current_org_unit` — основная оргединица автора через `DataScopes.homeUnit`, `sequence`), `null` у скрытого поля, проверка правил и проверки по БД — элемент справочника `ENUM` (новое значение не в архиве — 422 `archived`), файл, который можно прикрепить, его тип и размер. Вызывает runtime (`prepareAll` — без отказа, чтобы ответить одним 422). |
| `Entity.reference(code, name)` и `EntityEnums` | `A/entity/Entity.java`, `S/common/entity/EntityEnums.java` | Сущность-справочник для `ENUM` (ADR-0032, §4.5): колонка кода, колонка названия, порядок `sort_order`, до 500 элементов. Элементы читаются целиком и кэшируются (`entityEnums`, очистка по кластеру — `EntityEnums.evict`; каждое изменение записи справочника очищает его кэш сразу и после коммита — `EntityEnumEviction`); `EntityEnumResolver` даёт полю списка коды и названия во время запроса, `form-meta` — `options` (у справочника с `archivable()` — только действующие элементы) и `optionLabels` (названия всех, чтобы старое значение было подписано). |
| `EntityFiles` | `S/common/entity/EntityFiles.java` | Файлы полей `FILE`/`IMAGE` (ADR-0032, §4.7): какой файл можно положить в поле (свой загруженный или уже прикреплённый к записи), прикрепление к полю записи, снятие прикреплений удалённой записи, чтение файла через запись. Реализует модуль `mf` (`MfAttachments`, таблица `mf_record_files`); `GET /api/v1/entities/{code}/{id}/files/{fileId}` отдаёт файл, если запись видна зрителю (чтение runtime в скоупе) и файл прикреплён к ней. Прикрепления пишет и снимает runtime. |
| `FieldSource.Link` | `A/entity/field/FieldSource.java`, `EntityStoreRepository` | Значение `MULTI_REF` в таблице связи (ADR-0032, §4): `link(таблица, владелец, цель)`; с колонкой вида — `link(таблица, владелец, цель, колонка вида, вид)`, несколько списков одной таблицы (исполнители и наблюдатели задачи в `ms_task_members`). Строки своего вида runtime пишет и удаляет сам, строки других видов не трогает. |
| `EntityRowMapper`, `EntitySelect` | `S/common/entity/EntityRowMapper.java`, `S/common/entity/EntitySelect.java` | Как поле попадает в строку списка и обратно в значения записи: деньги — `{"amount":"1250.00","currency":"UZS"}`, несколько ссылок — массив ключей, файл — id, имя, размер и тип из `mf_pub_files`, JSON — как есть. Один `RowMapper` на все сущности. |
| `FormFieldExtender` | `S/common/entity/FormFieldExtender.java` | Поля, добавляемые в форму во время запроса. Реализация — дополнительные поля (`S/md/service/MdCustomFieldFormFields.java`). |

Возможности (`EntityCapability`) и что им нужно:

| Возможность | Требует | Что появляется |
|---|---|---|
| `CUSTOM_FIELDS` | `customEntity` (тип сущности дополнительных полей) | Дополнительные поля администратора в форме, списке, фильтре и экспорте |
| `SAVED_VIEWS` | `listCode` | Сохранённые виды списка |
| `EXPORT` | `listCode` (страницы даёт runtime) | Выгрузка списка в Excel (журнал «Мои выгрузки») |
| `HISTORY` | `auditTable` (аудит пишет runtime) | Вкладка истории изменений |
| `IMPORT` | `.importKey(ключ)` и действие `create` или `update`; право `<форма>.import` в миграции прав | Импорт из xlsx в общем списке: шаблон, проверка без записи, upsert по ключу в фоне, ошибки по строкам и отчёт (ADR-0032, §10.1) |
| `BULK` | хотя бы одно действие над записью | `POST /api/v1/entities/{code}/bulk`: `delete`, `archive`, `update` (поля из `params`) и любое объявленное действие записи (`params` — его параметры) над выбранными записями, каждое через одиночную операцию runtime со своим правом, проверками и хуками |
| `SEARCH` | `Entity.search(EntitySearchSpec)` и таблица; поля спецификации — текстовые поля списка без `requires`; у скоупа `custom` — участники (`scopeUsers`) | Глобальный поиск находит записи (ADR-0032, §10.3): коллекция Typesense и документы строятся из объявления (`S/search/repository/SearchDocumentSql.java`), документ несёт ключи скоупа, запрос фильтрует их по правилу зрителя, каждая находка перепроверяется в БД предикатом скоупа сущности; изменения доходят до индекса через `EntityChanged` (`EntitySearchListener`); категория — в `GET /api/v1/search/entities`, результат ведёт на `/e/<код>/<id>` или на маршрут спецификации (`route`) |

Объявление, которому не хватает нужного, не даёт приложению стартовать.

Контракт сущности в тестах (ADR-0032, §11; план 10/10, пункт 6.2): у каждой
сущности с таблицей — ровно один наследник `EntityContractTestKit`
(`apps/server/src/test/java/com/smartup24/cms/instance/support/entity`), его
требует `EntityContractCoverageTest`. Кит выводит случаи из объявления: CRUD,
ревизия, архив, права, скоуп «404, а не 403», права на поля, проверка по типам
полей, аудит, выгрузка, события (строка `kwh_outbox` на изменение, без
полей с правом; отказ — ни строки) и у документа — коллекции и процесс (строки
по порядку, ошибка строки `lines[i].поле`, чужая строка, лишние строки, замена
строк одной ревизией; недопустимый переход — 422
`entity_transition_not_allowed`, без права — 403, заблокированное поле — 422
`readonly`; строки фикстура даёт `EntityFixture.valid(Map.of("lines", ...))`),
у сущности с `IMPORT` — импорт (шаблон по правам, 403 без `import`, проверка
ничего не пишет, строка создаёт или изменяет запись по ключу, ошибочная строка —
`rows[n].поле`, запись вне скоупа не меняется), у сущности с `SEARCH` — поиск
(группа `search`: поля спецификации без права на поле, владелец находит запись
по заголовку, зритель вне скоупа не находит её ни по тексту, ни по `#id`, без
права формы сущность не ищется). Транспорт по умолчанию — общий runtime
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
| Отчёты и виджеты списка (данные, не код) | `S/common/query/QueryAggregates.java`, `QueryAggregateRepository.java`, `S/common/entity/report/` (`EntityReports`, `EntityReportController`, `EntityReportViews`), `S/md/service/MdReportViewState.java`, `MdReportWidgetService.java` | Отчёт сущности без Java-кода (ADR-0032, §10.2; пункт 5.8): до 2 группировок (выбор/`ENUM`, да/нет, ссылка, дата или момент по `day`/`week`/`month`/`quarter`/`year`, момент — в UTC) и до 4 мер (`count`, `sum`/`avg`/`min`/`max` числа и суммы денег; деньги сами группируются по валюте) над списком сущности, до 1000 групп, ≤ 5 с на запрос. SQL — только из выражений полей списка; скоуп, архив и права на поля — как у списка (поле с `requires` без права — 422, как в фильтре). `GET /api/v1/entities/{code}/report?groupBy=&measures=&filter=` — построитель, `GET …/reports/{viewId}` — сохранённый. Отчёт хранится сохранённым видом списка (`md_list_views.kind` = `report` или `widget`, V192), виджеты зрителя — `GET /api/v1/report-widgets` (до 12). Новой сущности ничего объявлять не нужно: отчёт есть у каждого списка сущности. |

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
| Поиск | объявление `.search(...)`; `S/search/service/SearchChangePublisher.java` | Сущность на runtime подключается объявлением (возможность `SEARCH`): индекс слышит `EntityChanged` сам. `changed(код сущности, id)` в транзакции владельца нужен только изменению записи вне runtime (системное создание пользователя). |
| Вебхуки | `S/webhook/service/WebhookService.java`, `S/webhook/service/EntityWebhookListener.java` | `publishEvent(type, payload)` доставляет событие подписчикам с подписью HMAC-SHA256. События сущностей на runtime (`<форма>.created`/`updated`/`deleted`/`archived`/`restored`/`<действие>`, например `notes.updated`) приходят без кода модуля: конверт `{id, type, occurredAt, entity, recordId, revision, changedFields, data}`, `data` — запись без полей с правом. |
| Провайдеры | `libs/provider-spi` (`StorageProvider`, `MailProvider`, `SmsProvider`, `MessengerProvider`) | Хранилище и каналы доставки; активный провайдер выбирает `S/common/provider/ProviderRegistry.java`. Часть публичного API (ADR-0033, §3): версия и japicmp — вместе с `platform-api`. |

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
| Общий экран `/e/:code` | `W/shared/entity/page/` (`entity.routes.ts`, `smt-entity-page`, `smt-entity-list-page`, `smt-entity-edit-page`, `smt-entity-record-page`), `W/shared/entity/page/entity.guard.ts` | Экран любой объявленной сущности **без кода веба** (ADR-0032, §7.1; план 10/10, пункт 5.5): `/e/<код>` — список (`ui-server-table` по `query-meta`, фильтр, сохранённые виды, выгрузка, переключатель архива, массовые архив и удаление, «Создать» — всё по возможностям и `actions`), `/new` и `/:id/edit` — форма (`smt-entity-form`, `If-Match`, 422 под полями, 409/428 — `SaveErrorNotifier` с перечитыванием), `/:id` — карточка (поля по секциям, файлы полей через запись, вкладка истории, кнопки по `actions` записи: правка, архив, удаление, действия по коду). У документа (ADR-0032, §9; пункт 5.7) форма правит строки (`smt-entity-lines`: добавить, удалить, переставить, ошибка под ячейкой строки; строки, заблокированные состоянием, не отправляются), карточка — вкладки из `form-meta` (`smt-entity-rows` — строки, `smt-entity-related` — связанный список), значок состояния и кнопки переходов с их вопросом. `form-meta` читается один раз на сущность (`EntityPageContext`); неизвестная, чужая сущность — «не найдено» по 404 сервера; `entityGuard` закрывает экран выключенного модуля по `EntityMenu.module`. Данные — `EntitiesApi` (`page`, `get`, `create`, `patch`, `remove`, `setArchived`, `action`). |
| `provideEntityOverrides(code, {cells, fields, sections, tabs})` | `W/shared/entity/page/entity-overrides.ts`, провайдеры маршрута общего экрана в `W/features/entity-screens.routes.ts` (грузятся вместе с экраном) | Точечная правка общего экрана без своего экрана (ADR-0032, §7.2): ячейка списка (входы `row`, `field`), контрол поля формы (`field`, `value`, `problem`, `disabled`, `set`), секция карточки (`meta`, `record`, `values`), своя вкладка карточки (`key`, `labelKey`, компонент со входами `meta`, `record`; `requires` — вкладка предлагается зрителю с одним из этих прав). Несколько вызовов для одной сущности складываются. Пример — пользователи (`W/features/iam/users/users.overrides.ts`: язык и часовой пояс выбором, вкладки «Сессии и безопасность», «Роли и права»). Ссылка на список с фильтром — `/e/<код>?filter=<условия DSL>` (поля, которых у списка нет, отбрасываются). |
| Отчёт списка и виджеты панели | `W/shared/entity/report/` (`smt-entity-report-builder`, `smt-entity-report`, `smt-entity-report-saved`, `EntityReportsApi`), `W/features/analytics/components/analytics-widgets.component.ts` | Вкладка «Отчёт» общего списка (ADR-0032, §10.2): группировки, меры и вид (таблица, столбцы `ui-bar-chart`, одно число `ui-kpi-card`) под фильтром списка, сохранение отчётом или виджетом; панель аналитики показывает виджеты зрителя. Группировки и меры предлагаются по `query-meta` (`groupable`, `measurable`), код веба на сущность не нужен. |
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

1. **Инвалидация скоупа поисковых документов.** При изменении оргединиц
   пользователя (`md_user_org_units`), состава участников проекта или задач проекта
   связанные поисковые проекции инвалидируются в `search_projection_versions` и
   переиндексируются в Typesense (ADR-0032, §10.3.1, С7; `SearchScopeInvalidationRepository`).
   Фасетов поиска (фильтр по полю) нет.
2. **Вебхуки — только у сущностей на runtime.** События сущностей приходят из
   `EntityChanged`; модули вне runtime (задачи, проекты) `publishEvent` пока не
   вызывают. Каталог событий подписки (`GET /api/v1/webhooks/events`) строится из
   `EntityRegistry`, подписка валидируется с 422, подпись метки времени
   (`timestamp.body`, В9) защищает от replay-атак.
3. **Подписей ссылок (`labels`) в ответе runtime нет** — общий экран называет
   ссылку отдельным чтением её цели (`RefLookups`, по запросу на значение).
   Строки документа не выгружаются отдельным листом, поле строки не бывает
   ссылкой с проверкой цели, справочником или файлом (ADR-0032, §9.5, Д1, Д13).
4. **Встраивание внешнего приложения — только iframe** из пункта меню, без SSO
   и передачи сессии.
5. **Маршрут своего экрана добавляется в `app.routes.ts` вручную.** Сущности
   экран даёт общий маршрут `/e/:code` (пункт 5.5); динамической регистрации
   своих экранов (доска, календарь) нет.
6. Прежняя модель «Plugin SDK» (таблица `md_custom_modules`, V017, выключена
   в V019) удалена миграцией V152 (план 10/10, пункт 4.7); модули
   регистрируются только в `md_installed_modules`, их версии — из манифестов
   (ADR-0033, §6.4).
7. **Доставка jar стороннего модуля в образ Docker не сделана** (ADR-0033,
   §11, В2): модуль работает, если его jar на classpath сервера (так его
   запускает тест-кит); тома `/app/modules` и `-cp` в `ENTRYPOINT` нет.
