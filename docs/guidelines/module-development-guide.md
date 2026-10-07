# Разработка модулей SmartupCMS

**Версия:** 2.5

**Обновлено:** 2026-10-03

**Основание:** [каноническое ТЗ](../technical-specification.md),
[ADR-0014](../adr/ADR-0014-unified-open-source-runtime.md) и
[структура монорепозитория](../architecture/monorepo-structure.md).

У приложения два исходных корня:

- `apps/server/src/main/java` — Spring Boot modular monolith;
- `apps/web/src/app` — Angular SPA.

Короткий путь «задача → код» — [cookbook](../cookbook/README.md): рецепт на
задачу (справочник, документ со строками и со статусами, связи, хуки, права,
скоуп, импорт, отчёты, `If-Match`, OpenAPI, правка экрана, модуль вне монорепо)
на проверенных эталонных модулях.

Новый runtime или параллельный application root не добавляется без отдельного
принятого ADR. Flyway SQL и runtime-конфигурация остаются ресурсами сервера, а
не третьим приложением.

## Быстрый старт: CLI `cms`

<!-- docs-contract: hypothetical ms.probe -->

Модуль и сущность создаёт CLI `cms` (`tools/cms-cli`, план 10/10, пункт 6.1):
Node.js 22+ без внешних пакетов, одинаково на Windows, Linux и macOS. Запуск из
корня репозитория — `node tools/cms-cli/bin/cms.mjs <команда>`; короче —
`npm link` в `tools/cms-cli`, после чего команда называется `cms`.

```bash
cms doctor                                   # Node, JDK 25, Maven wrapper, git, Docker
cms module new inventory --title "Склад" --title-en Inventory --title-uz Ombor
cms entity new inventory items --title "Товары" --title-en Items --title-uz Tovarlar --icon package --hooks
cms entity add-field inventory.items price --type money --currencies UZS,USD --label "Цена" --label-en Price
cms entity add-field inventory.items stage --type select --options draft,ready --required
cms migration diff                           # что схема не знает из объявлений; --write <имя> — миграцией
```

| Команда | Что пишет |
|---|---|
| `module new <код>` | пакет с `package-info.java`, область права в `PermissionAreas` (код из одного сегмента — сам область, `ms.probe` — именованная область `probe`; ADR-0028), модуль в `ModuleBoundariesTest.MODULES` и владельца префикса таблиц в `ownerOf`, строку в `module-map.md`, манифест `apps/server/src/main/resources/META-INF/smartupcms/modules/<область>.json` ровно с полями ADR-0033, §6.2 (`code` — код модуля в реестре, он же область; `name`, `version` и `minPlatform` — плейсхолдеры версий сборки, зависимость `iam`) |
| `entity new <модуль> <сущность>` | объявление `<Модуль><Сущность>Entity` (поля `name`, `code`, `modifiedAt`, скоуп `all()`, архив, история, выгрузка, виды, массовые действия), при `--hooks` — хуки `…Hooks`, тест контракта `…ContractTest` (наследник кита), миграцию таблицы по ADR-0020 и §14.1 ADR-0032 (ревизия, `attributes` с проверкой, `archived_at`/`archived_by`, частичный уникальный индекс, индекс на каждый FK) и миграцию прав и реестра модулей, ключи ru/uz/en (`nav.<область>_<сущность>`, `<код>.col.*`, `<код>.rights.*`) с `i18n:sync-ru`, иконку пункта меню и модуля в реестре (`--icon`; без него — иконка уже объявленной сущности модуля, иначе `box`), таблицу в списке `SchemaOrderTest.attributesAreObjects`, строку порога покрытия и точку входа в строке модуля карты |
| `entity add-field <код> <поле> --type <тип>` | поле в объявлении (импорт, константа вариантов, ключ в секции `main`), миграцию колонки (обязательное поле обязательно в форме, колонка допускает `null`, чтобы таблица с данными приняла миграцию), ключи подписи и вариантов. Типы: `text`, `textarea`, `markdown`, `email`, `phone`, `url`, `number`, `date`, `datetime`, `time`, `bool`, `select`, `money`, `ref` (`--target <код сущности>`; обязательную ссылку кит не придумает — значение в `fixture(...)`) |
| `migration diff [--write <имя>]` | DDL того, чего схема не знает из объявлений: недостающая таблица — по соглашению §14.1, недостающая колонка — с типом поля и именами ADR-0020; колонку, которой нет в объявлении, не удаляет (удаление пишется руками) |
| `doctor` | проверяет окружение |

Свойства всех команд: миграция берёт следующий свободный номер (выше
наибольшего на диске и в манифесте; `--version V<n>` — свой) и сразу
записывается в `migration-manifest.sha256`; `--dry-run` показывает план и
ничего не пишет; команда сначала планирует все записи и пишет всё или ничего;
файл, который она однажды создала и который потом изменили (рукой или
`add-field`), сохраняется («kept»), повторный запуск ничего не меняет; файл
чужой сущности по тому же пути останавливает команду до первой записи. Шаблоны
и пути репозитория собраны в `tools/cms-cli/lib/templates.mjs` и
`tools/cms-cli/lib/layout.mjs`.

**Как устроен `migration diff`.** Сравнивать разбором исходников ненадёжно:
объявление может брать код и таблицу из констант, миграции — переименовывать и
удалять колонки в блоках `DO`. Поэтому CLI запускает тест
`EntitySchemaDiffTest` (Maven, встроенный PostgreSQL сборки): он берёт
объявления, которые реально исполняет приложение, и схему, которую дали все
миграции. Сравнение одно — `common.entity.EntitySchemaCheck` (ADR-0033, §7),
та же проверка, что останавливает старт (`EntitySchemaGate`) и идёт в сборке
(`EntitySchemaContractTest`); тест CLI добавляет только запись DDL
(`EntitySchemaDiff`). С `-Dcms.schema.diff.out=<файл>` он пишет DDL
недостающих таблиц и колонок в файл, а все расхождения проверки (тип колонки,
`not null` без значения и т. п.) — в `<файл>.problems`; CLI печатает их, их
исправляют руками. Цена — время сборки и старта контекста (около двух
минут); без Maven команда не работает (`--no-libs` пропускает сборку `libs/*`,
если они уже установлены).

**От команды до экрана — замер** (2026-10-03, Windows 11, прогретые `~/.m2` и
кэш Docker, сборка образа веба не повторялась — новой сущности код веба не
нужен):

| Шаг | Время |
|---|---|
| `cms module new`, `cms entity new --hooks`, два `cms entity add-field` (деньги, выбор) | 1 с |
| `docker compose build server` | 1 мин 23 с |
| `docker compose run --rm migrate` на пустой базе | 17 с |
| `docker compose up -d --wait` (postgres, typesense, server, web) | 23 с |
| вход, обязательная смена временного пароля, `/e/inventory.items`: список «Товары» с колонками и форма создания | ≈ 1 мин 30 с |
| **итого до работающего экрана** | **4 мин 1 с** |

Проверка перед коммитом — `scripts/dev/test-cms-cli.ps1` (модуль, сущность,
поля, `migration diff` и сборка с 27 классами тестов, включая кит) — 3,5 мин
на той же машине. Вместе меньше 8 минут при пороге плана 30 минут. Первая
сборка без кэшей дольше: её измеряет пункт 6.5. В CI тот же прогон —
`nightly.yml`, задание `cms-cli`, на ubuntu и windows: сгенерированная
сущность проходит Spotless, Error Prone, Checkstyle, архитектурные и
миграционные тесты, кит, сравнение схемы и описание API.

## Серверный модуль

Размещайте новую функцию в существующей предметной области либо создавайте
отдельный предметный пакет с явной границей. Внутри модуля роли разделяются так:

1. **Controller** разбирает HTTP-контракт, валидирует вход, требует серверную
   авторизацию и делегирует use case. Он не содержит транзакционной бизнес-логики
   и прямого SQL.
2. **Application service** владеет use case, бизнес-инвариантами и границей
   транзакции. Он координирует репозитории, provider interfaces и события.
3. **Repository или adapter** владеет I/O: SQL, внешним HTTP, объектным
   хранилищем, mail, SMS или messenger. Он не принимает решение о праве
   пользователя на бизнес-операцию.
4. **DTO и domain records** не раскрывают секреты и не протаскивают
   provider-specific типы через публичную границу модуля.

Cross-module интеграция выполняется через явный интерфейс или событие. Соседний
модуль виден только через его пакеты `service` и `api`; импорт его
`repository`, `controller` и любых других пакетов запрещён
(`ModuleBoundariesTest`: старые нарушения заморожены и только убывают).
Контроллер не видит пакет `repository` даже своего модуля. Общие value/error contracts помещаются в `libs/core-types`, общая
backend-инфраструктура — в `libs/platform-common`, а интерфейсы внешних
провайдеров — в `libs/provider-spi`. Общая библиотека не зависит от сервера.
Объявление сущности, хуки, правила, действия и события — публичный API
платформы `libs/platform-api` (`com.smartup24.cms.platform.api..`,
[ADR-0033](../adr/ADR-0033-platform-api-and-module-manifest.md)): объявление и
хуки модуля импортируют их оттуда, а не из `com.smartup24.cms.instance.common`.

Любой защищённый endpoint использует `@RequiresPermission`; UI-проверка лишь
улучшает UX и не заменяет серверную авторизацию. Код формы —
`<область модуля>.<сущность>` ([ADR-0028](../adr/ADR-0028-permission-codes.md)):
область — код модуля или именованная область из `PermissionAreas`
(`tasks` → `ms.task`); форма другого модуля — только опубликованная вашему
модулю. Новая область или публикация — правка `PermissionAreas` и ADR-0028. Ошибки возвращаются в общем
Problem Details формате ([ADR-0021](../adr/ADR-0021-error-model.md),
[как ведёт себя API](../api/README.md)).

## Серверные правила

Правила фазы 3 плана 10/10 проверяются сборкой; CLI `cms` им следует.

| Правило | Как | Основание и проверка |
|---|---|---|
| Ошибка | `ApiException` с `ErrorCode`, ключом `error.<модуль>.<имя>` и параметрами: `ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found")`; ключ — в каталогах ru, uz и en (`apps/server/src/main/resources/i18n`); предложений в коде нет | [ADR-0021](../adr/ADR-0021-error-model.md); `ErrorTextsTest`, `ErrorModelTest` |
| DTO | запросы и ответы — records в пакете `api` модуля; контроллер не видит `repository` | [ADR-0022](../adr/ADR-0022-openapi-from-code.md); `ModuleBoundariesTest` |
| Описание API | springdoc строит его по контроллерам; после изменения контроллера или DTO — `mvn -B test -pl apps/server -Dtest=OpenApiContractTest -Dopenapi.update=true`, затем в `apps/web` — `npm run api:types` | [ADR-0022](../adr/ADR-0022-openapi-from-code.md); `OpenApiContractTest`, `scripts/api/test-api-contract.ps1` |
| Статусы | создание — `201` и `Location` (`Created.at(...)`) с `@ResponseStatus(HttpStatus.CREATED)`; удаление — `204` с `@ResponseStatus(HttpStatus.NO_CONTENT)`; переключатель принимает состояние (`PUT …/archived {archived}`) | [ADR-0023](../adr/ADR-0023-uniform-rest.md); `ResponseStatusDeclaredTest`, Spectral |
| Блокировка | таблица с `revision`; ответ — record, реализующий `Revisioned` (заголовок `ETag`); `PUT`/`PATCH` принимает `@RequestHeader(Revisions.IF_MATCH)` и передаёт `Revisions.required(ifMatch)`; репозиторий пишет `where id = :id and revision = :expected` и на пустой результат бросает `Revisions.conflict()`: без ревизии — 428, устаревшая — 409 | [ADR-0024](../adr/ADR-0024-optimistic-locking.md); `ChangesNameTheirRevisionTest` |
| Страницы | растущая коллекция — `KeysetPage`: список реестра полей (`QueryList`, ADR-0016) или `TimePage` вне реестра; `limit` выше максимума — 422; для таблицы без предела — `QueryList.withEstimatedTotal()` (`totalExact: false`) | план 10/10, пункт 3.5; `CollectionsArePagedTest` |
| JSON-колонки | `JsonColumns` (`common.json`) с общим `ObjectMapper`; своих `toJson`/`parseJson` нет | план 10/10, пункт 3.11; Checkstyle |
| Чужие данные | репозиторий читает другой модуль только через его опубликованное представление `<префикс>_pub_*` (колонки без секретов, только чтение) или его сервис; запись — только через сервис владельца; своё представление модуль добавляет миграцией | [ADR-0026](../adr/ADR-0026-published-read-views.md); `ModuleBoundariesTest`, `PublishedReadViewsTest` |
| Журналы | таблица, которая растёт с каждым событием, объявляет бин `RetentionPolicy` (имя, таблица, условие с `:cutoff`, срок по умолчанию); срок — `smc.retention.days.<имя>` | [ADR-0025](../adr/ADR-0025-retention-and-cluster-cache.md); `RetentionJobIntegrationTest` |
| Кэш | имя кэша регистрируется в `CacheConfig`; очистка доходит до всех узлов после коммита | [ADR-0025](../adr/ADR-0025-retention-and-cluster-cache.md) |
| Размер | класс до 400 строк, метод до 60 | план 10/10, пункт 3.10; Checkstyle |
| Комментарии | по-английски; ссылки — на `ADR-NNNN`, `FR-…`/`NFR-…`, «plan 10/10, item N» | [CODE_STYLE](../../CODE_STYLE.md), §2.1.2; `CommentLanguageTest` |
| Миграции | правила именования и типов с V128; DDL и данные в разных файлах; новый файл — в манифест: `mvn -B test -pl apps/server -Dtest=MigrationManifestTest -Dmigrations.manifest.append=true` | [ADR-0020](../adr/ADR-0020-database-naming.md), [руководство](database-migrations.md); `MigrationLintTest`, `MigrationFileRulesTest`, `MigrationManifestTest` |

## Сущность как объявление: чек-лист

Запись, которую пользователь создаёт, ищет и меняет, — это **объявление и
хуки** ([ADR-0019](../adr/ADR-0019-low-code-entity-model.md),
[ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §6; план 10/10, пункт
5.4). REST, проверку, скоуп, ревизию, аудит, события, историю, выгрузку и
массовые действия строит платформа: общий runtime `/api/v1/entities/{код}`
обслуживает каждую сущность с таблицей. Контроллер, сервис и репозиторий
CRUD модуль **не пишет** — серверная часть сущности ≤ 2 файлов. Каркас даёт
CLI (раздел «Быстрый старт: CLI `cms`»): `cms module new <код>`, затем
`cms entity new <модуль> <сущность> [--hooks]` — две миграции (таблица и
данные), объявление (`<Модуль><Сущность>Entity`), хуки (`…Hooks`), ключи меню,
подписей и прав в каталогах ru/uz/en, область права в `PermissionAreas` и тест
контракта сущности (наследник `EntityContractTestKit`); `cms entity add-field`
добавляет поле. В конце команда печатает, что осталось сделать руками.
`scripts/dev/test-cms-cli.ps1` (и тот же `tools/cms-cli/scripts/smoke.mjs` в CI
на Linux и Windows) проверяет, что результат CLI — два серверных файла,
собирается и проходит гейты.
Эталоны cookbook (модуль `example`, план 10/10, пункт 6.6): справочник
`ExampleProductsEntity`, документ со строками `ExampleOrderEntity`, документ со
статусами `ExampleRequestsEntity` с хуками `ExampleRequestsHooks`. Образец в
коде — заметки (`ms.note`: один файл `MsNoteEntity`, хуки им не нужны). Образец модуля с хуками, своим скоупом и действиями записи — задачи
(`ms.task`, план 10/10, пункт 5.6): справочники `MsTaskTypeEntity` и
`MsTaskStatusEntity`, проекты `MsProjectEntity` (скоуп `custom`, участники
действиями `add_member`/`remove_member`) и задачи `MsTaskEntity` (статус —
`ENUM` и действие `set_status`, исполнители и наблюдатели — `MULTI_REF` в
одной таблице участников).

1. **Миграция:** таблица со стандартными полями (`id`, `attributes jsonb`,
   `created_by`/`modified_by`/`created_at`/`modified_at`, `revision`) по
   [ADR-0020](../adr/ADR-0020-database-naming.md) и ADR-0032 §14.1;
   отдельным файлом — права в каталоге, выдача системным ролям, модуль в
   `md_installed_modules`; оба файла — в манифест миграций. Колонка
   необязательного поля допускает `null`: `null` в теле очищает значение.
2. **Объявление** (`<Prefix><Name>Entity`): `Entity.define(код, форма)` —
   таблица и псевдоним, **обязательный скоуп** (`.scope(EntityScope.owner(...)
   | orgUnit(...) | all() | custom(...))`, без него объявление не собирается;
   ADR-0032 §5.1), **каждое поле один раз** как `EntityField` (§3) с правами
   на поле при нужде (`.requires(форма, действие)`,
   `.readonlyUnless(форма, действие)`), секции, действия с правом каждое,
   ключи названий права для матрицы (`EntityRights`, `<код>.rights.*` по
   ADR-0031), пункт меню (`EntityMenu`, его `module` выключает и сущность),
   сортировка списка по умолчанию, возможности (`HISTORY` — аудит под
   таблицей сущности, `EXPORT`, `SAVED_VIEWS`, `BULK` — массовые `delete`,
   `archive`, `update` (поля из `params`) и любое объявленное действие записи
   (`params` — его параметры), каждое по записи со своими проверками и хуками;
   нужно хотя бы одно действие над записью, `SEARCH` через
   `.search(EntitySearchSpec.title("поле").body("поле", ...))` — глобальный
   поиск по текстовым полям списка без права на поле, у скоупа `custom` с
   `.scopeUsers(sql участников)`, по желанию `.route("/экран/{id}")` и
   `.when(sql)`; индекс слышит `EntityChanged` сам, находку перепроверяет
   скоуп сущности в БД (ADR-0032, §10.3),
   `archivable()` — `ARCHIVE` с колонками `archived_at`/`archived_by` и
   частичными уникальными индексами `where archived_at is null`,
   `customFields(тип)`, `importKey(ключ)` — `IMPORT`, см. «Импорт из файла») и кросс-полевые правила (`.rule("period",
   Rules.notBefore("endsOn", "startsOn"))` — даты или моменты времени). Поле:

   ```java
   .field(text("title", "notes.col.title").column("title").required().length(1, 255)
           .list(sortable().searchable()))
   .field(select("color", "notes.col.color", COLORS, "notes.color_").column("color")
           .required().defaultValue(FieldDefault.fixed("default")))
   .field(ref("noteId", "orders.col.note").column("note_id").target("ms.notes", "title"))
   .field(instant("modifiedAt", "notes.col.modified_at").system(SystemColumn.MODIFIED_AT).list(sortable()))
   ```

   Поле формы и поле списка выводятся из этого объявления: список сущности
   (`query-meta/<код>`) строит `EntityLists`, отдельный бин `QueryList` для
   сущности не пишется (`EntityFieldsSingleSourceTest`). Ссылка называет цель
   **кодом сущности** (`.target(код, поле подписи)`): новое значение — строка
   цели, которую автор видит в её скоупе (иначе 422 `not_found`), и не
   архивная (иначе 422 `archived`).

   Типы ERP/SFA (план 10/10, пункт 5.2; ADR-0032, §4): `email`, `phone`,
   `url`, `money(..., валюты).money(колонка суммы, колонка валюты)`,
   `enumeration(..., код справочника)` — справочник объявляется
   `.reference("code", "name")` (список справочника кэшируется и сбрасывается
   при каждом изменении его записи; справочник читается целиком любым
   зрителем, поэтому его скоуп — только `EntityScope.all()`, иное объявление
   отклоняется), `multiRef(...).link(таблица, владелец,
   цель)` или `.link(таблица, владелец, цель, колонка вида, вид)` — несколько
   списков в одной таблице связи, каждый со своим видом строки, `file`/`image` — колонка `uuid` с FK на `mf_files`, `json(...,
   корень)`, вычисляемое `computed(sql)` любого скалярного типа. Признаки
   формы: `readonly()`, `readonlyOnUpdate()`, `readonlyWhen(условие)`,
   `defaultValue(FieldDefault.fixed/now/today/currentUser/currentOrgUnit/sequence)`,
   `visibleWhen(FieldCondition.eq(...))`. Все типы пишет runtime: колонки
   денег, строки таблицы связи, прикрепления файлов. Ключ поля не называет
   секрет (ADR-0029).
3. **Хуки** (`<Prefix><Name>Hooks implements EntityHooks`, `@Component`) — то,
   что объявление сказать не может (ADR-0032, §6.5): `beforeSave` видит уже
   проверенные значения, может изменить записываемое поле
   (`save.values().set(...)`) и добавить ошибку (`save.reject(...)` — один 422);
   `beforeArchive` может отказать в архиве или восстановлении (`ApiException`);
   `afterSave`/`afterDelete` — в той же транзакции; `afterCommit` — после
   коммита (сбой пишется в журнал и не меняет ответ; то, что нельзя повторить,
   ставится в очередь или outbox). Данные своего модуля хук пишет через свой
   репозиторий, чужие — через сервис другого модуля, SQL в хуке нет. Действие
   записи сверх `create`/`update`/`archive`/`delete` —
   `EntityActionHandler` (`POST …/{id}/actions/{код}` с `If-Match`).
4. **Что делает runtime без кода модуля** (порядок §6.3): право и `If-Match`
   до транзакции; запись со скоупом `for update` (вне скоупа — 404, как
   несуществующая); тело по полям (неизвестное свойство, системное — `id`,
   `revision`, `createdBy`…, неверный тип JSON — 422); значения по умолчанию,
   readonly, правила полей, ссылки, справочники, файлы, правила `EntityRule`
   и доп. поля — одним 422; хуки; запись с ревизией + 1; аудит всех полей;
   `EntityChanged` в транзакции (вебхук `<форма>.updated` пишется в outbox
   без кода модуля); `afterCommit`. С `Idempotency-Key` всё это — в
   транзакции фильтра идемпотентности.
5. Отдельный пункт «список реестра» для сущности не нужен: список, который
   не является сущностью (журнал, отчёт), остаётся бином `QueryList`
   (ADR-0016). **Отчёты и виджеты — тоже не пишутся** (ADR-0032, §10.2):
   у каждого списка сущности есть вкладка «Отчёт» — группировка по полям
   выбора, `ENUM`, да/нет, ссылкам и датам, меры по числам и деньгам; отчёт
   сохраняется видом списка (`report` или `widget` — на панели аналитики).
   Чтобы поле можно было группировать или суммировать, ему достаточно быть в
   списке (`.list(...)`) с подходящим типом; права на поле (`requires`) и
   скоуп сущности отчёт соблюдает сам.
6. **Переводы** в `apps/server/src/main/resources/i18n` (ru, uz, en):
   `nav.<код>`, подписи полей и вариантов, ключи ошибок хуков; затем
   `npm run i18n:sync-ru`. Ключ экрана — `<модуль>.<экран>.<элемент>` по-английски
   в snake_case, без транслита и хэшей
   ([ADR-0031](../adr/ADR-0031-semantic-translation-keys.md)); это проверяет
   `npm run i18n:audit`.
7. **Экран — не пишется.** Пункт меню объявляется без маршрута
   (`new EntityMenu("nav.<код>", "<иконка>", "workspace", <порядок>, "<модуль>")`)
   и ведёт на общий экран `/e/<префикс>.<код>` (ADR-0032, §7.1): список
   (`ui-server-table` по `query-meta`: колонки, сортировка, фильтр, сохранённые
   виды, выгрузка, архив, массовые действия), создание `/new`, карточка `/:id`
   (поля по секциям, история, файлы, кнопки по `actions` записи) и правка
   `/:id/edit` (`smt-entity-form`, `If-Match`, 422 — под полями, 409/428 —
   `SaveErrorNotifier`). Всё — из `form-meta`, `query-meta` и записи runtime;
   права решает сервер. Точечная правка (своя ячейка списка, свой контрол
   поля, своя секция карточки, своя вкладка) — `provideEntityOverrides` в
   `apps/web/src/main.ts` (раздел «Экран сущности» ниже). Свой экран — только
   для **другого способа работы** (доска, календарь, карта, мастер из шагов,
   экран нескольких сущностей, ADR-0032 §7.2): тогда пункт меню называет свой
   маршрут (`new EntityMenu("/<путь>", …)`), а экран берёт данные из
   `/api/v1/entities/<код>` (`EntitiesApi`) и части `smt-entity-*`.
8. **Проверки:** `EntityActionPermissionContractTest` — у каждой пары
   объявления (`view`, право каждого действия) есть название в `EntityRights`
   и она попадает в каталог прав (`MdFormCatalogSynchronizer` берёт пары
   объявлений, ADR-0032 §6.10), форма принадлежит модулю `EntityRights`
   (`cms module new` записывает область модуля в
   `PermissionAreas`); `PermissionCodesTest` — код формы по ADR-0028;
   `MdFormCatalogTest` — у каждой пары права есть название; объявление без
   того, что обещают его возможности, хуки и обработчики необъявленной
   сущности, действие без обработчика не дают приложению стартовать.
   **Контракт сущности** — один наследник `EntityContractTestKit` (раздел
   ниже); без него `EntityContractCoverageTest` валит сборку. Хуки и правила
   модуля — отдельными тестами. Новый модуль получает строку порога покрытия
   в `apps/server/coverage-floors.csv` (без неё
   `scripts/quality/test-coverage-floors.ps1` падает) и своё имя в
   `ModuleBoundariesTest.MODULES` с префиксом таблиц в `ownerOf`, чтобы
   границы проверялись и для него.

Не нужно: контроллер, сервис и репозиторий CRUD, записи в `MdFormCatalog`,
`@RequiresPermission` на CRUD, свой аудит, вызов `WebhookService`, свой
источник истории, свой экспортёр, свой endpoint массовых действий, бин
`EntityRecords` (он только для сущности без таблицы), пункт меню в
`app-shell.models.ts`, маршрут в `app.routes.ts` и файлы экрана в
`apps/web/src/app/features`. Описание API строится из объявления
(`EntityOpenApiCustomizer`: пути `/api/v1/entities/<код>…`, схемы
`<Код>Record`/`Create`/`Patch`/`Page`) — после нового поля перегенерируйте
`docs/api/openapi.json` и типы веба.

### Документ: строки и статусы

Документ — та же сущность ([ADR-0032](../adr/ADR-0032-low-code-platform-v2.md),
§9; план 10/10, пункт 5.7): объявление получает коллекции строк, процесс и
вкладки карточки, своего экрана и кода веба не нужно. Образцы — модуль
`example`: заказы `example.orders` одним файлом `ExampleOrderEntity` (строки и
проведение) и заявки `example.requests` (`ExampleRequestsEntity`,
`ExampleRequestsHooks`: процесс с отзывом и решением, право на поле); рецепты —
[документ со строками](../cookbook/document-lines.md),
[документ со статусами](../cookbook/document-statuses.md).

1. **Миграция строк:** дочерняя таблица `id bigint generated always as
   identity`, `<родитель>_id bigint not null references <родитель> on delete
   cascade` с индексом, `position integer not null`, колонки полей строки; без
   своей ревизии, авторов и аудита.
2. **Коллекция:** `.collection(EntityCollection.of("lines", подпись).table(...)
   .parentColumn(...).positionColumn("position").field(...).maxRows(500).build())`.
   Поле строки — колонка, деньги в валюте документа
   (`money(...).money("price", null).currencyFrom("currency")`) или вычисляемое
   (`computed("round(l.qty * l.price, 2)")`); итог документа — вычисляемые деньги
   родителя с `currencyFrom`.
3. **Процесс:** поле статуса — `select(...).column("status").readonly()
   .defaultValue(FieldDefault.fixed("draft"))`, затем
   `.workflow(EntityWorkflow.on("status").state(...).initial()...)`; права
   переходов — в `EntityRights` и в миграции прав (`md_form_actions`); что
   блокирует состояние — `.locks(...)`, правила перехода — `.rule(...)`,
   вопрос — `.confirm(ключ)`. Проведение с записью своих данных — хук
   `afterSave` с `save.operation() == ACTION` и `save.action()` перехода.
4. **Вкладки карточки:** `.tab(EntityTab.sections(...))`,
   `.tab(EntityTab.collection(...))`, `.tab(EntityTab.related(...))`,
   `.tab(EntityTab.history(...))`; без вкладок карточка — «Поля» и «История».
5. **Ключи:** подписи строк, состояний, кнопок переходов
   (`entity.action.<код>`) и вопросов — в ru/uz/en.
6. **Тест-кит:** фикстура даёт строки (`EntityFixture.valid(Map.of("lines",
   List.of(...)))`); группа «коллекции и процесс» проверяет строки и каждый
   переход сама.

### Импорт из файла

Сущность получает импорт из xlsx одной строкой объявления
([ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §10.1; план 10/10,
пункт 5.8): общий список показывает кнопку «Импорт», если зритель держит право.

1. **Ключ:** `.importKey("code")` — текстовое поле формы в своей колонке, по
   которому строка файла находит запись (найдена в скоупе — изменение, иначе
   создание). На колонку нужен уникальный индекс (у архивной сущности можно
   частичный `where archived_at is null`) — его требует
   `EntityImportDeclaredTest`. Ключ, который даёт сервер (номер из
   последовательности), только для чтения: строка находит запись по нему, строка
   без него создаёт запись со своим номером.
2. **Право:** действие `import` формы — в `EntityRights` (`<код>.rights.import`)
   и в миграции прав (`md_form_actions`, выдача ролям); строка требует ещё
   `create` или `update`.
3. **Хуки:** импортированная строка — то же `CREATE` или `UPDATE`, хук узнаёт
   её по `save.imported()`; аудит пишет источник `import`.
4. **Колонки шаблона** — записываемые поля формы, которые зритель видит и может
   менять (без файлов, JSON, полей «только для чтения»); строки документа и доп.
   поля администратора в шаблон не входят.
5. **Тест-кит:** группа «import» появляется сама; в фикстуре ничего не нужно.

### Контракт сущности: тест-кит

Каждая сущность с таблицей проходит общий контракт
([ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §11; план 10/10, пункт
6.2). Автор пишет один класс в тестах модуля; `cms entity new` создаёт его сам
(`<Prefix><Name>ContractTest`):

```java
class MsNoteContractTest extends EntityContractTestKit {
    @Override protected String entity() { return MsNoteEntity.CODE; }
}
```

Кит (`apps/server/src/test/java/.../support/entity`) выводит случаи из
объявления и гоняет их по всему приложению на встроенном PostgreSQL сборки
через runtime `/api/v1/entities/{code}`: метаданные, CRUD (201 + `Location` +
`ETag`, чтение как записано, список, повтор с тем же `Idempotency-Key`),
ревизия (428/409), архив, права (без `view` — 404 на всех путях, с одним
`view` изменения — 403, `actions` в `form-meta` и в записи по правам), скоуп
(чужая запись — 404, тот же ответ, что у несуществующего id, на каждом пути
по id; её нет в списке, массовом действии и выгрузке), права на поля,
проверка каждого правила каждого поля (422 с адресом и кодом), строгое тело
(неизвестное и системное свойство — `unknown_field`, неверный тип JSON —
`invalid`), аудит (все объявленные поля в истории с подписями), выгрузка
(строки списка зрителя, только его колонки), события (строка `kwh_outbox`
на изменение, без полей с правом; отказ — ни строки) и импорт у сущности с
`IMPORT` (шаблон по правам, проверка без записи, upsert по ключу, ошибки
`rows[n].поле`, запись вне скоупа не меняется). Пользователей, роли,
оргединицы и сессии кит создаёт сам (`TestUsers`, `TestSession`).

Что переопределяет автор:

| Метод | Когда |
|---|---|
| `entity()` | всегда: код сущности |
| `transport()` | только у сущности со своим контроллером (`EntityTransport.module(путь)`); по умолчанию — runtime `/api/v1/entities/{code}` |
| `fixture(ctx)` | значения, которые кит не придумает: ссылка, файл, элемент справочника, текст по шаблону, — `EntityFixture.valid(Map.of(...))`; своё изменение — `.update(...)`; свои недопустимые значения — `.invalid(поле, значение, код)`. Право `view` на сущность, которую называет ссылка, кит выдаёт своим пользователям сам |

Падение случая называет группу и правило («скоуп: … read is 404»). Чинится
код модуля, а не кит: обойти случай нельзя, а `EntityContractCoverageTest`
требует ровно один наследник на сущность.

Веб-экраны на `smt-entity-form` проверяются помощниками
`apps/web/src/testing/entity-form.ts`: `formMetaFixture(code, fields)` —
`form-meta` из полей, `renderEntityForm(meta, values, problems)` — форма с
хостом, `EntityFormHarness` — `fill(ключ, значение)` по типу поля, `problem(ключ)`,
`readonly(ключ)`, `keys()`; для формы в диалоге корнем служит
`inScreen(fixture.nativeElement)`.

Общий экран `/e/:code` проверяется помощниками
`apps/web/src/testing/entity-page.ts`: `renderEntityScreen(url, {meta, list,
records, …})` рисует страницу настоящим роутером поверх API, который отвечает
только из фикстур (`queryMetaFixture`, `entityRecord`, `problem`); так спека
показывает, что сущность, о которой веб ничего не знает, получает список,
форму и карточку (`shared/entity/page/entity-page.spec.ts`).

### Модуль вне монорепо

Сторонний модуль — отдельный jar, собранный против публичного API
([ADR-0033](../adr/ADR-0033-platform-api-and-module-manifest.md)); образец —
`examples/external-module` (модуль `library`, сущность `library.books`).

1. Зависимости: `com.smartup24.cms:platform-api` (`provided`),
   `org.springframework:spring-context` (`provided`, для `@Configuration` и
   `@Bean`), в тестах — `com.smartup24.cms:platform-testkit` с
   `<type>pom</type>`. Ничего из `com.smartup24.cms.instance..` модуль не
   импортирует (`LibraryModuleBoundaryTest`).
2. Манифест `META-INF/smartupcms/modules/<код>.json`: `code`, `name`,
   `version`, `minPlatform` (наименьшая версия API, на которой модуль
   работает), `dependencies`, `configuration` (класс `@Configuration` с бинами
   `EntityDefinition`, `EntityHooks`, `EntityActionHandler`), `migrations`
   (`db/modules/<код>`, свои номера `V`, история `flyway_module_<код>`),
   `messages` (каталог `ru.json`, `uz.json`, `en.json` с ключами модуля),
   `areas` (области прав, которые модуль держит кроме своего кода; обычно не
   нужно: код модуля и есть его область, ADR-0028).
   Неизвестное поле, модуль для более новой или другой MAJOR-версии API и
   отсутствующая зависимость не дают платформе стартовать — с сообщением,
   которое называет модуль и версии. Сущность модуля принадлежит модулю по
   области своей формы (`<код>.<сущность>`): выключенный модуль закрывает её
   на всех путях (404), а сущность, чью область не держит ни один модуль,
   останавливает старт.
3. Таблицы модуля — по соглашению ADR-0032 §14.1: `id`, `revision`,
   `attributes`, `created_*`, `modified_*`, у архива `archived_at`/`archived_by`.
   Платформа при старте сравнивает объявление с `information_schema`
   (`EntitySchemaGate`): расхождение — отказ старта со списком строк.
4. Отказ хука целиком — `EntityRefusal.conflict(ключ, параметры)` (403, 409,
   422); проблема поля — `save.reject(...)`. Ключи текстов — в `messages`
   модуля; ключ, который уже есть у платформы, — отказ старта.
5. Контракт: один наследник `EntityContractTestKit` на сущность, как у
   встроенного модуля; кит стартует платформу с jar модуля на classpath.
6. Подключение: jar на classpath сервера; строка реестра модулей появляется
   при первом старте (включён, не системный), версия в реестре — из манифеста.
   Доставка jar в образ Docker — открытый вопрос (ADR-0033, §11, В2).

Встроенный модуль, который заводит строку реестра миграцией, кладёт манифест в
`apps/server/src/main/resources/META-INF/smartupcms/modules/<код>.json`
(версии подставляет сборка: `${project.version}`, `${platform-api.version}`);
генератор пишет его сам.

### Экран сущности: общий экран и точечные правки

Новая сущность получает экран без кода веба: `/e/<код>` (компоненты
`apps/web/src/app/shared/entity/page/`). Если нужна мелочь — своя ячейка
списка, свой контрол поля, своя секция или вкладка карточки, — она задаётся
по ключу один раз — среди провайдеров маршрута общего экрана
(`apps/web/src/app/features/entity-screens.routes.ts`, грузится вместе с
экраном), а не своим экраном:

```ts
provideEntityOverrides('sales.orders', {
  cells: { status: OrderStatusCell },        // входы: row, field
  fields: { color: ColorPickerControl },     // входы: field, value, problem, disabled, set
  sections: { totals: OrderTotalsSection },  // входы: meta, record, values
  // входы: meta, record; requires — вкладка только у зрителя с одним из прав
  tabs: [{ key: 'map', labelKey: 'sales.orders.tab_map', component: OrderMapTab, requires: [{ form: 'sales.orders', action: 'view' }] }],
});
```

Компоненты переопределений — обычные standalone-компоненты с `input()`;
контрол поля меняет значение вызовом `set(значение)`. Сущность без
переопределений — ноль файлов веба. Кнопку действия записи называет ключ
`entity.action.<код>`; если в каталоге есть `entity.action_confirm.<код>`
(текст с `{name}` записи), экран сначала спрашивает подтверждение. Другой экран
ведёт на список с фильтром ссылкой `/e/<код>?filter=<условия DSL>`. Пример
сущности с вкладками и действиями — пользователи (`md.users`:
`MdUserEntity`, `MdUserHooks`, `MdUserActions` на сервере,
`features/iam/users/users.overrides.ts` в вебе; ADR-0032 §8).

### Свой экран сущности: пример

Свой экран оправдан только для другого способа работы (ADR-0032, §7.2). Форма
целиком приходит с сервера; экран только загружает `form-meta`, держит
значения и сохраняет. Эталон своего экрана — доска заметок
`apps/web/src/app/features/notes` (карточки с закреплением; та же сущность
открывается и на общем экране `/e/ms.notes`): новый свой экран копирует его
устройство, а не старые фичи.

| Файл | Что в нём |
|---|---|
| `notes.api.ts` | типизированный сервис данных: модель записи и все запросы фичи; компонент не зовёт `ApiService` сам |
| `notes.component.ts` + `.html` | экран: `rxResource` для формы, метаданных списка и первой страницы; `linkedSignal` для догружаемых страниц; `OnPush`; `@if`/`@for` |
| `note-card.component.ts` | одна запись: `input()`/`output()`, действия по `actions` из `form-meta` |
| `note-form-dialog.component.ts` | создание и правка через `smt-entity-form`; экран создаёт диалог на одно открытие |
| `*.spec.ts` | по спеке на компонент: загрузка, пусто, ошибка, права, сохранение, отказ сервера |

Удаление подтверждается общим `SMTModalService.confirm()` с `action`, а не
своим диалогом. Сокращённо:

```ts
@Injectable({ providedIn: 'root' })
export class InventoryApi {
  private readonly api = inject(ApiService);
  // revision — ревизия загруженной записи: без If-Match сервер ответит 428, устаревшая — 409
  save(id: number | null, payload: Record<string, unknown>, revision?: number): Observable<Item> {
    return id === null
      ? this.api.post<Item>('/entities/ms.inventory', payload, { notifyError: false })
      : this.api.patch<Item>(`/entities/ms.inventory/${id}`, payload, { notifyError: false, ifMatch: revision });
  }
}

@Component({
  selector: 'app-inventory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTEntityFormComponent, SMTButtonComponent, TranslatePipe],
  templateUrl: './inventory.component.html',
})
export class InventoryComponent {
  private readonly items = inject(InventoryApi);
  private readonly i18n = inject(I18nService);
  private readonly formMeta = inject(FormMetaService);
  private readonly form = rxResource({ stream: () => this.formMeta.get('ms.inventory') });
  readonly meta = computed(() => this.form.value() ?? null);
  readonly canCreate = computed(() => canDo(this.meta(), 'create'));
  readonly values = signal<FormValues>({});
  readonly problems = signal<FormProblems>({});

  save(meta: FormMeta): void {
    const t = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    this.problems.set(formProblems(meta, this.values(), t)); // те же правила, что на сервере
    if (Object.keys(this.problems()).length) return;
    this.items.save(null, recordPayload(meta, this.values())).subscribe({
      error: (problem: ProblemDetail) => this.problems.set(serverProblems(meta, problem?.errors, t)), // 422 — на поля
    });
  }
}
```

```html
@if (meta(); as form) {
  <smt-entity-form [meta]="form" [(value)]="values" [problems]="problems()" />
  @if (canCreate()) {
    <button smt-button smtVariant="primary" (click)="save(form)">{{ 'common.save' | t }}</button>
  }
}
```

Кнопки показываются по `actions` из `form-meta`, а не по своим проверкам прав:
сервер уже отфильтровал действия по правам зрителя. Отдельное поле можно
заменить своим шаблоном, не переписывая форму:

```html
<smt-entity-form [meta]="form" [(value)]="values">
  <ng-template smtEntityField="color" let-field let-set="set">
    <my-color-picker (picked)="set($event)" />
  </ng-template>
</smt-entity-form>
```

Полный список точек расширения — в
[extension-points.md](../architecture/extension-points.md).

## Angular feature

Новая пользовательская функция создаётся под `apps/web/src/app/features`.
Маршрут объявляется в `app.routes.ts`, API-вызов идёт через общий HTTP-слой к
серверу, модели ответа типизируются, а состояния loading/empty/error/forbidden
проверяются тестом компонента. Переиспользуемое поведение размещается в `core`,
визуальные примитивы — в `shared`.
Готовые компоненты и правило префиксов (`smt-`, `ui-`, `app-`) перечислены в
[shared/README.md](../../apps/web/src/app/shared/README.md): сначала ищите там.

Браузер не обращается напрямую к PostgreSQL или Typesense и не принимает
окончательное решение об авторизации. Поисковый результат всегда получает
разрешённый пользователю набор через API сервера.

## Изменение данных и внешних интеграций

- Изменение схемы выпускается новой неизменяемой Flyway-миграцией по
  [руководству миграций](database-migrations.md).
- Для storage/mail/SMS/messenger сначала расширяется интерфейс
  `libs/provider-spi`, затем runtime adapter; feature зависит от интерфейса.
- Новый исходящий вызов получает timeout, безопасную конфигурацию, обработку
  ошибки и тест. Секреты не входят в исходный код, логи или тестовые artifacts.
- Изменение, влияющее на требования или release acceptance, связывается с
  соответствующим `FR-*`, `NFR-*` или `AC-*` из канонического ТЗ.

## Проверка перед review

Используйте точные команды из корневого README.

Backend:

```bash
mvn -B verify
```

Web:

```bash
cd apps/web
npm ci
npm run lint
npm run typecheck
npm run api:audit
npm test
npm run build
```

После изменения CLI — `npm test` в `tools/cms-cli` и `scripts/dev/test-cms-cli.ps1`.

End-to-end после запуска Compose из quick start:

```bash
cd e2e
npm ci
npx playwright install chromium
npm test
```

Кроме тестов изменённого модуля выполните относящиеся к изменению архитектурные,
документационные, configuration, release и security gates из
[стратегии тестирования](testing-strategy.md). В pull request перечислите
затронутые требования ТЗ, миграции, проверенные негативные сценарии и команды с
фактическим результатом.
