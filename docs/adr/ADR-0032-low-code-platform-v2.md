# ADR-0032: Low-code платформа v2 — модель поля, общий runtime сущности и экран по метаданным

**Статус:** Предложено (2026-10-01)
**Дата:** 2026-10-01
**Зависит от:** ADR-0013 (скоуп данных), ADR-0016 (реестр полей и DSL),
ADR-0017 (история), ADR-0018 (выгрузки), ADR-0019 (модель сущности),
ADR-0021 (ошибки), ADR-0022 (OpenAPI из кода), ADR-0023 (единообразный REST),
ADR-0024 (ревизии), ADR-0025 (сроки хранения и кэш), ADR-0026 (опубликованные
представления), ADR-0028 (коды прав); план 10/10, пункты 5.1–5.8 и 6.2

---

## 1. Контекст

### 1.1. Что есть сейчас

ADR-0019 ввёл объявление сущности, и на нём работает одна сущность — заметки.
Проверка кода на 2026-10-01:

| Что | Сейчас | Где |
|---|---|---|
| Поле описано дважды | поле формы `FormField` (8 типов) и поле списка `QueryField` (6 типов) объявляются отдельно; у заметок `color` в форме — `SELECT`, в списке — `TEXT` | `MsNoteEntity`, `MsNoteQuery` |
| Модуль пишет CRUD сам | контроллер, сервис, репозиторий, выборка, объявление и бин `EntityRecords` — 6 серверных файлов на заметки | `ms/note/*` (727 строк) |
| Скоуп | модуль сам передаёт предикат (`ScopeFilter`) в каждый запрос; у заметок «чужая» запись отвечает 403, у истории — 404 | `MsNoteService.getNote`, `MsNoteRecords.requireVisible` |
| Аудит | модуль сам пишет `logChange` и сам выбирает поля (у заметок — 3 из 4) | `MsNoteService` |
| События | `WebhookService.publishEvent` никто не вызывает; поиск знает только задачи, проекты и пользователей | [extension-points.md §7](../architecture/extension-points.md#7-известные-пробелы) |
| Описание API | у `form-meta` в `openapi.json` схема поля списка: два класса `FieldMeta` слились в одну схему, и типы веба не знают `required`, `minLength`, `options` | `docs/api/openapi.json`, `FormMetaController.FieldMeta` |
| Веб | `smt-entity-form`, `smt-entity-card`, `smt-entity-toolbar` использует только экран заметок; задачи, проекты и пользователи — свои формы (~3 300, ~1 900 и ~2 500 строк TS) | `apps/web/src/app/features` |
| Маршрут | каждый экран регистрируется в `app.routes.ts` вручную; общего маршрута сущности нет | `app.routes.ts` |

Пункт 5.0 плана (параллельно с этим ADR) исправляет расхождения модели:
одинаковые типы поля формы и списка, история всех полей с подписями, 404 для
чужой записи на всех путях, отображение ссылки по типу цели, кэш `form-meta`,
типы `DATETIME` и `TIME`. Этот ADR **предполагает, что 5.0 влит**, и
опирается на его результат.

### 1.2. Решения владельца продукта (2026-10-01)

1. Общий CRUD — **runtime-эндпоинт** `/api/v1/entities/{code}`, а не
   генерация кода при сборке.
2. Документы со строками и проведением (5.7) идут **раньше** импорта,
   отчётов и поиска (5.8).
3. До финального релиза **установок у клиентов нет** (AGENTS.md): старые
   пути модулей заменяются новыми без псевдонимов и срока `Sunset`, данные
   при переводе сущностей не сохраняются и не конвертируются, изменение
   ответа записывается в `CHANGELOG.md`.

### 1.3. Цель и границы

Цель фазы 5: сущность — это **объявление и хуки**, всё остальное (REST,
проверка, скоуп, ревизия, аудит, события, экран, экспорт, импорт, поиск)
строит платформа. Критерии: ≥ 5 сущностей на модели, новая сущность —
≤ 2 серверных файла (объявление + хуки) и 0 файлов экрана.

Вне решения:

- сущности без кода (таблицу и экран создаёт администратор) — уровень 3 из
  ADR-0019 §2.6, нужен отдельный ADR;
- изменение схемы БД во время работы: таблицу по-прежнему создаёт миграция
  Flyway, объявление её только описывает;
- подключаемые плагины (jar) — модульный монолит (ADR-0006);
- регистры накопления и бухгалтерская модель проведения: 5.7 даёт статусы,
  переходы и хуки перехода, а не учётные регистры (вопрос В2, §19).

## 2. Решение — обзор

Одна модель в `common.entity`, один исполнитель в `common.entity.runtime`,
один экран в вебе:

```
EntityField ─┐                         ┌─► GET  /api/v1/form-meta/{code}
EntityScope ─┤                         ├─► GET  /api/v1/query-meta/{code}
Collections ─┼─► EntityDefinition ─────┼─► /api/v1/entities/{code}[/{id}[/...]]  (EntityRuntime)
Workflow ────┤   (бин модуля)          ├─► история, экспорт, импорт, массовые действия
Hooks/Rules ─┘                         ├─► EntityChanged → аудит, вебхуки, поиск, Spring-события
                                       └─► /e/:code  (smt-entity-page)
```

Пакеты сервера (все — инфраструктура `common`, бизнес-модулей не знают):

| Пакет | Что внутри |
|---|---|
| `common.entity` | `EntityDefinition`, `Entity` (построитель объявления), `EntityRegistry`, `EntityCapability`, `EntityScope`, `FormField`/`FormMetaController` (проекция для формы) |
| `common.entity.field` | `EntityField`, `FieldType`, `FieldSource`, `FormPart`, `ListPart`, `FieldAccess`, `FieldDefault`, `FieldCondition`, `Money`, `FieldRules` |
| `common.entity.runtime` | `EntityController`, `EntityRuntime`, `EntityRequestReader`, `EntityWriteCheck`, `EntityResponses`, `EntityOperation` |
| `common.entity.store` | `EntityStoreRepository` (`@Repository`: весь SQL runtime), `EntitySql` (сборка SQL из объявления) |
| `common.entity.hook` | `EntityHooks`, `EntitySave`, `EntityDelete`, `EntityCommitted`, `EntityRule`, `RuleErrors`, `EntityActionHandler` |
| `common.entity.event` | `EntityChanged`, `EntityEventType` |
| `common.entity.collection` | `EntityCollection`, `CollectionDiff` |
| `common.entity.workflow` | `EntityWorkflow`, `EntityState`, `EntityTransition` |
| `common.entity.importing` | `EntityImportSpec`, `EntityImporter` (интерфейс; журнал и задание — модуль `report`) |
| `common.entity.search` | `EntitySearchSpec` (интерфейс; коллекции Typesense — модуль `search`) |
| `config.openapi` | `EntityOpenApiCustomizer` — пути и схемы каждой сущности в `openapi.json` |
| `common.security`, `common.module` | интерфейсы `DataScopes` (предикат скоупа, реализует `md`) и `InstalledModules` (выключатель модуля, реализует `md`) — `common` не зависит от модулей |
| `mf.service` | контракт `MfAttachments` и таблица прикреплений `mf_record_files` для полей `FILE`/`IMAGE` (§4.7) |

Правило слоёв: `common.entity` и `common.entity.field` не зависят от
`runtime`, `store`, `hook`; `common.query` не зависит от `common.entity`
(сегодня `FormField` уже зависит от `QueryRef`, обратной зависимости нет и не
будет: реестр списков получает списки сущностей через интерфейс
`QueryListSource`, §3.4).

## 3. Модель поля `EntityField` (5.1)

### 3.1. Одно объявление на поле

`EntityField` (`common.entity.field`, record) — единственное место, где поле
сущности описано. Из него выводятся поле формы, поле списка, колонка
экспорта, строка истории, колонка импорта, поле поискового документа и
свойство схемы OpenAPI.

```java
public record EntityField(
        String key,                 // свойство записи в API и DSL: ^[a-z][a-zA-Z0-9]{0,63}$
        String labelKey,            // ключ подписи; "" если задан label
        @Nullable String label,     // готовая подпись (доп. поле администратора)
        FieldType type,             // §4
        FieldSource source,         // где живёт значение: колонка, выражение, атрибут, пара колонок, связь
        @Nullable FormPart form,    // поле формы или null (только в списке)
        @Nullable ListPart list,    // поле списка или null (только в форме)
        FieldAccess access,         // права на поле: §5.2
        FieldOptions options,       // параметры типа: варианты, ссылка, источник ENUM, валюта, файлы
        boolean exported,           // колонка выгрузки (по умолчанию = list != null)
        boolean history,            // попадает в аудит и историю (по умолчанию true, кроме COMPUTED и JSON > 4 КБ)
        boolean importable) {}      // колонка шаблона импорта (по умолчанию = записываемое поле формы)
```

Ключ поля — шаблон `QueryField` (`^[a-z][a-zA-Z0-9]{0,63}$`, без
подчёркивания); шаблон `FormField` с подчёркиванием сужается до него, потому
что ключ один на форму, список и DSL (у заметок все ключи уже подходят).

`FieldSource` — sealed-интерфейс:

| Вариант | Что это | Запись | Пример |
|---|---|---|---|
| `Column(name)` | колонка таблицы сущности | да | `title` → `n.title` |
| `Expression(sql)` | выражение над `from` сущности (как `QueryField.sql`) | нет | ранг заметок |
| `Computed(sql)` | вычисляемое поле (тип `COMPUTED` плана): выражение, только чтение, в форме показывается как readonly | нет | `qty * price` |
| `Attribute(code)` | значение в `attributes jsonb` записи | да | малонагруженное поле без миграции, доп. поле администратора |
| `MoneyColumns(amount, currency)` | пара колонок типа `MONEY` | да | `total_amount`, `total_currency` |
| `Link(table, ownerColumn, targetColumn)` | таблица связи `MULTI_REF` | да | `ms_task_tags(task_id, tag_id)` |
| `System(SystemColumn)` | `id`, `revision`, `created_at`, `created_by`, `modified_at`, `modified_by`, `archived_at` | нет (пишет runtime) | дата изменения в списке |

`FormPart` — признаки формы: `required`, `readonly` (`ALWAYS`, `ON_UPDATE`,
`WHEN(FieldCondition)`), `defaultValue` (`FieldDefault`, §4.3),
`visibleWhen` (`FieldCondition`, §4.4), правила `FieldRules` (длина,
диапазон, шаблон, масштаб числа, число элементов, размер и типы файла).
`ListPart` — признаки списка, как сегодня у `QueryField`: `filterable`,
`sortable`, `searchable`, `nullable`, `defaultVisible`.

### 3.2. Объявление сущности целиком

Автор объявляет сущность построителем `Entity` и отдаёт её бином, как
сегодня. Пример — заметки после 5.1 (заменяет `MsNoteEntity` и `MsNoteQuery`
одним классом):

```java
@Configuration
public class MsNoteEntity {
    public static final EntityDefinition DEFINITION = Entity.define("ms.notes", "notes")
            .table("ms_notes", "n")
            .scope(EntityScope.owner("created_by"))
            .rights("ms.note", "Заметки", Map.of(
                    "view", "Просмотр заметок", "create", "Создание заметки",
                    "update", "Редактирование и закрепление заметки", "delete", "Удаление заметки"))
            .menu(new EntityMenu("/notes", "nav.notes", "description", "workspace", 30, "notes"))
            .field(text("title", "notes.col.title").column("title").required().length(1, 255)
                    .list(sortable().searchable()))
            .field(markdown("contentMd", "notes.col.content").column("content_md").length(null, MAX_CONTENT)
                    .list(searchable().hidden()))
            .field(select("color", "notes.col.color", COLORS, "notes.color_").column("color")
                    .defaultValue(FieldDefault.fixed("default")))
            .field(bool("isPinned", "notes.col.pinned").column("is_pinned"))
            .field(instant("modifiedAt", "notes.col.modified_at").system(MODIFIED_AT).list(sortable()))
            .field(instant("createdAt", "notes.col.created_at").system(CREATED_AT).list(sortable().hidden()))
            .field(text("rank", "notes.col.rank").expression(RANK).listOnly(sortable().notFilterable().hidden()))
            .section("main", "entity.section.main", "title", "contentMd")
            .section("settings", "entity.section.settings", "color", "isPinned")
            .actions("create", "update", "delete").action("pin", "update")
            .defaultSort("rank", DESC)
            .customFields("NOTE")
            .capabilities(SAVED_VIEWS, EXPORT, HISTORY, BULK)
            .build();

    @Bean
    public EntityDefinition msNotesEntity() { return DEFINITION; }
}
```

`EntityDefinition` (record) получает компонент `EntityModel model`:
таблица, псевдоним, колонка ключа (`id`, `bigint generated always as
identity`, ADR-0020), скоуп, поля `EntityField`, сортировка по умолчанию,
коллекции (§9.1), процесс (§9.2), правила (§6.6), поиск (§10.3), импорт
(§10.1). Прежние компоненты (`fields`, `layout`, `actions`, `rights`,
`menu`, `capabilities`) остаются и **выводятся** построителем: `fields()`
— список `FormField` из полей с `form != null`, раскладка — из
`section(...)`. Код, который читает `EntityDefinition` сегодня
(`FormMetaController`, `EntityRegistry`, синхронизатор каталога прав,
`EntityMenuController`), не меняется.

### 3.3. Как выводится форма

`EntityField.formField()` возвращает `FormField`; `FormField` расширяется
новыми компонентами (`readonly`, `defaultValue`, `visibleWhen`, `computed`,
параметры типа из §4) и остаётся проекцией для `form-meta`. Схема ответа
`form-meta` переименовывается в `FormFieldMeta` и `FormSectionMeta`, чтобы
не сливаться со схемой `query-meta` в `openapi.json` (дефект из §1.1;
исправляется в шаге 1, это ломающее изменение описания API без изменения
JSON-ответа).

### 3.4. Как выводится список

`EntityLists.queryList(EntityDefinition)` строит `QueryList` сущности:

- `code` = `listCode` сущности (по умолчанию = код сущности), `form` и
  `action` = `<форма>`/`view`;
- `from` = `<таблица> <псевдоним>` плюс соединения, объявленные полями-ссылками
  (§4.6), — только своих таблиц и опубликованных представлений (ADR-0026);
- `select` = `<псевдоним>.id, <псевдоним>.revision` и каждое поле с
  `source` колонкой или выражением как `<sql> as "<key>"`; подписи ссылок —
  `<label sql> as "<key>$label"`; строки читает общий `EntityRowMapper` по
  ключам, модульный `RowMapper` не нужен;
- `fields` = поля с `list != null` в порядке объявления, через
  `EntityField.queryField()`;
- `idSql` = `<псевдоним>.id`; `customEntity`/`attributesSql` — из
  `customFields(...)`.

Реестр списков получает списки сущностей через новый интерфейс
`common.query.QueryListSource { List<QueryList> lists(); }`, который
реализует `EntityRegistry`. Конструктор `QueryListRegistry(List<QueryList>,
List<QueryListSource>, List<QueryListExtender>)` отвергает совпадение кода
списка сущности с отдельным бином `QueryList` — двух объявлений одного
списка быть не может.

### 3.5. Совместимость

- **Снимок метаданных заметок.** `EntityMetaSnapshotTest` хранит ответы
  `GET /form-meta/ms.notes` и `GET /query-meta/ms.notes` в
  `apps/server/src/test/resources/entity-meta/ms.notes.*.json`, снятые на
  коммите слияния 5.0. После перевода заметок на `EntityField` оба ответа
  совпадают байт в байт (критерий 5.1). Расхождение — падение теста;
  намеренное изменение — `-Dentity.meta.update=true` и строка в CHANGELOG.
- **0 параллельных объявлений.** `EntityFieldsSingleSourceTest` (ArchUnit):
  вне `common` нет вызовов `FormField.of/select`, `QueryField.of/enumeration`
  для кода, который объявлен сущностью; ни один бин `QueryList` не совпадает
  кодом с сущностью. Списки, которые не сущности (аудит, события
  безопасности, файлы, пакеты UPL), остаются на `QueryList`.
- `EntityValidator.check(entity, values, partial)` остаётся публичным: хуки и
  нестандартные экраны проверяют им значения, как сейчас.

### 3.6. Шаг 1 выполнен: как реализовано и отступления (2026-10-01)

Шаг 1 (пункт 5.1) выполнен в ветке `claude/p5-entity-field`: заметки — одно
объявление (`MsNoteEntity`), `MsNoteQuery` удалён; `EntityMetaSnapshotTest`
подтверждает, что `form-meta` и `query-meta` заметок совпадают байт в байт
со снимком после 5.0; `EntityFieldsSingleSourceTest` и
`EntityFieldContractTest` — зелёные. Схемы `form-meta` в `openapi.json` —
`FormFieldMeta` и `FormSectionMeta`.

Отступления от §3.1–3.5 (решение не меняется, уточняется исполнение):

| № | В дизайне | Сделано | Почему |
|---|---|---|---|
| О1 | `QueryListSource` реализует `EntityRegistry` | реализует `EntityLists` — бин, собранный только из бинов `EntityDefinition` | `EntityRegistry` зависит от бинов `EntityRecords`, те — от сервисов модулей, а сервис заметок — от реестра списков: цикл бинов, которого Spring Boot не допускает |
| О2 | `FieldSource` из семи вариантов | `Column`, `Expression`, `Computed`, `Attribute`, `SystemValue`; `MoneyColumns` и `Link` придут с типами `MONEY` и `MULTI_REF` (шаг 6) | вариант без типа, который его читает, был бы мёртвым кодом; `System` переименован в `SystemValue` — имя совпадало с `java.lang.System` (Error Prone `AvoidCommonTypeNames`) |
| О3 | `Computed` показывается в форме как readonly | выражение, вычисляемое и системная колонка — только в списке; поле формы — только у колонки и атрибута | признака `readonly` у `FormField` до шага 6 (§4.4) нет; без него форма предлагала бы правку, которую сервер не примет |
| О4 | `Attribute` для скалярных типов | только `TEXT`, `TEXTAREA`, `MARKDOWN`, `SELECT`, без сортировки | значение атрибута — текст jsonb; приведение остальных типов с защитой от старых значений (как у доп. полей) — правило хранения §4.1, шаг 6 |
| О5 | `FormPart` с `readonly`, `defaultValue`, `visibleWhen` | `FormPart(required, FieldRules)`; остальные признаки — шаг 6 (§4.3–4.4) | снимок `form-meta` заметок не должен меняться в шаге 1 |
| О6 | `ListPart` — record | класс-значение с методами `sortable()`, `searchable()`, `hidden()`, `notFilterable()`, `nullable()` и доступом `isSortable()`…; начальные значения — `EntityFields.sortable()`/`searchable()`/`hidden()`/`listed()` | у record метод `sortable()` обязан вернуть компонент `boolean`, цепочка `sortable().searchable()` из §3.2 иначе не пишется |
| О7 | `FieldAccess` — права на поле | запись есть, по умолчанию `OPEN`; список применяет `requires` так же, как `QueryField.requires`; метода построителя и проверки в форме, записи, выгрузке, истории нет | это шаг 2 (§5.2); заготовка не даёт полю списка и полю формы разойтись позже |
| О8 | `select` списка — `id`, `revision` и поля | системные колонки `id`, `revision`, `created_at`, `created_by`, `modified_at`, `modified_by` под ключами записи (`"createdAt"`…), поля как `<sql> as "<key>"` и всегда `attributes::text as "attributes"` | формат записи §6.2 и соглашение о таблице §14.1: эти колонки есть у каждой таблицы сущности; строка списка читается по ключам записи; ключ поля не может совпасть с системным, кроме самой системной колонки (`EntityModel`) |
| О9 | `EntityDefinition` с компонентом `model` | `model` — последний компонент; `listCode` есть только вместе с моделью (иначе конструктор отвергает объявление); форма модели — начало `fields` (доп. поля идут после); для сущности без таблицы — конструктор без `listCode` и модели; `new EntityDefinition` вне `common.entity` запрещён | «0 параллельных объявлений» обеспечивает конструктор и ArchUnit, а не только ревью |
| О10 | `.defaultSort("rank", DESC)`, `.rights("ms.note", "Заметки", …)` | `.defaultSort("rank", Entity.Sort.DESC)`; `rights(модуль, ключ названия, ключи действий)` (ключи — с 5.0, ADR-0031); действия в порядке объявления (`create`, `update`, `pin`, `delete` у заметок — порядок кнопок в снимке); `auditTable` по умолчанию — таблица сущности при `HISTORY` | порядок действий виден в `form-meta` |
| О11 | `FormFieldType` | переименован в `FieldType` и перенесён в `common.entity.field`, с `listType()` — тип поля списка по виду поля | один перечень видов для формы и списка; псевдонима старого имени нет (AGENTS.md, §3) |
| О12 | генератор переписывается в шаге 4 | в шаге 1 генератор уже пишет одно объявление полей (без класса `…Query`), сервис берёт список из реестра, а область права (`<код>` → `<префикс>.<код>`) записывается в `PermissionAreas` и в `EntityRights` | без этого генератор снова создавал бы параллельное объявление; предположение §6.15 подтвердилось: `test-create-module.ps1` падал на `EntityActionPermissionContractTest.everyEntityFormIsOwnedByTheModuleItsRightsName` |
| О13 | — | доп. поля администратора остаются `FormFieldExtender`/`QueryListExtender` в `md` | это данные, а не объявление в коде; их согласованность проверяет `EntityFieldContractTest`; перевод на `EntityField` с источником `Attribute` — вместе с правилами хранения шага 6 |
| О14 | `EntityFieldsSingleSourceTest` — ArchUnit | ArchUnit (конструктор `EntityDefinition` и фабрики `FormField` только в `common.entity`, кроме доп. полей `md`; класс с `Entity.define` не строит `QueryField`/`QueryList`) и проверки объявлений (поля формы и списка — из модели; ни один бин `QueryList` не совпадает кодом с сущностью; реестр отвергает второе объявление) | — |

## 4. Типы полей (5.2)

### 4.1. Перечень и хранение

`FieldType` (wire — нижний регистр, как сейчас): существующие `TEXT`,
`TEXTAREA`, `MARKDOWN`, `NUMBER`, `DATE`, `BOOLEAN`, `SELECT`, `REF`, типы
5.0 `DATETIME`, `TIME` и новые `EMAIL`, `PHONE`, `URL`, `MONEY`, `ENUM`,
`MULTI_REF`, `FILE`, `IMAGE`, `JSON`. `COMPUTED` плана — не отдельный тип,
а источник `Computed(sql)` у поля любого скалярного типа: `form-meta`
отдаёт `computed: true` и `readonly: true`.

| Тип | Колонка (ADR-0020) | В `attributes` | Значение в API | Поле списка (`QueryFieldType`, операции) | Экспорт xlsx |
|---|---|---|---|---|---|
| `TEXT`, `TEXTAREA`, `MARKDOWN` | `text` + `check (char_length(..) <= N)` | да | строка | `TEXT`: `eq ne in contains starts_with` | текст |
| `EMAIL` | `text` + `check (... ~ шаблон)` | да | строка, хранится в нижнем регистре | `TEXT` | текст |
| `PHONE` | `text`, E.164 `^\+[1-9][0-9]{6,14}$` | да | строка; пробелы, скобки и дефисы снимаются до проверки | `TEXT` | текст |
| `URL` | `text`, до 2048 | да | строка; только `http`/`https` | `TEXT` | текст (гиперссылка) |
| `NUMBER` | `bigint` (масштаб 0) или `numeric(p,s)` | да (число JSON) | число; дробное — строкой в JSON, чтобы не терять знаки | `NUMBER`: `eq ne in gt gte lt lte between` | число с форматом масштаба |
| `MONEY` | две колонки `<имя>_amount numeric(19,4)` и `<имя>_currency text check (~ '^[A-Z]{3}$')`; либо одна колонка суммы и валюта поля или документа | да (объект) | `{"amount":"1250.00","currency":"UZS"}` | сумма — `NUMBER`, валюта — `ENUM` (поле `<key>Currency`, скрытое) | число с форматом валюты + колонка валюты |
| `DATE` | `date` | да (`YYYY-MM-DD`) | строка ISO | `DATE` | дата |
| `DATETIME` | `timestamptz` | нет | строка ISO-8601 с зоной | `INSTANT` | дата-время в зоне зрителя |
| `TIME` | `time` | да | `HH:MM[:SS]` | `TIME` (тип 5.0) | время |
| `BOOLEAN` | `boolean not null default false` | да | `true`/`false` | `BOOLEAN`: `eq` | да/нет словами |
| `SELECT` | `text` + `check (... in (...))` | да | код варианта | `ENUM` с вариантами объявления | подпись варианта |
| `ENUM` | `text` + FK на `(code)` справочника | нет | код элемента справочника | `ENUM`, значения — из справочника во время запроса | подпись элемента |
| `REF` | `bigint` + FK + индекс | нет | id цели; подпись — в `labels` ответа | `NUMBER` + `ref`: `eq ne in empty not_empty` | подпись цели |
| `MULTI_REF` | таблица связи `<таблица>_<имя>(owner_id, target_id, position)`, PK `(owner_id, target_id)`, индекс `(target_id)` | нет | массив id; подписи — в `labels` | новый `QueryFieldType.REF_SET`: `in` (любой из), `empty`, `not_empty` через `exists` | подписи через «, » |
| `FILE`, `IMAGE` | `uuid` FK на `mf_files(id)` (одиночное) или только прикрепления `mf_record_files` (множественное, §4.7) | нет | `{"id":"<uuid>","name":"…","size":…,"contentType":"…"}` на чтение, uuid на запись | `BOOLEAN`-признак наличия: `empty`, `not_empty` | имя файла |
| `JSON` | `jsonb` + `check (jsonb_typeof(..) in ('object','array'))` | нет | JSON как есть | нет (только `empty`/`not_empty`) | текст JSON (до 32 767 символов ячейки) |

Правила хранения:

1. **Колонка — по умолчанию.** Поле, по которому сортируют, которое
   соединяют, на которое ставят FK или уникальность, — колонка.
2. **`attributes` — по объявлению** `.attribute()` для скалярных типов из
   таблицы (и для доп. полей администратора, как сейчас). Сортировка по
   атрибуту запрещена (нужен индекс по выражению, ADR-0019 §2.3), равенство
   идёт через `@>` по GIN-индексу (пункт 3.7). `MONEY` в атрибуте — объект
   `{amount, currency}`, сумма строкой.
3. **Деньги не в `double`.** Сумма — `BigDecimal` на сервере, строка в JSON,
   `numeric` в БД. Число знаков после запятой ограничено валютой
   (`java.util.Currency.getDefaultFractionDigits()`: UZS — 2, JPY — 0) и
   масштабом колонки.

### 4.2. Проверка на сервере

`EntityValidator` получает ветку на каждый тип; коды ошибок поля — прежние
(`required`, `too_short`, `too_long`, `out_of_range`, `invalid`) и новые:

| Тип | Проверка | Код и ключ |
|---|---|---|
| `EMAIL` | до 254 символов, шаблон `^[^@\s]+@[^@\s]+\.[^@\s]+$` (полная RFC 5322 не нужна: адрес подтверждает доставка) | `invalid`, `error.field.email_invalid` |
| `PHONE` | E.164 после нормализации | `invalid`, `error.field.phone_invalid` |
| `URL` | `java.net.URI`, схема `http`/`https`, есть хост, до 2048 | `invalid`, `error.field.url_invalid` |
| `MONEY` | сумма — десятичное число в диапазоне; знаков не больше, чем у валюты; валюта — код ISO 4217 из разрешённых полем | `invalid`/`out_of_range`, `error.field.money_invalid`, `error.field.currency_not_allowed` |
| `ENUM` | код есть в справочнике и не в архиве (для изменённого значения) | `invalid`, `error.field.option_required`; `archived`, `error.field.ref_archived` |
| `REF`, `MULTI_REF` | запись цели существует **и видна автору** в скоупе цели; не в архиве, если значение меняется; `MULTI_REF` — не больше `maxItems` (по умолчанию 100), без повторов | `not_found`, `error.field.ref_not_found`; `archived`; `too_many`, `error.field.too_many` |
| `FILE`, `IMAGE` | файл существует, загружен этим же пользователем или уже прикреплён к этой записи, проверен антивирусом, тип и размер — из `FieldRules` (`IMAGE`: `image/png`, `image/jpeg`, `image/webp`) | `not_found`, `error.field.file_not_found`; `invalid`, `error.field.file_type_not_allowed`; `too_large`, `error.field.file_too_large` |
| `JSON` | разбирается, корень — объект или массив по объявлению, до 64 КБ | `invalid`, `error.field.json_invalid`; `too_large` |
| `DATETIME`, `TIME` | разбор ISO (типы 5.0) | `invalid` |

Проверка ссылок (REF, MULTI_REF, ENUM, FILE) — один запрос на поле, а не на
значение: `select id from <цель> where id = any(:ids) and <скоуп цели>`
(§13). Сообщение «запись не найдена» одинаково для несуществующей и
невидимой цели.

### 4.3. Значения по умолчанию

`FieldDefault` (sealed): `fixed(value)`, `now()`, `today()`,
`currentUser()`, `currentOrgUnit()`, `sequence(name, pattern)`. Сервер
подставляет значение при создании, если поле отсутствует в теле;
`form-meta` отдаёт `default: {"kind":"fixed","value":"default"}` или
`{"kind":"today"}`, и форма предзаполняет поле. `sequence` — номер
документа: последовательность PostgreSQL `<таблица>_<поле>_seq` (создаёт
миграция модуля), формат `pattern` вида `ЗК-{000000}`; поле с ним — `readonly`.

### 4.4. Только чтение и условная видимость

- `readonly(ALWAYS)` — значение пишет только сервер (хук, default,
  вычисление); `readonly(ON_UPDATE)` — задаётся при создании (код
  справочника); `readonly(WHEN(условие))` — например, при статусе
  «проведён» (§9.2 даёт это из процесса: `lockedIn(...)`).
- Значение readonly-поля в теле: **равно текущему — пропускается** (клиент
  может отправить запись целиком), отличается — 422 с кодом `readonly`,
  ключ `error.field.readonly`.
- `visibleWhen(FieldCondition)` — подмножество DSL фильтра (ADR-0016):
  условия `eq`, `ne`, `in`, `empty`, `not_empty` и группа `{"any": [...]}`
  над полями формы типов `SELECT`, `ENUM`, `BOOLEAN`, `REF`. Клиент
  вычисляет его на каждое изменение формы, сервер — при сохранении тем же
  `FieldConditionEvaluator`. Скрытое поле не обязательно; его значение
  **не сохраняется** (пишется `null`, хук видит `null`). Видимость — удобство
  формы, а не граница безопасности: права на поле — §5.2.

### 4.5. Справочник для `ENUM`

`ENUM` берёт значения из сущности-справочника — любой объявленной сущности
с признаком `.reference("code", "name")`: колонка `code text not null`
(уникальная среди неархивных, `unique ... where archived_at is null`),
колонка или ключ перевода названия, порядок `sort_order`, возможность
`ARCHIVE`. Ограничение размера: до 500 строк, иначе нужен `REF`. Элементы
справочника читаются целиком и кэшируются (`entity-enum` в `CacheConfig`,
очистка по кластеру при изменении справочника, ADR-0025). Значение хранится
**кодом**, а не id: код переносим между установками, понятен в импорте,
экспорте и вебхуке. Справочники, которые администратор заводит без выпуска
(без своей таблицы), — вопрос В1 (§19).

### 4.6. Ссылки и подписи

`REF` объявляет цель кодом сущности, а не путём:
`ref("assigneeId", "tasks.col.assignee").column("assignee_id").target("md.users")`.
Платформа берёт у цели:

- путь выбора — `QueryRef("/entities/md.users", labelField, "id", paged=true)`
  (шаблон пути `QueryRef` расширяется точкой: `^/[a-z0-9/_.-]+$`);
- подпись в списке и карточке — соединение с опубликованным представлением
  цели (`left join md_pub_users <ключ>_ref on <ключ>_ref.id = t.assignee_id`)
  и выражение подписи; представление объявляет цель:
  `.publishedAs("md_pub_users", "full_name")`. Цель без опубликованного
  представления подписывается вторым запросом на страницу (`id = any(...)`),
  не по строке.

Ответ несёт id в поле и подписи отдельным объектом:
`{"assigneeId": 5, "tagIds": [3, 9], "labels": {"assigneeId": "Иван Петров", "tagIds": ["Срочно", "Склад"]}}`.
`labels` только для чтения: при записи сервер его игнорирует.

### 4.7. Файлы

`FILE`/`IMAGE` используют модуль `mf`, но не его правило видимости: сегодня
файл виден владельцу или через задачу (`ScopeFilter.fileSelf`,
`fileByOwnerOrTaskOrgUnit`), а прикрепления — отдельные таблицы модулей
(`ms_task_files`, `md_users.avatar_file_id`).

1. Файл загружается текущим `POST /api/v1/files/upload` (проверка
   содержимого `FileContentInspector`, ClamAV fail-closed, квота), поле
   получает его id.
2. При сохранении runtime проверяет файл через новый метод контракта `mf`
   — `MfAttachments.requireAttachable(fileId, userId)`: файл есть, загружен
   этим пользователем или уже прикреплён к этой записи, проверен. Затем
   пишет прикрепление в таблицу `mf_record_files(file_id, entity,
   record_id, field_key, created_at)` модуля `mf` (FK на `mf_files` с
   `on delete restrict`, индекс `(entity, record_id)`); одиночное поле
   дополнительно хранит id в своей колонке для FK и подписи.
3. Файл поля читается **через запись**:
   `GET /api/v1/entities/{code}/{id}/files/{fileId}` — runtime проверяет
   видимость записи (404) и то, что файл прикреплён к ней, и отдаёт поток
   через `MfFileService`. Общий список `/api/v1/files` по-прежнему
   показывает файлы по владельцу; предикаты `ScopeFilter.file*` не
   расширяются на произвольные сущности (иначе каждый список файлов
   соединял бы все таблицы сущностей).
4. Удаление записи удаляет прикрепления; физическое удаление файла без
   прикреплений — существующая очистка `mf`.

Изображение в карточке — через тот же путь, превью — вопрос В5 (§19).

### 4.8. Каждый тип — с тестом

Критерий 5.2 «для каждого типа: серверная проверка, контрол в форме,
колонка и фильтр в списке, экспорт — и тест на каждое» закрывает таблица
`FieldTypeMatrixTest` (сервер) + `field-type-matrix.spec.ts` (веб):
параметризованные по `FieldType.values()` проверки, что у типа есть ветка
валидатора, проекция в `QueryFieldType` (или явное «не фильтруется»),
форматтер экспорта, контрол веба и форматтер ячейки. Новый тип без любой из
пяти частей валит обе сборки.

### 4.9. Шаг 6 выполнен: как реализовано и отступления (2026-10-01)

Шаг 6 (пункт 5.2) выполнен в ветке `claude/p5-field-types`: `FieldType` знает
все типы §4.1; источники `MoneyColumns` и `Link` введены вместе с типами,
которые их читают; `FormPart` получил `readonly`, `defaultValue` и
`visibleWhen` — отступления О2–О5 из §3.6 закрыты. Пять частей каждого типа
проверяют `FieldTypeMatrixTest` (сервер: ветка валидатора, поле списка с
фильтром, ячейка выгрузки, совпадение перечня типов с вебом) и
`field-type-matrix.spec.ts` (веб: контрол, правила значения, слова карточки,
ячейка списка). Снимок метаданных заметок не изменился
(`EntityMetaSnapshotTest`): новые признаки и параметры отдаются только у полей,
которые их имеют. Типы показаны на тестовой сущности `FieldTypesFixture`
(тестовые исходники, своя база `FieldTypesIntegrationTest`): эталонный
документ — шаг 9 (§9.4).

Отступления от §4.1–4.8 (решение не меняется, уточняется исполнение):

| № | В дизайне | Сделано | Почему |
|---|---|---|---|
| Т1 | контракт `MfAttachments` модуля `mf` вызывает runtime | контракт — интерфейс `common.entity.EntityFiles` (`attachable`, `attach`, `detachAll`, `attached`, `open`); `mf.service.MfAttachments` его реализует | `common` не зависит от модулей — так же, как `DataScopes` (§2) |
| Т2 | `MONEY` — пара колонок, одна колонка с валютой поля или объект в `attributes` | `MoneyColumns(amount, currency)`; без колонки валюты поле держит одну валюту (литерал в SQL); `MONEY` в `attributes` — нет | сумма в атрибуте не сортируется и не фильтруется числом; понадобится — отдельным шагом |
| Т3 | `FILE`/`IMAGE` одиночное (колонка + прикрепление) или множественное (только прикрепления) | только одиночное: колонка `uuid` с FK на `mf_files` и строка `mf_record_files` | множественному полю нужен формат значения-массива и выборка из чужой таблицы в списке; ни одной сущности оно пока не нужно |
| Т4 | runtime пишет колонки денег, строки связи и прикрепления | путь чтения готов (выборка `EntitySelect`, `EntityRowMapper`, фильтры, выгрузка); запись подготовлена: `EntityFieldValues.prepare` — порядок §6.3 для значений (readonly, хранимая форма, умолчания, скрытые поля, проверки по БД), `EntityFiles.attach`/`detachAll` | общей записи (runtime, пункт 5.4) ещё нет; модуль может вызвать их сам |
| Т5 | `REF`/`MULTI_REF`: цель существует и видна автору в скоупе цели; цель — код сущности (`.target(...)`), подписи — `labels` | проверяется форма значения (`REF` — число или код, `error.field.ref_invalid`; `MULTI_REF` — положительные ключи без повторов, не больше `maxItems`); источник выбора `MULTI_REF` — `QueryRef`, как у `REF`; `labels` не отдаются, выгрузка пишет ключи | видимость в скоупе цели — `DataScopes` шага 2 (5.3) и runtime шага 3; цель кодом сущности и подписи — вместе с runtime |
| Т6 | `ENUM`: код не в архиве; справочник с `ARCHIVE` | проверяется только наличие кода; справочник читается целиком (до 500 строк, иначе ошибка при чтении) | возможность `ARCHIVE` — шаг 2 (5.3), идёт параллельно |
| Т7 | `FieldDefault.currentOrgUnit()` | нет | в сессии нет подразделения; появится с `DataScopes` (5.3) |
| Т8 | `form-meta`: `default: {...}`, `readonly: true`, условие — подмножество DSL | `defaultValue: {kind, value}` (`default` — ключевое слово Java); `readonly` — режим `always`/`on_update`/`when` и `readonlyWhen`; `computed: true`; условие — элементы `{field, op, values}` (значения всегда списком) и `{any: [...]}`; элементы `ENUM` — `options` и названия `optionLabels`, читаются во время запроса | форма должна знать режим, чтобы разблокировать поле при создании; значения списком — одна форма для `eq` и `in` |
| Т9 | поле списка `FILE`/`IMAGE` — `BOOLEAN`-признак; `JSON` — без фильтра | новые типы списка `REF_SET` (`in`, `empty`, `not_empty`) и `OBJECT` (`empty`, `not_empty`); у типов шага 6 поле списка называет тип в `format`, у скрытого поля валюты — `format: "currency"`; названия элементов `ENUM` — `enumLabels` | признак наличия — это и есть `empty`/`not_empty` без лишнего значения; `format` нужен ячейке и выгрузке, у старых типов он `null`, поэтому снимок заметок прежний |
| Т10 | значения `ENUM` списка — из справочника во время запроса | новый интерфейс `common.query.QueryFieldResolver` в `QueryListRegistry` (реализация `EntityEnumResolver`) | `QueryListExtender` только добавляет поля и не заменяет объявленные |
| Т11 | `IMAGE` в карточке «через тот же путь», превью — В5 | без миниатюр (предположение по В5); файл поля открывается ссылкой через запись; изображение PNG/JPEG/WebP отдаётся `inline`, остальное — `attachment`, с `nosniff` | новой серверной зависимости нет |
| Т12 | каждый тип — на реальной сущности, a11y-скан общей страницы | тестовая сущность `FieldTypesFixture`; новые контролы не добавлены в a11y-экраны e2e | реальной сущности с новыми типами до шага 9 нет; доступность контролов проверяют спеки компонентов, `aria:audit` и `contrast:audit` |
| Т13 | — | ключ поля, называющий секрет (`password`, `secret`, `apiKey`, `privateKey`, `accessToken`…), отвергается при старте | секреты живут только в запечатанных колонках (ADR-0029) |
| Т14 | коды ошибок §4.2 | добавлены ключи `error.field.ref_invalid`, `keys_invalid`, `keys_repeated`, `too_many`, `scale_exceeded`, `json_too_large`, `file_invalid`, `readonly`; удаление прикреплённого файла — 409 `error.file.attached_to_record` (FK `on delete restrict`) | без них ошибка формы не называла бы причину, а удаление файла давало бы 500 |
| Т15 | атрибут — скалярные типы (О4) | атрибут держит текстовые типы, `SELECT`, `EMAIL`, `PHONE`, `URL`, `NUMBER`, `DATE`, `DATETIME`, `TIME`, `BOOLEAN`; значение приводится к типу, только если имеет его форму (как у доп. полей) | старое значение не должно ронять страницу |
| Т16 | телефон — контрол кита | обычное поле `tel` в `smt-dynamic-field`, значение вводится в E.164 | `smt-phone-input` кита хранит номер по стране, а сервер принимает только E.164 |

## 5. Скоуп, права на поля, ревизия, архив (5.3)

### 5.1. `EntityScope` — обязателен в каждом объявлении

`EntityScope` (`common.entity`, sealed) задаёт, какие строки видит зритель.
Построитель `Entity.build()` без `.scope(...)` не собирается: критерий
«скоуп объявлен у 100% сущностей» обеспечивает компилятор, а
`EntityScopeDeclaredTest` перечисляет сущности и их скоуп для ревью.

| Вариант | Смысл | Предикат чтения (`ScopeFilter`) | Запись |
|---|---|---|---|
| `owner(ownerColumn)` | личная запись: видит только владелец, независимо от правила роли | `<a>.<ownerColumn> = :scopeUserId` | владелец — автор (`created_by`), сменить нельзя |
| `orgUnit(orgUnitColumn, ownerColumn)` | запись принадлежит оргединице; правило роли (ADR-0013): `ALL` — всё, `SUBTREE`/`UNITS` — единица в `md_effective_scope`, `SELF` — `ownerColumn = я` | `MdScopeService.filterFor(userId, orgUnitColumn, ownerColumn)` | единица по умолчанию — основная единица автора (`FieldDefault.currentOrgUnit()`); единица вне скоупа автора — 422 `out_of_scope` на поле |
| `all()` | справочники и настройки: строк не ограничивает, доступ — только правом формы | пусто | — |
| `custom(name, ScopeProvider)` | правило модуля: задачи видны по участию (`filterForTasks`) | фрагмент от модуля | проверки — в хуках модуля |

Предикат строит не `common`, а модуль `md`: в `common.security` появляется
интерфейс `DataScopes` (`ScopeFilter filterFor(long userId, String
orgUnitColumn, String ownerColumn)`, `boolean unitVisible(long userId, long
orgUnitId)`), его реализует `MdScopeService` — `common` по-прежнему не знает
модулей. `ScopeProvider` — `ScopeFilter filter(long userId, String alias)`;
фиксированный псевдоним `t` у задач сохраняется (объявление задач берёт
псевдоним `t`).

Где применяется скоуп (каждая строка — проверка кита, §11):

- список, его подсчёт и выгрузка — предикат в том же SQL (ADR-0013 §2.4);
- чтение, изменение, удаление, действие, история, файлы записи — запись
  читается с предикатом, вне скоупа — **404** `error.common.record_not_found`
  (не 403: существование не раскрывается);
- ссылка на запись цели при сохранении — проверка по скоупу **цели** (§4.2);
- коллекции и таблицы связи — только через родителя, своего скоупа нет;
- поиск — фильтр документа по скоупу плюс перепроверка в БД (§10.3);
- массовое действие — каждая запись проходит одиночную операцию со скоупом.

### 5.2. Права на поля

`FieldAccess` (record): `requires(form, action)` и `readonlyUnless(form,
action)`; оба необязательны и задаются построителем
`.requires("md.users", "view_contacts")`, `.readonlyUnless("tasks.items", "assign")`.
Форма права подчиняется ADR-0028 (форма своего модуля или опубликованная
ему). Пара «форма.действие» попадает в каталог прав из объявления (§6.10).

| Путь | `requires` не выполнен — поля **нет** | `readonlyUnless` не выполнен — поле **только для чтения** |
|---|---|---|
| `form-meta` | поля нет в ответе | `readonly: true` |
| `query-meta`, фильтр, сортировка, `q` | поля нет; условие по нему — 422 `QUERY_UNKNOWN_FIELD` (как сейчас у `QueryField.requires`) | поле есть |
| чтение (список, запись, `labels`) | свойства нет в JSON | значение есть |
| запись (create, patch, импорт) | свойство в теле — 422 `unknown_field` (тот же ответ, что у несуществующего поля) | значение, отличное от текущего, — 422 `readonly` |
| выгрузка | колонки нет (выгрузка строится по `query-meta` зрителя, ADR-0018) | колонка есть |
| история | поле скрыто (`RecordHistorySource.hiddenFields()` по зрителю) | показывается |
| поиск | поле не индексируется для полнотекста (индекс общий для всех зрителей) | индексируется |
| вебхук | поле не входит в `data` события (у подписки нет зрителя) | входит |

### 5.3. Ревизия

«Версия» плана — это ревизия ADR-0024: колонка `revision bigint not null
default 1`, `If-Match` на изменение, 428 без него, 409 при устаревшей, `ETag`
в ответе. Runtime добавляет к этому только обязательность:

- таблица сущности без колонки `revision` не проходит
  `EntitySchemaContractTest` (§11.4);
- каждое изменение — `update … set …, revision = revision + 1 where id = :id
  and revision = :expected returning revision` (`EntityStoreRepository`);
  `RevisionedUpdatesRaiseRevisionTest` расширяется на SQL runtime;
- изменение коллекции, связей `MULTI_REF`, прикреплений и переход статуса —
  изменение родителя: ревизия родителя растёт на единицу за сохранение;
- `DELETE` принимает необязательный `If-Match`: если он есть, удаление идёт
  только из этой ревизии (409 иначе).

### 5.4. Возможность `ARCHIVE` (мягкое удаление)

- Требует колонки `archived_at timestamptz` и `archived_by bigint
  references md_users` (индекс по `archived_by`, ADR-0020; частичный индекс
  `where archived_at is null` под горячие выборки — по нагрузке).
- Действие `archive` (право по умолчанию — действие `delete` формы;
  объявление может назначить своё, вопрос В11):
  `PUT /api/v1/entities/{code}/{id}/archived {"archived": true|false}` с
  `If-Match` — переключатель ADR-0023: пишет только при смене состояния,
  событие `archived`/`restored`.
- **Списки** по умолчанию добавляют `<a>.archived_at is null`. Виртуальное
  поле списка `archived` (`BOOLEAN`, фильтруемое, скрытое) снимает условие:
  `{"field":"archived","op":"eq","value":true}` — только архив, `false` —
  только действующие; тулбар показывает переключатель «Архив».
- **Чтение по id** архивной записи — 200 с `archived: true`: старые ссылки
  на неё должны показываться.
- **Ссылки:** выбор (`REF`, `MULTI_REF`, `ENUM`) предлагает только
  действующие записи; новое или изменённое значение, указывающее на архивную
  запись, — 422 `archived`; неизменённое старое значение сохраняется.
  Карточка и ячейка показывают подпись архивной цели с пометкой «в архиве».
- **Уникальность** кодов — частичным индексом `… where archived_at is null`.
- **Поиск** убирает архивную запись из индекса; **выгрузка** следует фильтру
  списка; массовое действие `archive` — через `BulkRunner`.
- Жёсткое удаление остаётся, только если объявлено действие `delete`.

## 6. Общий runtime (5.4)

### 6.1. Маршруты

`EntityController` (`common.entity.runtime`) обслуживает все сущности,
объявленные с таблицей (`Entity.table(...)`). Код сущности — шаблон
`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$` (обязательна точка), поэтому
литералы `menu` и `bulk` не пересекаются с кодами; id — `{id:\d+}`.

| Метод и путь | Что делает | Право | Ответ |
|---|---|---|---|
| `GET /api/v1/entities/{code}` | страница списка: `q`, `filter`, `sort`, `limit`, `cursor` (ADR-0016) | `view` | 200 `KeysetPage<запись>` |
| `GET /api/v1/entities/{code}/{id}` | запись с коллекциями (до лимита, §9.1) | `view` | 200 + `ETag` |
| `POST /api/v1/entities/{code}` | создание | `create` | 201 + `Location: /api/v1/entities/{code}/{id}` + `ETag` |
| `PATCH /api/v1/entities/{code}/{id}` | изменение: отсутствующее свойство не меняется, `null` очищает; коллекция заменяется целиком по id строк (§9.1) | `update` | 200 + `ETag`; `If-Match` обязателен |
| `DELETE /api/v1/entities/{code}/{id}` | удаление (если объявлено `delete`) | `delete` | 204 |
| `PUT /api/v1/entities/{code}/{id}/archived` | архив и восстановление | `archive` (§5.4) | 200 + `ETag`; `If-Match` |
| `POST /api/v1/entities/{code}/{id}/actions/{action}` | действие записи или переход процесса (§6.7, §9.2) | право действия | 200 + `ETag`; `If-Match` |
| `GET /api/v1/entities/{code}/{id}/collections/{collection}` | строки коллекции страницами, когда их больше лимита | `view` | 200 `KeysetPage` |
| `GET /api/v1/entities/{code}/{id}/files/{fileId}` | файл поля записи (§4.7) | `view` | 200 поток |
| `POST /api/v1/entities/{code}/bulk` | существующий `EntityBulkController`: `delete`, `archive` и объявленные массовые действия | право действия | 200 `BulkResult` |
| `GET /api/v1/entities/{code}/reports/{report}` | отчёт по списку (§10.2) | `view` | 200 |
| `POST /api/v1/entities/{code}/imports` и `GET /api/v1/entities/{code}/import-template` | импорт (§10.1) | `import` | 202 + `Location` / 200 xlsx |

Все обработчики помечены `@RequiresPermission(form = "md.profile", action
= "view")` (вход в систему), а право сущности проверяет runtime — так же, как
`form-meta` и `bulk` сегодня; инфраструктура `common` может требовать любую
форму (ADR-0028, п. 3).

### 6.2. Формат записи

Запись в ответе — объект с системными свойствами и полями зрителя:

```json
{
  "id": 42, "revision": 3,
  "createdAt": "2026-10-01T09:00:00Z", "createdBy": 7,
  "modifiedAt": "2026-10-01T09:30:00Z", "modifiedBy": 7,
  "archived": false,
  "title": "Поставка", "color": "blue", "assigneeId": 5, "total": {"amount": "1250.00", "currency": "UZS"},
  "attributes": {"cfRegion": "north"},
  "lines": [{"id": 1, "position": 1, "productId": 9, "qty": "3"}],
  "labels": {"assigneeId": "Иван Петров", "lines": [{"productId": "Мука 50 кг"}]},
  "actions": ["update", "post"]
}
```

- Ответ — `EntityRecordView` (record в `common.entity.runtime`, `implements
  Revisioned`, свойства полей через `@JsonAnyGetter`): `ETag` ставит
  существующий `RevisionETagAdvice`.
- `actions` — действия и переходы, доступные **этому зрителю для этой
  записи** (право + состояние процесса); экран рисует кнопки по ним, как по
  `form-meta` сегодня.
- Доп. поля администратора — в `attributes`, как у заметок сейчас; веб-помощники
  `recordValues`/`recordPayload` не меняются.
- Тело записи (`POST`, `PATCH`) — те же свойства без системных, `labels` и
  `actions`; системные и неизвестные свойства — 422 `unknown_field`.
- Лимит тела — 512 КБ для `/api/v1/entities/**` (413 выше); лимит фильтра
  идемпотентности (64 КБ) для этих путей поднимается до того же значения.

### 6.3. Порядок обработки записи

Порядок фиксирован и одинаков для `POST`, `PATCH`, `DELETE`, архива,
действия и строки импорта; хук не может его поменять.

| Шаг | Что | Ошибка |
|---|---|---|
| 1 | найти сущность; её модуль включён (`EntityMenu.module`, новый интерфейс `common.module.InstalledModules`, реализует `ModuleRegistryService`); у зрителя есть `<форма>.view` | 404 `error.common.entity_not_found` |
| 2 | право операции (`create`, `update`, `delete`, право действия) | 403 `permissionDenied(form, action)` |
| 3 | `If-Match` для изменения | 428 / 422 формат |
| — | **начало транзакции** (или участие в транзакции фильтра идемпотентности) | — |
| 4 | прочитать запись со скоупом `for update` (кроме создания) | 404 `error.common.record_not_found` |
| 5 | ревизия записи = ревизии `If-Match` | 409 `revision_conflict` |
| 6 | разобрать тело по полям: неизвестное, скрытое правом (`requires`), системное свойство, неверный тип JSON | 422 `unknown_field` / `invalid` |
| 7 | значения по умолчанию (создание), условная видимость, readonly и `readonlyUnless`, заблокированные процессом поля | 422 `readonly` |
| 8 | правила полей (`EntityValidator`), ссылки, справочники и файлы (по запросу на поле), правила `EntityRule`; все ошибки собираются вместе | один 422 со всеми адресами |
| 9 | `EntityHooks.beforeSave` / `beforeDelete` — может изменить значения и добавить ошибки | 422 / `ApiException` хука |
| 10 | запись: строка (ревизия + 1), коллекции, связи, прикрепления | 409 (гонка ревизии), 422 (нарушение ограничения БД → ключ модуля, `ConstraintCodes`) |
| 11 | аудит (§6.8) | — |
| 12 | `EntityHooks.afterSave` / `afterDelete` — в той же транзакции | `ApiException` хука откатывает всё |
| 13 | событие `EntityChanged` (`ApplicationEventPublisher`, синхронно в транзакции): вебхуки и поиск пишут в свои outbox-таблицы в той же транзакции | — |
| — | **коммит** | — |
| 14 | `EntityHooks.afterCommit` и `@TransactionalEventListener(AFTER_COMMIT)` — вне транзакции; сбой пишется в журнал (`warn`) и не меняет ответ | — |
| 15 | ответ: запись перечитывается тем же SQL, что и список (единая проекция), с новой ревизией | 200 / 201 / 204 |

Шаги 1–3 идут до транзакции, чтобы чужой запрос не держал соединение.
Проверка 6–8 не обращается к хукам: хук видит уже проверенные значения.

### 6.4. Транзакции и идемпотентность

- Одна транзакция на запрос (`TransactionTemplate` в `EntityRuntime`,
  `REQUIRED`): если запрос пришёл с `Idempotency-Key`, фильтр уже открыл
  транзакцию, и runtime вступает в неё — сохранённый ответ и изменение
  коммитятся вместе (пункт 3.12). Повтор отдаёт тот же `Location` и `ETag`.
- Массовое действие — транзакция на запись (как сейчас, ADR-0016 §2.8).
- Импорт — транзакция на пачку строк (§10.1).
- `afterCommit` не участвует в идемпотентности: побочный эффект, который
  нельзя повторить (письмо, внешний вызов), ставится в очередь
  (`JobQueue.enqueueOnce` в шаге 12) или в outbox, а не выполняется в
  `afterCommit`.

### 6.5. Хуки `EntityHooks`

Второй файл автора сущности. Бин (`common.entity.hook`), один на сущность:

```java
public interface EntityHooks {
    String entity();                                         // "sales.orders"
    default void beforeSave(EntitySave save) {}              // шаг 9
    default void afterSave(EntitySave save) {}               // шаг 12, та же транзакция
    default void beforeDelete(EntityDelete delete) {}        // шаг 9
    default void afterDelete(EntityDelete delete) {}         // шаг 12
    default void afterCommit(EntityCommitted committed) {}   // шаг 14, вне транзакции
}

public interface EntitySave {
    EntityDefinition entity();
    EntityOperation operation();                 // CREATE, UPDATE, ACTION, IMPORT
    @Nullable Long id();                         // null в beforeSave при создании
    @Nullable EntityValues before();             // запись до изменения (только чтение) или null
    EntityValues values();                       // новые значения (меняются только записываемые поля)
    @Nullable String action();                   // код действия или перехода
    AuditActor actor();
    boolean changed(String key);
    void reject(String fieldPath, String code, String messageKey, Map<String, ?> params); // ошибка поля → 422 после хука
}
```

`EntityValues` — типизированный доступ по ключу (`text`, `decimal`,
`money`, `date`, `ref`, `refs`, `bool`, `json`, `collection(key)`),
`set(key, value)` проверяет тип по объявлению. Хук не пишет SQL сам: данные
своего модуля — через репозиторий модуля, чужие — через `service`/`api`
другого модуля (ADR-0026). `EntityRegistry` отвергает второй бин хуков той
же сущности и хуки для необъявленной сущности (как `EntityRecords` сейчас).

`EntityRecords` для сущностей на runtime не нужен: видимость, страницу и
удаление даёт runtime; интерфейс остаётся для сущностей без таблицы, если
они понадобятся.

### 6.6. Кросс-полевые правила `EntityRule`

```java
@FunctionalInterface
public interface EntityRule {
    void check(EntityValues values, @Nullable EntityValues before, RuleErrors errors);
}
```

Правило — чистая функция без SQL и сервисов (проверки с данными — в
`beforeSave`). Объявляется в построителе: `.rule("period",
Rules.notBefore("endDate", "startDate"))` или лямбдой. Готовые правила
(`common.entity.hook.Rules`): `notBefore`, `requiredIf(key, condition)`,
`atLeastOne(keys...)`, `sumEquals(collection, field, total)`. Ошибка
адресуется полю (`errors.field("endDate", "before_start",
"error.tasks.end_before_start")`) или записи целиком
(`errors.record(...)`, адрес `""`). Правила выполняются и в `dry-run`
импорта, и на клиенте не дублируются: форма получает их ошибки ответом 422.

### 6.7. Действия записи

`EntityAction` получает вид и параметры:
`new EntityAction(code, permission, kind, confirmKey, params)`, где `kind` —
`RECORD` (кнопка карточки), `BULK` (массовое), `TRANSITION` (переход
процесса, §9.2); `params` — поля диалога действия (`EntityField` с формой,
без колонки). Обработчик — бин
`EntityActionHandler { String entity(); String action(); void run(EntityActionCall call); }`;
`EntityActionCall` наследует `EntitySave` (значения записи, параметры,
`reject`). Действие идёт по общему порядку §6.3 (шаги 4–15) с `If-Match`.
Действие без обработчика и без перехода процесса не стартует приложение.

### 6.8. Аудит и история

- Runtime пишет `AuditLogService.logChange(auditTable, id, I|U|D, …)` в
  шаге 11 сам; модуль не пишет аудит своих записей. Поля — все с
  `history = true` (по умолчанию все записываемые и вычисляемые не входят),
  значения — в форме API (ключ поля, `MONEY` — объект, `REF` — id). Это
  закрывает «историю всех полей» 5.0 для всех сущностей сразу.
- Коллекция пишется в запись аудита родителя одним свойством (`lines`) со
  сводкой изменений: добавленные строки целиком, изменённые — только
  изменённые поля с id строки, удалённые — id; история показывает
  «строки: +1, изменено 2, удалено 1» и раскрывает подробности.
- Переход процесса пишется событием `U` с полем статуса и кодом действия в
  `new_row._action`.
- `auditTable` по умолчанию — таблица сущности; возможность `HISTORY`
  подключается как сейчас (`EntityRegistry.historySources()`), подписи —
  из `EntityField.labelKey`, скрытые поля — по `FieldAccess` зрителя.

### 6.9. События `EntityChanged`

```java
public record EntityChanged(
        String entity, long id, long revision,
        EntityEventType type,                // CREATED, UPDATED, DELETED, ARCHIVED, RESTORED, ACTION
        @Nullable String action, Set<String> changedFields,
        @Nullable Long actorId, Instant occurredAt, UUID eventId) {}
```

Runtime публикует событие в шаге 13 через `ApplicationEventPublisher`.
Подписчики платформы:

| Подписчик | Где | Когда | Что делает |
|---|---|---|---|
| вебхуки | `webhook.service.EntityWebhookListener` | `@EventListener`, в транзакции | имя события `<форма>.<событие>`, событие — `created`, `updated`, `deleted`, `archived`, `restored` или код действия (`notes.updated`, `sales.orders.post`); `WebhookService.publishEvent` (`REQUIRED`) пишет строку `kwh_outbox` в той же транзакции; `data` — запись в проекции без полей с `requires`, читается тем же SQL без скоупа зрителя |
| поиск | `search.service.EntitySearchListener` | `@EventListener`, в транзакции | для сущности с `SEARCH` — `SearchChangePublisher.changed(entity, id)` (`MANDATORY`, та же транзакция) |
| модули | любой `@EventListener` / `@TransactionalEventListener` | по выбору | уведомления, пересчёты; модуль не вызывает соседний сервис напрямую (extension-points §4) |

Конверт вебхука сущности (тело, которое подписывается, как сейчас,
HMAC-SHA256): `{"id": eventId, "type": "notes.updated", "occurredAt": …,
"entity": "ms.notes", "recordId": 42, "revision": 3, "changedFields": [...],
"data": {...}}`. Каталог событий для формы подписки —
`GET /api/v1/webhooks/events` (модуль `webhook`, строится из
`EntityRegistry`); подписка на несуществующее событие — 422. Подпись
метки времени — вопрос В9.

Так выполняется критерий 5.4 «вебхук `notes.updated` приходит без кода
модуля»: ни объявление, ни хуки заметок не вызывают `publishEvent`.

### 6.10. Права

- Действия сущности — права её формы (ADR-0028): `view`, `create`,
  `update`, `delete`, `archive` (если отдельное), `import`, `export`
  (по умолчанию `view`, как сейчас) и права действий и переходов.
- `@RequiresPermission` больше не единственный источник пары
  «форма.действие»: `MdFormCatalogSynchronizer` добавляет пары из
  `EntityRegistry` (каждое действие объявления, `view` и формы
  `FieldAccess`), названия — из `EntityRights` (как сейчас).
- `EntityActionPermissionContractTest` меняет смысл: вместо сверки с
  аннотациями контроллеров он проверяет, что у каждой пары объявления есть
  название в `EntityRights`, а форма принадлежит модулю объявления
  (`PermissionAreas.ownerOf`). `PermissionCodesTest` проверяет коды форм
  объявлений по тому же правилу, что и аннотации.
- Новая сущность получает выдачу прав ролям миграцией данных модуля (как
  делает генератор сейчас: `md_forms`, действия, роли).

### 6.11. Чтение чужих данных

SQL runtime строится только из объявления, и объявление подчиняется
ADR-0026: `from`, соединения ссылок и выражения называют таблицы своего
модуля и `*_pub_*`-представления других. Сущность объявлена в пакете
модуля, поэтому владелец известен. `EntitySqlBoundariesTest` создаёт
`EntityRegistry` из всех объявлений и прогоняет их SQL-фрагменты через
разбор `ModuleBoundariesTest` (таблица чужого модуля — нарушение).
`EntityStoreRepository` — `@Repository` в `common` (инфраструктура,
ADR-0026, ограничения): правило «SQL только в репозиториях»
(`ServicesRunNoSqlTest`) соблюдено, а границы модулей проверяются по
объявлениям.

### 6.12. Ошибки

Все ответы — `ApiException` (ADR-0021); новые ключи — в ru, uz и en
(`ErrorTextsTest`). Существующие ключи переиспользуются.

| Случай | Статус и `code` | Ключ |
|---|---|---|
| нет сущности или нет `view` | 404 `not_found` | `error.common.entity_not_found` (есть) |
| нет записи или вне скоупа | 404 `not_found` | `error.common.record_not_found` (есть) |
| нет права операции | 403 | `error.permission_denied_action` (есть) |
| нет `If-Match` / устарел | 428 / 409 `revision_conflict` | есть (ADR-0024) |
| поля не прошли проверку | 422 `validation_failed` | `error.common.record_fields_invalid` (есть) + ошибки полей |
| неизвестное или скрытое свойство | 422, поле `unknown_field` | `error.field.unknown` |
| readonly | 422, поле `readonly` | `error.field.readonly` |
| ссылка не найдена или в архиве | 422, поле `not_found` / `archived` | `error.field.ref_not_found`, `error.field.ref_archived` |
| недопустимый переход | 422 `entity_transition_not_allowed` (новый `ErrorCode`, параметры `from`, `action`) | `error.common.entity_transition_not_allowed` |
| строк коллекции больше лимита | 422, поле `lines` `too_many` | `error.field.too_many` |
| тело больше лимита | 413 | существующий ответ лимита |

Для 422 с собственным кодом добавляется фабрика
`ApiException.unprocessable(ErrorCode, key, params, errors)`. Существующий
`STATUS_TRANSITION_FORBIDDEN` (409) задач не используется runtime.

Адреса полей: `title`, `attributes.cfRegion`, `total.amount`,
`lines[3].qty` (индекс — позиция строки **в теле запроса**, с нуля),
`lines[3]` (строка целиком), `""` (запись целиком), `params.reason`
(параметр действия), `rows[17].qty` (строка импорта, §10.1).

### 6.13. Описание в OpenAPI

Критерий «все эндпоинты сущностей в OpenAPI» выполняется генерацией
описания из объявлений, а не одним шаблоном `{code}`:

- `config.openapi.EntityOpenApiCustomizer` (`OpenApiCustomizer`, как
  `ApiDocsConfig`) для каждой сущности на runtime добавляет конкретные пути
  (`/api/v1/entities/ms.notes`, `/api/v1/entities/ms.notes/{id}`, действия
  `/api/v1/entities/ms.notes/{id}/actions/pin`, …) с `operationId`
  (`listMsNotes`, `getMsNotes`, `createMsNotes`, `patchMsNotes`, …), тегом
  сущности и схемами `MsNotesRecord`, `MsNotesCreate`, `MsNotesPatch`,
  `MsNotesPage`, построенными из `EntityField`: тип → JSON Schema (`MONEY` —
  `$ref Money`, `MULTI_REF` — `array<int64>`, `FILE` — `$ref EntityFile`,
  `ENUM`/`SELECT` — `enum`), `required` — из формы, `readOnly` — из
  readonly и вычисляемых. Поля с `requires` описываются (описание не
  зависит от зрителя) с пометкой `x-requires`.
- Шаблонные обработчики `{code}` из описания убираются
  (`@Hidden`/фильтр), `OpenApiContractTest` считает их описанными, если для
  каждой сущности есть конкретные пути.
- Spectral: правило `smc-path-kebab-case` принимает сегмент-код сущности
  после `/entities/` (`[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+`), правило
  `smc-post-states-its-status` — пути `…/actions/<код>` как действия (200).
- `npm run api:types` даёт вебу типы каждой сущности
  (`ApiSchema<'MsNotesRecord'>`) для своих экранов (канбан).

### 6.14. Контроллеры модулей

Клиентов нет (решение 3), поэтому переход **без сосуществования**: в той
же ветке, где сущность включается на runtime, её контроллер, сервисные
методы CRUD, репозиторий CRUD и строки `ApiDeprecations` её путей
удаляются, веб переходит на `/api/v1/entities/{code}`, изменение
записывается в CHANGELOG (`### Changed`, трейлер `Api-Breaking:`).
Остаются только операции, которых runtime не даёт (вход, смена пароля,
матрица прав, канбан-порядок, если он появится), — и только как действия
сущности или отдельные эндпоинты модуля со своим обоснованием в описании
шага (§8).

### 6.15. Генератор

`scripts/dev/create-module.ps1` в шаге 4 переписывается под модель:
миграции (таблица по §14.1 и данные прав), объявление и хуки (два
серверных файла), ключи ru/uz/en и класс контрактного теста (§11). Попутно
исправляется найденное при разборе (предположение, не запускалось):
генератор пишет форму `"<code>"` с модулем `ms` в `EntityRights`, и
`EntityActionPermissionContractTest` не находит владельца формы — проверка
`test-create-module.ps1` подтвердит или опровергнет это первой (подтвердилось
и исправлено в шаге 1, §3.6, О12). Кроссплатформенный CLI — пункт 6.1.

## 7. Общий экран `/e/:code` (5.5)

### 7.1. Маршруты и состав

Один ленивый маршрут в `app.routes.ts` (дочерний `AppShellComponent`,
`authGuard`), компоненты в `apps/web/src/app/shared/entity/page/`:

| Путь | Компонент | Что |
|---|---|---|
| `/e/:code` | `SMTEntityListPage` (`smt-entity-list-page`) | `smt-entity-toolbar` + `ui-server-table` (`registryTableConfig` по `query-meta`, сохранённые виды, фильтр, выгрузка, выбор строк) + кнопка «Создать» по `actions` |
| `/e/:code/new` | `SMTEntityEditPage` | `smt-entity-form` + коллекции (`smt-entity-lines`) + сохранение |
| `/e/:code/:id` | `SMTEntityRecordPage` | `smt-entity-card` с вкладками (§9.3): поля, коллекции, связанные списки, история, файлы; кнопки действий из `record.actions` |
| `/e/:code/:id/edit` | `SMTEntityEditPage` | форма с `If-Match` из загруженной ревизии |

- Охрана: `entityGuard` загружает `form-meta` (404 → экран «не найдено»),
  проверяет `moduleActiveGuard` по `EntityMenu.module`; права — по ответу
  сервера, а не по коду экрана.
- Данные: `EntityApi` (`shared/entity/entity.api.ts`) — `page`, `get`,
  `create`, `patch(id, body, revision)`, `remove`, `setArchived`, `action`;
  ошибки 409/428 — `SaveErrorNotifier`, 422 — `serverProblems` на поля и
  строки (`lines[3].qty` → ячейка строки).
- Меню: `EntityMenu.route` по умолчанию `/e/<code>`; пункт без своего
  маршрута больше не падает в `'**'`.
- Новые контролы кита для типов 5.2 (их сейчас нет): `smt-money-input`
  (сумма + валюта), `smt-json-editor` (текст с проверкой JSON),
  загрузка файла поля поверх `smt-dropzone`, мультивыбор ссылок — существующий
  `smt-multi-data-select`; `smt-dynamic-field` получает типы `email`,
  `phone`, `url`, `money`, `multi_ref`, `file`, `image`, `json`.
- Условная видимость и readonly — `computed` от значений формы по
  `visibleWhen` из `form-meta` (тот же DSL, что на сервере).

### 7.2. Когда нужен свой экран

Свой экран оправдан, только если нужен **другой способ работы**, а не
другое оформление: доска (канбан), календарь, карта, редактор документа,
мастер из нескольких шагов, экран нескольких сущностей (дашборд). Свой
экран всё равно берёт данные из `/api/v1/entities/{code}` и части
`smt-entity-*`; маршрут — свой, пункт меню — `EntityMenu.route`.

Точечная замена без своего экрана — `provideEntityOverrides(code,
{cells, fields, sections, tabs})` в `app.config.ts`: шаблон ячейки, поля,
секции или вкладки по ключу (то же, что `smtEntityField` и `cells` в
`registryTableConfig` сегодня). Это код веба, поэтому сущность без
переопределений — 0 файлов экрана.

### 7.3. Проверка

- vitest: страницы на `formMetaFixture`/`queryMetaFixture` (§11.5) — список,
  создание, правка с 422 и 409, действия, вкладки.
- e2e `entity-page.spec.ts` на заметках через `/e/ms.notes` (критерий 5.5):
  создать → изменить → история → архив или удаление; a11y-сканы
  `/e/ms.notes` и формы.
- Критерий «новая сущность появляется в UI без веб-кода» проверяет e2e на
  эталонной сущности из §9.4: у неё нет ни одного файла в
  `apps/web/src/app/features`.

## 8. Перевод задач, проектов и пользователей (5.6)

Решение 3 упрощает перевод: сущность переводится **сразу**, без
сосуществования старого и нового API и без конвертации данных
(dev-базы пересоздаются миграциями). Контрактные снимки ответов
(`EntityApiSnapshotTest`, `src/test/resources/entity-api/<code>/*.json`)
снимаются **после** перевода как новая точка отсчёта; разница со старыми
ответами описывается в CHANGELOG, а не сохраняется.

| Порядок | Сущность | Скоуп | Что даёт runtime | Что остаётся своим (действия, хуки, экран) |
|---|---|---|---|---|
| 1 | `tasks.types` — справочник типов задач (`ms_task_types`, внешних ссылок нет) | `all()` | список, форма, порядок (`sort_order`), архив вместо удаления системных | хук: системный тип не архивируется и не меняет код |
| 2 | `tasks.statuses` — статусы задач | `all()` | то же; `reference("code", "name")` | хук: используемый статус не удаляется (`error.task.status_in_use`), признак терминального |
| 3 | `tasks.projects` | `custom("projects", …)` — по участию и по задачам в скоупе (сегодня у карточки проекта скоупа нет — дефект закрывается здесь) | CRUD, участники как `MULTI_REF` на `md.users` с ролью — коллекция `members` (§9.1) | показатели (`totalTasks`, `progress`) — вычисляемые поля со скоупом зрителя |
| 4 | `tasks.items` | `custom("tasks", filterForTasks)`, псевдоним `t` | CRUD, статус — `REF` на `tasks.statuses`, проект — `REF`, участники — коллекция `members`, подзадачи — связанный список, файлы — `FILE` | канбан — свой экран поверх `/api/v1/entities/tasks.items` (§7.2); смена статуса — действие `set_status`; комментарии остаются ресурсом модуля (`/api/v1/tasks/{taskId}/comments`) до коллекции с отдельным правом |
| 5 | `md.users` | `orgUnit("org_unit_id", "id")` (сегодня у карточки пользователя скоупа нет — дефект закрывается здесь) | список, форма профиля, доп. поля, история, выгрузка | действия `block`, `unblock`, `reset_2fa`, `anonymize` (вместо `DELETE`); создание — хук, который отправляет приглашение или задаёт пароль через `kauth.service`; роли и права — матрица `md.assignments`, не поля сущности; хеш пароля не входит в объявление и в `md_pub_users` |

Вместе с заметками это 6 сущностей на модели (критерий ≥ 5).

Удаление legacy-фильтров: `LegacyTaskFilters` (`statusId`, `hideTerminal`,
`assignedUserId`, `memberRole`, `reporterId`, `overdue`, …) и
`MdUserListSql.LegacyUserFilters` (`state`, `roleId`, `managerId`,
`is2faEnabled`) удаляются в шагах 4 и 5. Пресеты веба
(`task-filter.service.ts`: «мои», «исполнитель», «наблюдатель», «поставлены
мной», «просроченные») становятся фильтрами DSL: поля `assigneeIds`
(`MULTI_REF`), `reporterId`, `overdue` (вычисляемое `BOOLEAN`), группы
`{"any": [...]}`. Критерий `Legacy*Filters = 0` проверяет
`NoLegacyFiltersTest` (ArchUnit: нет классов `Legacy*Filters`, нет
параметров списка вне DSL). Строки `ApiDeprecations` этих путей удаляются
вместе с контроллерами по решению владельца продукта для пункта 5.6.

## 9. Связи, статусы, действия (5.7)

### 9.1. `EntityCollection` — строки документа

```java
.collection(EntityCollection.of("lines", "orders.lines.title")
        .table("ex_order_lines", "l").parentColumn("order_id").positionColumn("position")
        .field(ref("productId", "orders.lines.product").column("product_id").target("ex.products").required())
        .field(number("qty", "orders.lines.qty").column("qty").scale(3).required().range(ONE_THOUSANDTH, null))
        .field(money("price", "orders.lines.price").amountColumn("price").currencyFrom("currency"))
        .field(money("amount", "orders.lines.amount").computed("l.qty * l.price").currencyFrom("currency"))
        .maxRows(500)
        .rule(Rules.notBefore(...)))
```

- Таблица строк: `id bigint identity`, `<parent>_id bigint not null
  references <родитель> on delete cascade` с индексом, `position int not
  null`, поля; без своей ревизии и `created_*` (аудит — у родителя).
- В форме — редактируемая таблица `smt-entity-lines` (добавить, удалить,
  переставить, ячейки — контролы типов поля). В ответе `GET` — массив строк
  до `maxRows`; больше — `GET …/collections/lines` страницами, а форма
  переходит в постраничный режим.
- Сохранение — в транзакции родителя (§6.3 шаг 10), семантика замены по id:
  строка с `id` — изменение, без `id` — вставка, отсутствующая в теле —
  удаление; `position` — порядок в массиве. Строка с чужим `id` (другого
  документа) — 422 `lines[i].id` `not_found`.
- Ошибки адресуются `lines[3].qty`; правило коллекции — `lines[3]` или
  `lines`.
- Хуки видят коллекцию в `EntityValues.collection("lines")` (строки с
  признаком `added`/`changed`/`removed`) и могут считать итог родителя
  (`total` — readonly-поле родителя, которое пишет `beforeSave`).

### 9.2. `EntityWorkflow` — статусы и переходы

```java
.workflow(EntityWorkflow.on("status")            // поле SELECT статуса, readonly для PATCH
        .state("draft", "orders.status.draft").initial()
        .state("posted", "orders.status.posted").locks("lines", "customerId", "currency")
        .state("cancelled", "orders.status.cancelled").terminal()
        .transition("post", "draft", "posted").permission("post").rule(Rules.atLeastOne("lines"))
        .transition("unpost", "posted", "draft").permission("unpost")
        .transition("cancel", "draft", "cancelled").permission("update").confirm("orders.cancel.confirm"))
```

- Поле статуса меняется **только переходом**: `PATCH` со статусом — 422
  `readonly`.
- Переход — `POST /api/v1/entities/{code}/{id}/actions/{transition}` с
  `If-Match`; порядок §6.3. Недопустимый из текущего состояния — **422**
  `entity_transition_not_allowed` с параметрами `{from, action}`; нет права —
  403; устаревшая ревизия — 409.
- `locks(...)` делает поля и коллекции readonly в состоянии (форма и
  сервер, §4.4); `terminal()` — из состояния нет переходов, запись только
  читается.
- Хуки перехода — `beforeSave`/`afterSave` с `operation() == ACTION` и
  `action() == "post"`: проведение (запись движений своего модуля, проверка
  остатков) выполняется в `afterSave`, в той же транзакции.
- Состояния — код, а не данные: администратор их не меняет. Статусы задач
  (данные администратора, колонки канбана) — справочник и `REF`, а не
  процесс (§8).

### 9.3. Карточка: вкладки и связанные списки

Раскладка получает вкладки: `.tab("main", sections...)`, `.tab("lines",
collection("lines"))`, `.tab("tasks", related("tasks.items", "projectId"))`,
`.tab("history", HISTORY)`, `.tab("files", files("attachments"))`.
Связанный список — список **другой** сущности с фильтром
`{"field":"projectId","op":"eq","value":<id>}` через её runtime-список: права
и скоуп цели применяются её же правилами, вкладка без права `view` на цель
не показывается. `form-meta` отдаёт вкладки, `smt-entity-card` рисует их.

### 9.4. Эталон «документ со строками и статусами»

Эталон — сущность `example.orders` (заказ: номер по `sequence`, клиент —
`REF`, валюта, строки товара с количеством, ценой и суммой, итог,
процесс «черновик → проведён → отменён») и справочник `example.products`,
в модуле `example` (`com.smartup24.cms.instance.example`, таблицы `ex_*`,
область прав `example`). Модуль — два серверных файла на сущность и ноль
файлов веба; он же — эталон «справочник» и «документ» для cookbook 6.6.
Включение в поставку — вопрос В3.

e2e `entity-document.spec.ts` (критерий 5.7): создать заказ → добавить 3
строки → сохранить → провести → убедиться, что поля заблокированы, а
`unpost` без права даёт отказ → история показывает создание, строки и
проведение.

## 10. Импорт, отчёты, поиск (5.8)

### 10.1. Возможность `IMPORT`

- `GET /api/v1/entities/{code}/import-template` — xlsx: колонки —
  импортируемые поля зрителя (подпись на языке зрителя, ключ — во второй
  скрытой строке), листы-подсказки вариантов `SELECT`/`ENUM`.
- Файл загружается в `mf` (проверка и ClamAV), затем
  `POST /api/v1/entities/{code}/imports {"fileId": "<uuid>", "mode": "dry_run"|"apply", "key": "code"}`
  → 202 + `Location: /api/v1/imports/{id}`; право `<форма>.import`.
- Задание `report.import` (`JobHandler` модуля `report`, `run(args,
  JobAttempt)`), журнал `report_imports` (по образцу `report_exports`:
  владелец, сущность, режим, состояние, счётчики, файл отчёта, срок
  хранения 7 дней — `RetentionPolicy`). Модуль `report` уже владеет
  выгрузками и зависит от `jobs` и `mf`; `common` даёт ему интерфейс
  `EntityImporter` (строка → порядок §6.3 с `operation() == IMPORT`).
- Задание читает файл потоком, строки — пачками по 500 в отдельной
  транзакции на пачку; в пачке каждая строка проходит проверку, правила и
  хуки (точка сохранения на строку: ошибка строки откатывает только её).
- `dry_run` — все проверки без записи; `apply` — upsert по ключу импорта
  (`.importKey("code")`, уникальная колонка): найдена — изменение из
  текущей ревизии (без `If-Match`, вопрос В7), не найдена — создание.
- Ошибки по строкам: `rows[17].qty` (номер строки файла), отчёт — тот же
  xlsx с колонкой ошибок; `GET /api/v1/imports/{id}` отдаёт счётчики и
  первые 100 ошибок, файл отчёта — через выгрузки.
- Лимиты: файл 50 МБ (как `mf`), 100 000 строк (предположение), ограничения
  распакованного размера xlsx — пункт 7.6. Критерий «10 000 строк < 60 с»
  меряет `EntityImportPerformanceTest` на embedded PostgreSQL.

### 10.2. Отчёты и виджеты без Java-кода

Отчёт — **данные**, а не объявление: сохранённый вид списка
(`md_list_views`, ADR-0016 §2.7) получает вид `kind = table | report | widget`
и состояние отчёта, которое сервер проверяет по реестру:

```json
{"groupBy": [{"field": "status"}, {"field": "createdAt", "trunc": "month"}],
 "measures": [{"op": "count"}, {"op": "sum", "field": "total"}],
 "filter": [...], "chart": "bar"}
```

- Группировка — по полям `SELECT`, `ENUM`, `REF`, `BOOLEAN`, датам
  (`day|week|month|quarter|year`); меры — `count`, `sum`/`avg`/`min`/`max`
  по `NUMBER` и сумме `MONEY` (сумма по валюте — валюта входит в
  группировку автоматически).
- `GET /api/v1/entities/{code}/reports/{viewId}` строит агрегатный SQL из
  `QueryList` сущности (`select <group>, <agg> from … where <скоуп> <фильтр>
  group by …`), до 1000 групп, тот же скоуп и права на поля, что у списка;
  поле с `requires` без права — 422 как в фильтре.
- Виджет — отчёт с `chart` на дашборде (`analytics` показывает виджеты
  зрителя); общие для роли отчёты и виджеты — вопрос В6.
- Веб: `smt-entity-report` (таблица и график по ответу) во вкладке списка
  «Отчёт» и на дашборде.

### 10.3. Возможность `SEARCH`

Сегодня поиск жёстко знает типы (`SearchCollectionSchema.forProfile` —
`TASK`, `PROJECT`, `USER`, `NOTE`; `search_projection_versions` ограничен
`TASK|PROJECT|USER` в V026 — заметки в индекс не попадают) и доступен только
неограниченному администратору (ADR-0013 §2.5, fail-closed).

- Объявление: `.search(EntitySearchSpec.title("title").body("contentMd")
  .facets("status"))` — поля с `list != null` без `requires`.
- Модуль `search` строит коллекцию `entity_<код с подчёркиваниями>` из
  спецификации (`SearchCollectionSchema` принимает спецификацию вместо
  `switch`); тип в `search_projection_versions` — код сущности (новая
  миграция снимает CHECK V026 и переносит коллекции поколения из колонок
  `search_generations` в строки `search_generation_collections`).
- В документ пишутся ключи скоупа: `owner_id`, `org_unit_id` или (для
  `custom`) `scope_keys` — список id, которые даёт `ScopeProvider`
  (у задач — участники).
- Запрос: Typesense фильтрует по скоупу зрителя (`ALL` — без фильтра;
  `SUBTREE`/`UNITS` — `org_unit_id:[…]`; `SELF`/`owner` — `owner_id:=я`), а
  найденные id **перепроверяются в БД** одним запросом со скоупом
  (`id = any(:ids)` + предикат) — индекс не источник авторизации; запись,
  которую перепроверка отбросила, не показывается.
- Снятие fail-closed для сущностей с `SEARCH` меняет ADR-0013 §2.5 —
  вопрос В8; до ответа поиск по ним остаётся только у неограниченного
  администратора, а скоуп всё равно пишется в документ.

## 11. Тест-кит `EntityContractTestKit` (6.2)

Кит обязателен **до** включения runtime (риск из плана: общий CRUD — новая
поверхность атаки).

### 11.1. Что пишет автор

```java
class ExampleOrdersContractTest extends EntityContractTestKit {
    @Override protected String entity() { return "example.orders"; }

    @Override protected EntityFixture fixture(FixtureContext ctx) {
        long product = ctx.create("example.products", Map.of("code", "P1", "name", "Мука"));
        return EntityFixture.valid(Map.of("customerId", ctx.anyUserId(), "currency", "UZS",
                        "lines", List.of(Map.of("productId", product, "qty", "3", "price", "10.00"))))
                .update(Map.of("comment", "изменено"))
                .invalid("currency", "usd", "invalid")
                .invalid("lines[0].qty", "-1", "out_of_range")
                .transition("post");
    }
}
```

Одно наследование даёт весь набор проверок (`@TestFactory` с
динамическими тестами по объявлению). `EntityFixture` описывает только
данные; пользователей, роли и скоупы кит создаёт сам.

### 11.2. Что проверяет кит

| Группа | Проверки |
|---|---|
| Метаданные | `form-meta` и `query-meta` согласованы (тип поля формы = тип поля списка, §4.1); снимок обоих в `src/test/resources/entity-meta/<code>.*.json`; у каждого типа поля есть контрол и операции |
| CRUD | создание → 201 + `Location` + `ETag`; чтение; запись в списке; `PATCH` с `If-Match`; удаление 204 или архив; повтор с тем же `Idempotency-Key` → тот же `Location`, одна строка |
| Ревизия | `PATCH` без `If-Match` → 428, с устаревшей → 409; два параллельных `PATCH` из одной ревизии → ровно один 409 |
| Права | без `view` — **404** на всех путях (список, запись, `form-meta`, `query-meta`, история, выгрузка, массовое действие, действие, файл); с `view` без `create`/`update`/`delete`/действия → 403; `actions` в `form-meta` и в записи = права зрителя |
| Скоуп | пользователи A и B в разных единицах (или владельцы для `owner`): запись A для B — **404, а не 403** на чтении, изменении, удалении, действии, истории, файле; список, подсчёт, выгрузка и поиск B её не содержат; ссылка B на невидимую запись → 422 `not_found`; для `custom` — сценарий из `fixture` |
| Права на поля | поле с `requires` отсутствует в `form-meta`, `query-meta`, записи, списке, выгрузке, истории, вебхуке; запись его → 422 `unknown_field`; `readonlyUnless` → 422 `readonly` |
| Проверка | каждый `invalid(...)` → 422 с адресом и кодом; неизвестное свойство → 422; неверный тип JSON → 422 |
| Аудит | создание, изменение, удаление пишут `audit_log` со всеми полями `history`; `GET /history/{code}/{id}` показывает их с подписями |
| Выгрузка | задание выгрузки отдаёт те же строки и колонки, что список зрителя |
| События | одно `EntityChanged` на изменение, после коммита; строка `kwh_outbox` с `<форма>.updated` при подписке; откат — ни события, ни строки |
| Коллекции и процесс | ошибка строки — адрес `lines[i].поле`; недопустимый переход → 422 `entity_transition_not_allowed`; переход без права → 403; заблокированное поле → 422 `readonly` |
| OpenAPI | конкретные пути и схемы сущности есть в описании |

### 11.3. Фикстуры

- База — существующая инфраструктура: `TestDatabases.migratedCopy(prefix)`
  (embedded PostgreSQL zonky, копия мигрированного шаблона на класс) и
  `EmbeddedPostgresTest` с MockMvc (`webAppContextSetup(...).apply(springSecurity())`).
  Testcontainers плана не нужен для кита: он уже используется точечно
  (миграции, права БД, идемпотентность), а основной путь тестов — embedded.
  Расхождение версии embedded PostgreSQL с production 18 — ограничение
  (§17).
- Вход: новый общий `support/TestSessions` (`login(userId)` → cookie сессии
  и XSRF) и `support/TestUsers` (`withRights(Map<form, actions>)`,
  `inUnit(orgUnitId, rule)`) заменяют 20 скопированных приватных `login()`.
- Скоуп: кит создаёт две оргединицы, роль с правилом `UNITS` и двух
  пользователей (`MdScopeService.assignUserOrgUnits`).

### 11.4. Обязательность

- `EntityContractCoverageTest`: у каждой сущности на runtime ровно один
  наследник кита в тестах; сущность без него валит сборку. Это и есть
  «кит подключён у 100% сущностей».
- `EntitySchemaContractTest`: каждая колонка объявления есть в
  `information_schema` мигрированной базы с совместимым типом, есть
  `revision`, у `ARCHIVE` — `archived_at`/`archived_by`, у `orgUnit` — FK и
  индекс (подготовка к 6.4).
- Порядок включения (§15): runtime появляется выключенным
  (`smc.entities.runtime.enabled=false`, включён только в тестовом профиле),
  кит и контракт заметок зеленеют, затем флаг включается по умолчанию и
  удаляется в шаге 5.

### 11.5. Веб-помощники

- `apps/web/src/testing/entity/`: `formMetaFixture(code, fields)`,
  `queryMetaFixture(code, fields)` (на основе существующих
  `form-meta.ts`/`registry-meta.ts`), `entityRecord(meta, values)`,
  `provideEntityTesting()` (моки `EntityApi`, `FormMetaService`,
  `QueryMetaService`), `EntityFormHarness` (заполнить поле по ключу, строку
  коллекции, прочитать ошибку поля, нажать действие).
- `e2e/support/entity-page.ts`: `openEntity(page, code)`,
  `fillField(key, value)`, `addLine(values)`, `runAction(code)`,
  `expectFieldError(key, code)`; a11y-фикстуры получают мок
  `/entities/menu` и `/entities/{code}`.

## 12. Безопасность runtime

Общий эндпоинт обслуживает все сущности, поэтому ошибка в нём — ошибка
везде. Модель угроз (STRIDE по пункту 7.6 дополняется этой строкой):

| Угроза | Как защищаемся | Проверка |
|---|---|---|
| Перебор сущностей и записей | неизвестная сущность, сущность без `view`, запись вне скоупа — одинаковый 404; id только числом | кит: «404, а не 403» |
| Обход скоупа (IDOR) | скоуп обязателен в объявлении; запись читается с предикатом `for update` до любой проверки; ссылки проверяются по скоупу цели; коллекции и файлы — через родителя | кит: группа «Скоуп» |
| Массовое присваивание | принимаются только объявленные записываемые видимые поля; `id`, `revision`, `created_*`, `modified_*`, владелец, `archived_*` пишет сервер; единица записи — только в скоупе автора | кит: `unknown_field`, `readonly` |
| SQL-инъекция | идентификаторы — только из объявления, проверены шаблонами при старте (`^[a-z_][a-z0-9_]*$`); ключи клиента ищутся в карте полей и в SQL не попадают; значения — параметры; фильтр и сортировка — `QueryCompiler` | `EntitySqlSafetyTest` (злые ключи, кавычки, `;` в значениях) |
| Утечка полей | `requires` применяется к метаданным, чтению, записи, выгрузке, истории, поиску, вебхуку (§5.2); проекция ответа строится по полям зрителя, а не «все колонки» | кит: «Права на поля» |
| Подмена данных чужого модуля | SQL объявления — только свои таблицы и `*_pub_*` (ADR-0026); хук пишет чужое только через сервис владельца | `EntitySqlBoundariesTest`, `ModuleBoundariesTest` |
| Отказ в обслуживании | тело ≤ 512 КБ, строк коллекции ≤ `maxRows` (500), `MULTI_REF` ≤ 100, `JSON` ≤ 64 КБ, лимиты DSL прежние; импорт и отчёты — в `expensive-paths` ограничителя частоты; импорт — в очереди, не в запросе | тесты лимитов в ките |
| Гонки и потерянные изменения | `If-Match` + условный `update`; строка блокируется `for update` на время проверки и хуков | кит: «Ревизия» |
| Повтор и дубли | `Idempotency-Key` фильтра в одной транзакции с изменением | кит: CRUD |
| Опасные значения | `URL` — только `http`/`https`; ссылки в вебе — `rel="noopener noreferrer"`; markdown — существующий санитайзер `ui-markdown-view`; файлы — проверка содержимого и ClamAV; xlsx импорта — лимиты распаковки (7.6) | тесты типов (§4.8) |
| Отказ хука | исключение хука откатывает транзакцию; непредвиденное — 500 без подробностей (ADR-0021); `afterCommit` не влияет на ответ | кит: события при откате |
| Вебхук раскрывает данные | `data` без полей с `requires`; доставка — только на разрешённые хосты (`WebhookTargetPolicy`), подпись HMAC | кит: «События» |

## 13. Производительность

- **Списки** — keyset ADR-0016 без изменений: сортировка по объявленному
  сортируемому полю + `id`, курсор с отпечатком; подсчёт — только на первой
  странице (оценка планировщика для больших таблиц, `withEstimatedTotal`).
- **Без N+1:** подписи ссылок — соединения с опубликованными
  представлениями в том же SQL (ADR-0026: простое представление
  подставляется планировщиком, индексы базовой таблицы работают);
  `MULTI_REF` и подписи без представления — один запрос на страницу
  (`owner_id = any(:ids)`); строки коллекций в `GET` записи — один запрос;
  проверка ссылок при сохранении — один запрос на поле; справочники `ENUM`
  — из кэша.
- **Индексы**: FK каждой ссылки, `parent_id` строк, `(target_id)` связей,
  `org_unit_id` и владелец скоупа, сортируемые колонки, GIN по
  `attributes` — правила ADR-0020 и пункта 3.7; `SchemaOrderTest` («FK без
  индекса = 0») охватывает новые таблицы автоматически.
- **Метаданные**: `form-meta`/`query-meta` строятся из объявления один раз
  (кэш 5.0), доп. поля — по ревизии каталога доп. полей.
- **Запись**: одна транзакция, `for update` одной строки, пакетная вставка
  строк коллекции (`batchUpdate`).
- **Цели** (предположение, уточняются замером в шаге 3): p95 `GET` страницы
  50 строк < 150 мс на 1 млн строк; `PATCH` документа с 100 строками
  < 300 мс; импорт 10 000 строк < 60 с.

## 14. Миграции и откат

Клиентских установок нет (решение 3): раздел касается только dev-баз и
новых файлов схемы.

### 14.1. Соглашение о таблице сущности

Новая таблица (ADR-0020, `MigrationLintTest`):

```sql
create table ex_orders (
    id           bigint generated always as identity constraint ex_orders_pkey primary key,
    number       text not null constraint ex_orders_ck_number check (char_length(number) <= 32),
    customer_id  bigint not null constraint ex_orders_fk_customer references md_users (id),
    currency     text not null constraint ex_orders_ck_currency check (currency ~ '^[A-Z]{3}$'),
    total_amount numeric(19, 4) not null default 0,
    status       text not null default 'draft' constraint ex_orders_ck_status check (status in ('draft', 'posted', 'cancelled')),
    org_unit_id  bigint not null constraint ex_orders_fk_org_unit references md_org_units (id),
    attributes   jsonb not null default '{}' constraint ex_orders_ck_attributes check (jsonb_typeof(attributes) = 'object'),
    archived_at  timestamptz,
    archived_by  bigint constraint ex_orders_fk_archived_by references md_users (id),
    created_by   bigint not null constraint ex_orders_fk_created_by references md_users (id),
    modified_by  bigint not null constraint ex_orders_fk_modified_by references md_users (id),
    created_at   timestamptz not null default clock_timestamp(),
    modified_at  timestamptz not null default clock_timestamp(),
    revision     bigint not null default 1
);
create unique index ex_orders_number_uq on ex_orders (number);
create index ex_orders_customer_id_idx on ex_orders (customer_id);
create index ex_orders_org_unit_id_idx on ex_orders (org_unit_id);
-- and one index per remaining FK column
```

- DDL и данные (формы, действия, выдача ролям, меню модуля) — разные файлы.
- Опубликованное представление — только если сущность читают другие модули.
- Номера миграций выдаёт координатор в момент работы (свободный следующий
  `V`, манифест `-Dmigrations.manifest.append=true`); этот ADR номеров не
  резервирует.

### 14.2. Перевод существующих таблиц (5.6)

Существующие таблицы (`ms_task_types`, `ms_task_statuses`,
`ms_task_projects`, `ms_tasks`, `md_users`) приводятся к соглашению новыми
миграциями, без сохранения данных клиентов: недостающие `modified_at`,
`archived_at`/`archived_by`, `attributes`; переименование колонок, если нужно
для объявления; данные dev-баз допустимо пересоздать. Миграция с изменением
данных помечается `destructive: approved` с причиной (правило
`ai-context.md` §5). Выпущенные миграции не меняются.

### 14.3. Откат

- Откат шага — `git revert` слияния и пересоздание dev-базы
  (`docker compose down -v`, затем `migrate`): у Flyway нет откатных
  миграций, а данных клиентов нет.
- До шага 5 runtime можно выключить флагом
  `smc.entities.runtime.enabled=false` без отката кода (контроллер модуля
  заметок к этому моменту ещё не удалён — §15).

## 15. План поставки

Каждый шаг — отдельная ветка и слияние (≤ 400 строк смыслового диффа или
обоснование). Шаги 2, 6 и 10 могут идти параллельно с соседними после
шага 1.

| Шаг | Пункт | Что | Зависит от | Критерии приёмки плана (ACC) |
|---|---|---|---|---|
| 0 | 5.0 | гигиена модели (параллельный исполнитель) | — | контракт «поле формы ↔ поле списка», история всех полей, 404 чужой записи, доп. поле без перезагрузки |
| 1 | 5.1 | **выполнен** (§3.6): `EntityField`, `Entity`, вывод `FormField`/`QueryList`, `QueryListSource`; заметки — одно объявление; схемы `FormFieldMeta` в OpenAPI | 0 | «0 параллельных объявлений» — `EntityFieldsSingleSourceTest`; «снимок `query-meta` заметок не изменился» — `EntityMetaSnapshotTest` |
| 2 | 5.3 | `EntityScope` (обязателен), `DataScopes`, `FieldAccess`, обязательная ревизия, `ARCHIVE` | 1 | «скоуп у 100% сущностей» — построитель + `EntityScopeDeclaredTest`; «права на поля: form-meta, чтение, запись, выгрузка» — тест прав на поля; «устаревшая версия → 409» — `RecordRevisionIntegrationTest` + кит |
| 3 | 5.4 (а) | `EntityController`, `EntityRuntime`, `EntityStoreRepository`, порядок §6.3, `EntityHooks`, `EntityRule`, аудит, `EntityChanged` → Spring; флаг выключен вне тестов | 2 | — (промежуточный) |
| 4 | 6.2 (ядро) | кит, `TestSessions`/`TestUsers`, контракт заметок, `EntityContractCoverageTest`, `EntitySchemaContractTest`; генератор под модель | 3 | «модуль проходит кит одним наследованием» — `MsNotesContractTest` |
| 5 | 5.4 (б) | флаг включён по умолчанию и удалён; заметки на runtime: контроллер, сервис, репозиторий и `MsNoteRecords` удалены; веб заметок на `EntityApi`; вебхуки и поиск слушают `EntityChanged`; `EntityOpenApiCustomizer`; правки Spectral | 4 | «заметки — ≤ 2 серверных файла» — подсчёт файлов `ms/note` без `package-info.java` в `EntityFileBudgetTest` (действие `pin` заменяется `PATCH {isPinned}` с тем же правом `update`, поэтому заметкам хватает объявления); «вебхук `notes.updated` без кода модуля» — интеграционный тест outbox; «все эндпоинты сущностей в OpenAPI» — `OpenApiContractTest`; «тест-кит зелёный для каждой сущности» — `EntityContractCoverageTest` |
| 6 | 5.2 | **выполнен** (§4.9): типы полей (каждый — сервер, контрол, колонка и фильтр, выгрузка, тест); контролы кита | 1 (типы ссылок — 2) | `FieldTypeMatrixTest`, `field-type-matrix.spec.ts` |
| 7 | 5.5 | маршрут `/e/:code`, страницы, `entityGuard`, `provideEntityOverrides`; заметки — на общей странице | 5 | «новая сущность в UI без веб-кода», «e2e на общей странице» — `entity-page.spec.ts` |
| 8 | 5.6 | типы задач → статусы → проекты → задачи → пользователи (§8), удаление legacy-фильтров | 5, 6, 7 | «≥ 5 сущностей», «`Legacy*Filters` = 0» — `NoLegacyFiltersTest`; «снимки совпадают или изменение в CHANGELOG» — `EntityApiSnapshotTest` + CHANGELOG |
| 9 | 5.7 | `EntityCollection`, `EntityWorkflow`, действия, вкладки, связанные списки, модуль `example` | 5, 6, 7 | «документ без своего экрана» — 0 файлов `example` в вебе; «переход → 422 с кодом» — кит; e2e `entity-document.spec.ts` |
| 10 | 5.8 | `IMPORT`, отчёты и виджеты, `SEARCH` | 9 (порядок — решение 2) | «импорт 10 000 строк < 60 с» — `EntityImportPerformanceTest`; «отчёт или виджет без Java-кода» — e2e сохранения отчёта; «`SEARCH` с учётом скоупа» — интеграционный тест поиска двумя пользователями |
| 11 | 6.2 (веб) | веб-помощники и e2e-помощники; кит у 100% сущностей | 7, 8 | «кит подключён у 100% сущностей» — `EntityContractCoverageTest` |

Гейт 3 плана (≥ 5 сущностей на модели, новая сущность — ≤ 2 серверных
файла и 0 файлов экрана) закрывается после шага 9: сущности шага 8 и
эталон шага 9.

## 16. Проверки (сводка)

Сервер: `EntityMetaSnapshotTest`, `EntityFieldsSingleSourceTest`,
`EntityScopeDeclaredTest`, `FieldTypeMatrixTest`, `EntitySqlBoundariesTest`,
`EntitySqlSafetyTest`, `EntitySchemaContractTest`,
`EntityContractCoverageTest`, `EntityFileBudgetTest`, `NoLegacyFiltersTest`,
`EntityApiSnapshotTest`, `EntityImportPerformanceTest`, наследники
`EntityContractTestKit` на каждую сущность; расширяются
`EntityActionPermissionContractTest`, `PermissionCodesTest`,
`RevisionedUpdatesRaiseRevisionTest`, `ChangesNameTheirRevisionTest`,
`OpenApiContractTest`, `ErrorTextsTest`. Веб: `field-type-matrix.spec.ts`,
спеки страниц `/e/:code`, помощники `src/testing/entity`. E2E:
`entity-page.spec.ts`, `entity-document.spec.ts`, a11y-сканы общей страницы.

## 17. Последствия

- Новая сущность — миграция, объявление, хуки (по необходимости) и ключи
  переводов; REST, экран, права, аудит, события, выгрузка, импорт и поиск —
  из платформы.
- Модули перестают писать CRUD, аудит и вызовы вебхуков; ошибки скоупа и
  ревизии чинятся в одном месте для всех.
- Общий эндпоинт — единая точка отказа: дефект в `EntityRuntime` затрагивает
  все сущности. Смягчение — кит на каждую сущность и порядок §6.3 без
  точек расширения внутри шагов 1–8.
- Схема каждой сущности описана в OpenAPI, веб получает типы — свои экраны
  остаются типизированными.
- Документация меняется в шагах: extension-points (новые точки, закрытые
  пробелы 1–3 и 5 раздела 7), руководство по модулю (чек-лист: миграция →
  объявление → хуки → контракт), карта модулей (модуль `example`), ADR-0019
  §2.6 (уровень 2 получает отчёты и виджеты), ADR-0013 §2.5 (после ответа
  на В8).

### Ограничения (приняты)

- Объявление — код: изменение поля — выпуск и миграция (уровень 3 ADR-0019
  вне решения).
- Состояния процесса — код; статусы, которые ведёт администратор, — только
  справочник и `REF`.
- Embedded PostgreSQL тестов старше production 18 (ADR-0026): кит не
  проверяет поведение, которое различается между версиями; такие проверки
  — точечно на Testcontainers.
- Агрегаты отчётов идут по OLTP-базе: для больших объёмов нужен перенос в
  хранилище (`warehouse`) — отдельное решение.

## 18. Рассмотренные альтернативы

| Вариант | Когда подходит | Почему нет |
|---|---|---|
| **A. Runtime по объявлению** (выбрано, решение 1) | платформа, где сущности добавляют часто, а правила одинаковы | — |
| B. Генерация кода при сборке (контроллер, сервис, DTO на сущность) | нужны свои DTO и полный контроль над каждым путём | код размножается и расходится после правки руками; исправление платформы требует перегенерации всех модулей; отклонено владельцем продукта |
| C. Определения в БД и одна таблица EAV/`jsonb` на все сущности | no-code у разных клиентов | теряются типы, FK, индексы и уникальность; скоуп и права в данных — риск (ADR-0019, вариант B) |
| D. GraphQL поверх объявлений | клиенты с произвольными выборками | новый протокол и модель авторизации на поле; REST, keyset и OpenAPI уже есть |
| E. Spring Data REST | CRUD над JPA-сущностями | нет JPA в проекте (JdbcClient), нет скоупа, ревизий, модели ошибок и `query-meta` |

## 19. Открытые вопросы владельцу продукта

| # | Вопрос | Что зависит | Предложение по умолчанию |
|---|---|---|---|
| В1 | Нужны ли справочники, которые администратор заводит без выпуска (общая таблица элементов справочника), или `ENUM` берёт значения только из объявленных сущностей-справочников? | шаг 6 (тип `ENUM`), уровень 2 ADR-0019 | только объявленные справочники в фазе 5; **шаг 6 принял как предположение** (§4.9) |
| В2 | Нужны ли регистры накопления (остатки, взаиморасчёты) при проведении, или в v2 достаточно статусов и хуков перехода? | шаг 9, модель документа | статусы и хуки; регистры — отдельный ADR |
| В3 | Модуль-эталон `example`: в поставке, выключенный по умолчанию (`md_installed_modules`), или только в профилях dev и e2e? | шаг 9, e2e, cookbook 6.6 | в поставке, выключен по умолчанию |
| В4 | Лимит строк документа в одном сохранении (предложено 500) и нужны ли документы больше | шаг 9, производительность | 500, больше — постранично |
| В5 | Нужны ли миниатюры изображений (генерация на сервере — новая зависимость) | шаг 6 (`IMAGE`) | без миниатюр: браузер масштабирует оригинал; **шаг 6 принял как предположение** (§4.9, Т11) |
| В6 | Отчёты и виджеты: только личные или публикуются роли (кто может публиковать) | шаг 10 | личные; публикация — право `md.list_views.publish` позже |
| В7 | Импорт с upsert меняет запись без `If-Match` (последний пишущий выигрывает, всё в аудите) — приемлемо? Порог «10 000 строк < 60 с» подтверждается? | шаг 10 | да, с пометкой источника в аудите |
| В8 | Снимаем ли ограничение «глобальный поиск только у неограниченного администратора» для сущностей с `SEARCH` при фильтре скоупа в индексе и перепроверке в БД (меняет ADR-0013 §2.5)? | шаг 10, критерий 5.8 «с учётом скоупа» | да, для сущностей с `SEARCH` |
| В9 | Конверт события вебхука и подпись метки времени (сейчас `X-Signature-Timestamp` не входит в подпись — повтор доставки нельзя отличить): меняем формат доставки сейчас, пока клиентов нет? | шаг 5 | да: конверт §6.9 и подпись `timestamp.body` |
| В10 | Пользователи: создание через приглашение или с паролем от администратора; блокировка, 2FA, анонимизация — действия сущности | шаг 8 | действия; создание — хук с приглашением |
| В11 | Архив — отдельное право `archive` или право `delete`? | шаг 2 | право `delete` по умолчанию, отдельное — по объявлению |
| В12 | Сторонние модули вне монорепо (уже открыт в плане) — кит публикуется как test-jar и входит в контракт SPI 6.3? | шаг 4, 6.3 | после ответа на вопрос плана |
