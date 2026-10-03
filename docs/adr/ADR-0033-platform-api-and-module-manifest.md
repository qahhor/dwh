# ADR-0033: Публичный API платформы, его версия и манифест модуля

**Статус:** Предложено (2026-10-03)
**Дата:** 2026-10-03
**Зависит от:** ADR-0006 (модульный монолит), ADR-0011 (Provider SPI),
ADR-0020 (миграции), ADR-0032 (low-code платформа v2: объявление сущности,
хуки, тест-кит); план 10/10, пункты 6.3 и 6.4
**Частично заменяет:** ADR-0011 §2.3 (сторонний код в JVM), ADR-0006 в части
«подключаемых модулей нет», ADR-0032 §19 В12

---

## 1. Контекст

### 1.1. Решения владельца продукта (2026-10-03)

1. Сторонние модули **вне монорепо планируются** (вопрос фазы 6 плана и
   ADR-0032 §19 В12): автор модуля собирает его отдельно от платформы и
   подключает к готовой установке.
2. Для проверки совместимости разрешён плагин Maven **japicmp** (версия
   закрепляется точно).

### 1.2. Что есть сейчас (2026-10-03, main `01794bf7`)

| Что | Сейчас | Чем мешает стороннему модулю |
|---|---|---|
| Объявление сущности | `common.entity`, `common.entity.field`, `.hook`, `.workflow`, `.collection`, `.event`, `.importing`, `.search` внутри `apps/server`, рядом с runtime, реестром и SQL | модуль может собраться только против всего сервера; где кончается контракт и начинается реализация — не сказано |
| Зависимости объявления | `EntityField.queryField()` возвращает внутренний `QueryField`; `FieldType.listType()` — `QueryFieldType`; `EntityScope.ScopeProvider` — `ScopeFilter` с SQL задач и файлов; `EntityValues.set` вызывает `EntityValidator` (Jackson, `ApiException`); `EntityDefinition` читает `EntityRegistry.CUSTOM_SECTION`; хук отказывает только `ApiException` | контракт тянет за собой реализацию |
| Provider SPI | отдельный артефакт `libs/provider-spi` (`com.smartup24.cms.spi`), версии нет | изменение интерфейса никто не замечает |
| Версия | одна версия приложения `1.0.0-SNAPSHOT` у всех артефактов; номера версии API нет | модуль не может сказать, с какой платформой он работает |
| Реестр модулей | `md_installed_modules.version` — `'1.0.0'`, вписанное миграциями V028/V182 и умолчанием `putModule` | версия не связана с кодом |
| Схема | объявление описывает таблицу, которую создаёт Flyway; расхождение видно только по ошибке SQL на запросе (ADR-0032 §11.4 откладывал `EntitySchemaContractTest` до 6.4) | модуль с несогласованной миграцией стартует и падает позже |
| Тест-кит | `support/entity` в тестах сервера (ADR-0032 §11.6) | вне монорепо его не подключить |

## 2. Решение — обзор

1. Публичный API платформы — **отдельные Maven-артефакты**, от которых
   зависит модуль: `com.smartup24.cms:platform-api` (новый `libs/platform-api`,
   пакеты `com.smartup24.cms.platform.api..`) и `com.smartup24.cms:provider-spi`
   (ADR-0011, пакеты `com.smartup24.cms.spi..`). Всё под
   `com.smartup24.cms.instance..` — реализация, не контракт.
2. У API **своя SemVer-версия**, независимая от версии приложения: сейчас
   `1.0.0`. Каждый публичный тип помечен `@PlatformApi(since, stability)`.
3. **japicmp** в сборке каждого артефакта API сравнивает его с jar последней
   выпущенной версии API и валит сборку при несовместимом изменении без смены
   MAJOR-версии (и при любом изменении без смены версии).
4. Модуль описывает себя **манифестом**
   `META-INF/smartupcms/modules/<code>.json`: код, версия, минимальная версия
   API платформы, зависимости. Платформа проверяет манифесты **до** создания
   бинов и не стартует, если модулю нужна более новая платформа или нет его
   зависимости.
5. При старте объявления сущностей сравниваются с `information_schema`;
   расхождение — приложение не стартует. Тот же сравнитель —
   `EntitySchemaContractTest` в CI.
6. Тест-кит публикуется артефактом `com.smartup24.cms:platform-testkit`;
   пример стороннего модуля `examples/external-module` зависит только от
   `platform-api` и `platform-testkit` и проходит кит.

## 3. Что публично

### 3.1. `platform-api` 1.0

| Пакет | Типы | Стабильность |
|---|---|---|
| `com.smartup24.cms.platform.api` | `PlatformApi`, `Stability`, `PlatformVersion` | STABLE |
| `…api.actor` | `AuditActor` | STABLE |
| `…api.entity` | `Entity` (построитель), `EntityDefinition` и вложенные `EntityRights`, `EntityMenu`, `EntityAction`, `FormSection`; `EntityModel`, `EntityScope` и его варианты, `EntityCapability`, `EntityTab`, `EntityReference`, `FormField` | STABLE; `EntityScope.ScopeProvider` и `EntityScope.Condition` — EXPERIMENTAL (SQL модуля) |
| `…api.entity.field` | `EntityField`, построители `EntityFields`, `FieldSource`, `FieldType`, `FieldAccess`, `FieldCondition`, `FieldDefault`, `FieldOptions`, `FieldParams`, `FieldReadonly`, `FieldRules`, `FieldTypeRules`, `FormFlags`, `FormPart`, `ListPart`, `QueryRef` | STABLE; `AttributeCasts` — EXPERIMENTAL |
| `…api.entity.hook` | `EntityHooks`, `EntitySave`, `EntityDelete`, `EntityArchive`, `EntityCommitted`, `EntityOperation`, `EntityActionHandler`, `EntityActionCall`, `EntityValues`, `EntityRule`, `Rules`, `RuleErrors`, `EntityRefusal` (новый) | STABLE |
| `…api.entity.collection`, `…api.entity.workflow` | `EntityCollection`; `EntityWorkflow`, `EntityState`, `EntityTransition` | STABLE |
| `…api.entity.event` | `EntityChanged`, `EntityEventType` | STABLE |
| `…api.entity.importing`, `…api.entity.search` | `EntityImportSpec`; `EntitySearchSpec` | EXPERIMENTAL |

Правила артефакта:

- зависит только от JDK и `org.jspecify` (аннотации nullness); ни Spring, ни
  Jackson, ни `core-types`;
- каждый публичный тип (и вложенный публичный тип) несёт `@PlatformApi`;
- ничто в `com.smartup24.cms.platform.api..` не зависит от
  `com.smartup24.cms.instance..` — это обеспечивают граф Maven (сервер зависит
  от API, не наоборот) и `PlatformApiContractTest` (ArchUnit) в самом
  артефакте;
- SQL-помощники объявления (`FieldSource.sql`, `EntityField.sql`,
  `AttributeCasts`) остаются в API: это функции объявления, их изменение —
  изменение контракта.

### 3.2. Что вынесено из объявления в реализацию

| Было в объявлении | Стало | Почему |
|---|---|---|
| `EntityField.queryField(s)`, `FieldType.listType()`, `EntityModel.listFields()` | `common.entity.EntityListFields` (сервер) | возвращают внутренние `QueryField`/`QueryFieldType` реестра списков |
| `ScopeFilter` в `EntityScope.ScopeProvider` | `EntityScope.Condition(sql, bindsUserId, userId)`; `ScopeFilter.condition()` / `ScopeFilter.of(Condition)` на сервере | `ScopeFilter` хранит предикаты задач, файлов и проектов |
| `EntityValues.set` → `EntityValidator` | `EntityValues.writable(entity, values, check)`: проверку значения передаёт runtime | валидатор читает JSON через Jackson и бросает `ApiException` |
| `RuleErrors.items()` → `FieldErrorItem` (`core-types`) | `RuleErrors.Problem(field, code, messageKey, params)`; runtime переводит в `FieldErrorItem` | `core-types` — модель ответа сервера, не контракт модуля |
| отказ хука только `ApiException` | `EntityRefusal` (409, 422, 403 + ключ + параметры) или `ApiException` у встроенных модулей | сторонний модуль не видит `ApiException`; `GlobalExceptionHandler` отвечает на `EntityRefusal` так же, `application/problem+json` |
| `EntityRegistry.CUSTOM_SECTION` | `EntityDefinition.CUSTOM_SECTION` | объявление проверяет вкладки без реестра |

### 3.3. Что не публично

- runtime, хранилище, реестр сущностей, `EntityValidator`, `EntityRecords`,
  `EntityFiles`, `EntityAttributes`, `FormFieldExtender`, `EntityImporter` и
  всё остальное под `com.smartup24.cms.instance..` — встроенные модули
  монорепо пользуются ими, сторонние не должны;
- `core-types` (`ErrorCode`, `FieldErrorItem`, `KeysetPage`) — модель REST-ответа;
- REST API — свой контракт (ADR-0022, ADR-0023, `openapi.json`), версия
  `/api/v1` не связана с версией платформенного API.

### 3.4. Тест-кит

`com.smartup24.cms:platform-testkit` (`<type>pom</type>`) — артефакт для тестов
модуля: зависимости от сервера (основной артефакт `server` — обычный jar
классов; запускаемый jar Spring Boot получил классификатор `exec`, его берёт
образ), от jar тестовых помощников сервера (классификатор `testkit`: пакет
`com.smartup24.cms.instance.support..` без собственных тестов кита) и от
тестовых библиотек, которые им нужны. Точки входа автора —
`EntityContractTestKit`, `EntityFixture`, `FixtureContext`,
`EntityTransport` — помечены `@PlatformApi(stability = EXPERIMENTAL)`: кит
версионируется вместе с платформой, japicmp его не сравнивает (это тестовый
код), а изменение его точек входа записывается в журнал SPI.

## 4. Версия API

- Формат — SemVer `MAJOR.MINOR.PATCH`; значение — свойство
  `platform-api.version` корневого `pom.xml` (сейчас `1.0.0`), им версионируются
  `platform-api` и `provider-spi`. Версия приложения (`1.0.0-SNAPSHOT`)
  от неё не зависит.
- `PlatformVersion.current()` читает версию из ресурса
  `META-INF/smartupcms/platform-api.properties` своего jar (Maven
  подставляет её при сборке).
- Несовместимое изменение (бинарное или исходное: удаление, смена сигнатуры,
  новый абстрактный метод интерфейса, который реализует автор) — MAJOR;
  совместимое добавление — MINOR; изменение только реализации платформы —
  версия API не меняется.
- `since` в `@PlatformApi` — `MAJOR.MINOR` версии, в которой тип появился;
  `PlatformApiContractTest` требует `since` не выше текущей версии.
- Каждое изменение API — строка в [журнале SPI](../api/spi-changelog.md) в той же
  ветке.

### 4.1. japicmp

- Плагин `com.github.siom79.japicmp:japicmp-maven-plugin` **0.26.2** в
  `pluginManagement` корневого `pom.xml`, цель `cmp` в фазе `verify` у
  `platform-api` и `provider-spi`: `mvn verify` (CI, задание backend) и
  локальные гейты прогоняют его без отдельной команды.
- Базовая линия — jar последней выпущенной версии API в
  `libs/<артефакт>/baseline/<артефакт>-<версия>.jar`, версия — свойство
  `platform-api.baseline.version`. Репозитория выпусков нет, поэтому базовые
  jar лежат в git (исключение в `.gitignore`); первая базовая линия `1.0.0`
  собрана из этой ветки и совпадает с API на её слиянии.
- `breakBuildBasedOnSemanticVersioning`: несовместимое изменение при той же
  MAJOR-версии, совместимое добавление без смены MINOR и любое изменение без
  смены версии валят сборку.
- Выпуск версии API: поднять `platform-api.version`, записать журнал SPI,
  после слияния обновить базовую линию скриптом
  `scripts/api/update-platform-api-baseline.ps1` (сборка jar и замена файла в
  `baseline/`).
- Что гейт действительно падает, доказывает
  `scripts/api/test-platform-api-compat.ps1` (в CI): во временной копии API
  удаляется публичный метод — сборка падает; та же правка с MAJOR-версией —
  проходит.

## 5. Стабильность и депрекация

| Уровень | Что обещано |
|---|---|
| `STABLE` | меняется несовместимо только в MAJOR-версии и только после того, как элемент был `@Deprecated(since = "X.Y")` хотя бы в одной выпущенной MINOR-версии (≥ 1 минорная версия) |
| `EXPERIMENTAL` | бинарную совместимость тоже проверяет japicmp (несовместимое изменение — только MAJOR), но депрекация не обязательна: элемент можно убрать в следующей MAJOR-версии сразу |

- Депрекацию проверяет `PlatformApiContractTest` (правило `ApiPolicy`, его
  срабатывание доказывает `ApiPolicySelfTest`; для `provider-spi` —
  `ProviderSpiContractTest`): каждый
  публичный STABLE-тип, конструктор, метод или поле базовой линии, которого
  нет в текущем API, в базовой линии был помечен `@Deprecated`.
- Отношение к правилу «установок у клиентов нет» (AGENTS.md, решение
  2026-10-01): оно о REST, настройках и данных установок. Контракт SPI — для
  сторонних модулей, которые владелец продукта разрешил 2026-10-03, поэтому
  политика депрекации действует с базовой линии 1.0.0 (**предположение**,
  вопрос В1 §11).

## 6. Сторонний модуль: сборка и загрузка

### 6.1. Как выглядит модуль

Обычный jar (Java 25), собранный против `platform-api`:

```
com/acme/library/LibraryModule.class          @Configuration: бины EntityDefinition, хуки, действия
META-INF/smartupcms/modules/library.json      манифест
db/modules/library/V1__library_books.sql      миграции модуля (Flyway)
META-INF/smartupcms/modules/library/i18n/{ru,uz,en}.json   ключи модуля
```

### 6.2. Манифест

```json
{
  "code": "library",
  "name": "Библиотека",
  "version": "1.2.0",
  "minPlatform": "1.0.0",
  "dependencies": [{"code": "iam", "version": "1.0.0"}],
  "configuration": "com.acme.library.LibraryModule",
  "migrations": "db/modules/library",
  "messages": "META-INF/smartupcms/modules/library/i18n"
}
```

| Поле | Обязательно | Смысл |
|---|---|---|
| `code` | да | код модуля в реестре (`md_installed_modules.code`), `^[a-z][a-z0-9_]{1,31}$`; совпадает с именем файла |
| `name` | да | название в реестре |
| `version` | да | SemVer модуля |
| `minPlatform` | да | наименьшая версия API платформы, с которой модуль работает |
| `dependencies` | нет | коды модулей и их наименьшие версии |
| `configuration` | у стороннего | класс `@Configuration`, который платформа подключает после проверки; встроенные модули находит сканирование пакета сервера |
| `migrations` | нет | путь миграций модуля на classpath |
| `messages` | нет | каталог ключей модуля (`ru.json`, `uz.json`, `en.json`) |

Неизвестное поле манифеста — отказ (опечатка не должна молча отключить
проверку).

### 6.3. Проверка при старте

`ModuleManifests` читает все `classpath*:META-INF/smartupcms/modules/*.json`
в селекторе импорта конфигурации — **до** создания бинов, поэтому модуль,
который не проходит проверку, не создаёт ни одного бина:

- манифест разбирается по схеме §6.2, код уникален;
- платформа совместима: та же MAJOR-версия и `minPlatform` ≤ текущей версии
  API; иначе — отказ с текстом «module library 1.2.0 needs platform API ≥ 1.3.0
  (major 1); this platform provides 1.0.0»;
- каждая зависимость есть, её MAJOR совпадает и версия не ниже требуемой;
  иначе — отказ, называющий модуль и зависимость;

Отказ — исключение `ModuleManifestException`; `FailureAnalyzer` печатает его
как причину и действие, приложение не стартует. Версия с суффиксом
предварительного выпуска (`1.0.0-SNAPSHOT`) сравнивается как свой выпуск.

### 6.4. Встроенные модули и реестр

- У каждой строки реестра, которую создают миграции платформы (`iam`,
  `tasks`, `files`, `audit`, `search`, `notes`, `upl`, `example`), — манифест в
  ресурсах сервера; версия встроенного модуля — версия приложения (подставляет
  Maven), `minPlatform` — текущая версия API.
- Реестр показывает версию, `minPlatform` и зависимости **из манифеста**;
  колонка `md_installed_modules.version` удаляется (V196), `PUT
  /api/v1/modules/{code}` версию больше не принимает (ломающее изменение API,
  записывается в CHANGELOG; совместимости нет — установок нет). У строки без
  манифеста (модуль, зарегистрированный через API) версии нет (`null`).
- Манифест без строки в реестре (новый сторонний модуль) при старте получает
  строку: включён, не системный.

### 6.5. Загрузка, миграции, ключи

- Загрузка — **jar на classpath сервера** при старте; горячей загрузки,
  отдельного загрузчика классов и изоляции нет. Модуль — доверенный код в JVM
  платформы: ставит его администратор установки, как и саму платформу
  (сознательный отказ от ADR-0011 §2.3 по решению 2026-10-03; риск §10).
- Миграции модуля применяет тот же шаг `migrate` после миграций платформы:
  отдельный Flyway на модуль со своей таблицей истории
  `flyway_module_<code>`, номера `V` модуль ведёт сам. Правила ADR-0020 для
  таблиц модуля — рекомендация, `MigrationLintTest` проверяет только миграции
  платформы.
- Ключи модуля добавляются к встроенным каталогам ru/uz/en; ключ, который уже
  есть у платформы или у другого модуля, — отказ при старте.
- Как доставить jar в образ (том `/app/modules`, `-cp` в `ENTRYPOINT`) —
  следующий шаг эксплуатации, не этого решения (§10).

## 7. Проверка схемы

`EntitySchemaCheck` сравнивает каждое объявление сущности с таблицей
(основная таблица, таблицы коллекций и связей `MULTI_REF`) с
`information_schema` одним запросом:

| Правило | Расхождение |
|---|---|
| таблица есть | `table ex_orders is missing` |
| у каждого поля-колонки, пары колонок денег, системного поля, колонки скоупа есть колонка | `ex_orders.total_currency is missing (field total)` |
| тип колонки совместим с типом поля (таблица ниже) | `ex_orders.qty is text, field qty (number) needs numeric` |
| `revision bigint not null` | у каждой таблицы сущности и строк коллекции нет — у строк коллекции |
| `ARCHIVE` → `archived_at timestamptz`, `archived_by bigint` | |
| скоуп `orgUnit` → внешний ключ колонки единицы на `md_org_units` | |
| колонка `not null` без умолчания пишется: поле обязательно, у него есть умолчание, его пишет runtime (системное, скоуп, связь строки с документом) | `ex_orders.number is not null without a default, field number is optional` |

| Тип поля | Тип колонки |
|---|---|
| `TEXT`, `TEXTAREA`, `MARKDOWN`, `SELECT`, `ENUM`, `EMAIL`, `PHONE`, `URL` | `text`, `character varying`, `character` |
| `NUMBER` | `numeric`, `integer`, `bigint`, `smallint`, `real`, `double precision` |
| `MONEY` | сумма `numeric`, валюта — текст |
| `DATE` / `DATETIME` / `TIME` | `date` / `timestamp with time zone` / `time without time zone` |
| `BOOLEAN` | `boolean` |
| `REF`, `FILE`, `IMAGE`, ключ связи `MULTI_REF` | `bigint`, `integer` |
| `JSON` | `jsonb`, `json` |

- При старте (после проверки версии схемы Flyway) расхождение — приложение
  не стартует с перечнем всех строк. Переключателя «только предупредить» нет:
  несогласованная таблица всё равно ломает запросы runtime, лучше узнать до
  первого запроса.
- `EntitySchemaContractTest` в CI: все объявления сервера против
  мигрированной базы — 0 расхождений; намеренно испорченные таблицы дают
  ровно ожидаемые строки.

## 8. Пример стороннего модуля

`examples/external-module` — модуль реактора Maven, который **не**
наследует ничего от сервера, кроме общих плагинов сборки: зависимости —
`platform-api` (`provided`), `spring-context` (`provided`: `@Configuration` и
`@Bean` конфигурации, которую называет манифест) и `platform-testkit` (test). Модуль `library`
объявляет сущность `library.books` (таблица, скоуп владельца, поля, архив),
миграцию, ключи и манифест; `LibraryBooksContractTest extends
EntityContractTestKit` проходит кит. Пакет модуля — `com.acme.library`;
`LibraryModuleBoundaryTest` проверяет, что исходники модуля импортируют из
платформы только `com.smartup24.cms.platform.api..` (кит — только в тестах).

## 9. Генератор и документация

- Генератор (CLI `cms` пункта 6.1, `tools/cms-cli`) пишет импорты из
  `com.smartup24.cms.platform.api..` и не пишет импорты реализации в
  объявление и хуки; встроенному модулю манифест не нужен, если он не заводит
  строку реестра.
- `extension-points.md` получает раздел «Публичный API», руководство по
  модулю — путь стороннего модуля, карта модулей — `platform-api` и пример.

## 10. Последствия и риски

- Модуль, собранный против `platform-api` 1.x, работает на любой платформе
  1.y ≥ его `minPlatform`; несовместимость видна в сборке платформы (japicmp),
  а не у автора модуля после обновления.
- Каждое изменение публичного типа — решение о версии и строка в журнале SPI;
  это дисциплина и цена (поднять MINOR ради нового метода построителя).
- Риск: сторонний jar — код с полным доступом к JVM и базе. Смягчение —
  ставит только администратор, манифест проверяется, права и скоуп модуля
  проходят те же проверки runtime и кита; изоляция процессом (ADR-0011
  §2.3) остаётся вариантом, если появятся недоверенные поставщики.
- Риск: базовые jar в git — двоичные файлы; их меняет только скрипт выпуска,
  размер — десятки килобайт.
- Риск: схема проверяется при каждом старте — один запрос к
  `information_schema` на старт.
- Ограничение: доставки jar модуля в образ Docker и порядка миграций
  нескольких модулей между собой (кроме «после платформы, по коду») нет.

## 11. Открытые вопросы владельцу продукта

| # | Вопрос | Предложение по умолчанию |
|---|---|---|
| В1 | Политика депрекации SPI действует с базовой линии 1.0.0 (сейчас) или с финального выпуска? | сейчас: правило проверяется сборкой; **принято как предположение** |
| В2 | Доставка jar стороннего модуля в образ: том `/app/modules` и `-cp`, или сборка своего образа поверх платформенного? | свой образ поверх платформенного (`FROM smartupcms/server`, `COPY *.jar /app/modules/`) |
| В3 | Нужна ли подпись jar модуля (проверка издателя при старте)? | нет до появления маркетплейса |

## 12. Рассмотренные альтернативы

| Вариант | Когда подходит | Почему нет |
|---|---|---|
| **A. Отдельный артефакт `platform-api`** (выбрано) | модули вне монорепо | — |
| B. Пакет `platform.api` внутри `apps/server` | все модули в монорепо | модуль зависел бы от всего сервера; граница «API ↔ реализация» только соглашением |
| C. Изоляция процессом (модуль — сервис по HTTP/gRPC, ADR-0011 §2.3) | недоверенные поставщики | общий runtime сущности, транзакции хуков и скоуп в одном SQL не переносятся через процесс |
| D. OSGi / загрузчики классов | горячая установка | сложность и отладка; горячая установка не нужна |
| E. Аннотация `@ModuleManifest` вместо файла | — | манифест нужно прочитать до загрузки классов модуля; файл читается без них |

## 13. Реализация и отступления (2026-10-03)

Сделано в ветке `claude/p6-spi` (план 10/10, пункты 6.3 и 6.4):

| Что | Где | Проверка |
|---|---|---|
| Артефакт `platform-api` 1.0.0 и `provider-spi` 1.0.0, `@PlatformApi` у каждого публичного типа (вложенные включительно) | `libs/platform-api`, `libs/provider-spi` | `PlatformApiContractTest`, `ProviderSpiContractTest` |
| japicmp 0.26.2, семантическое версионирование, базовые линии 1.0.0 | корневой `pom.xml` (`pluginManagement`), `libs/*/baseline` | `mvn verify`; `scripts/api/test-platform-api-compat.ps1` (CI, задание backend) |
| Журнал SPI | `docs/api/spi-changelog.md` | — |
| Манифест, проверка до бинов, отказ с понятной причиной | `common.module` (`ModuleManifest`, `ModuleManifests`, `ModuleCatalog`, `ModuleMigrations`), `config.module` (`ModuleManifestSelector`, `ModuleManifestFailureAnalyzer`) | `ModuleManifestsTest`, `ModuleManifestStartupTest` |
| Манифесты встроенных модулей, версии в реестре, V196 | `apps/server/src/main/resources/META-INF/smartupcms/modules`, `ModuleRegistryService`, `ModuleRegistrySynchronizer` | `ModuleRegistryIntegrationTest` |
| Ключи модуля в каталогах | `MdI18nCatalog` | пример `library` (409 с текстом модуля) |
| Проверка схемы при старте и в CI | `EntitySchemaCheck`, `EntitySchemaGate` | `EntitySchemaContractTest` |
| Тест-кит артефактом и пример модуля | `apps/server` (`testkit`, `exec`), `libs/platform-testkit`, `examples/external-module` | `LibraryBooksContractTest` (49 случаев кита), `LibraryModuleBoundaryTest`, `LibraryBookHooksTest` |

Отступления от разделов 2–9 (решение не меняется, уточняется исполнение):

| № | В дизайне | Сделано | Почему |
|---|---|---|---|
| Р1 | §6.3: `EntityMenu.module` каждой сущности называет модуль с манифестом | не проверяется при старте; `ModuleRegistryIntegrationTest` требует манифест у каждой строки реестра, которую создают миграции | реестр — данные базы, а проверка манифестов идёт до создания бинов, без базы |
| Р2 | §7: правила схемы | добавлены колонки, которые runtime читает всегда (`attributes`, `created_at`); поле `readonly()` над `not null` без умолчания не считается ошибкой (его пишет хук или сервер); колонка `not null` без умолчания, которую не пишет ни поле формы, ни runtime, — ошибка | первый прогон примера модуля упал 500 на отсутствующей `attributes` — проверка должна была отказать старту |
| Р3 | §8: зависимости примера — только API и кит | ещё `spring-context` (`provided`) | конфигурация модуля — `@Configuration`/`@Bean`; свой реестр модуля без Spring в API не вводился |
| Р4 | §6.2: версия jar модуля | у примера jar версии реактора, версия модуля `1.2.0` — в манифесте | родительский `pom.xml` версионирует библиотеки платформы `${project.version}`, своя версия примера ломала разрешение зависимостей |
| Р5 | §3.1: перенос `AuditActor` | перенесён в `platform.api.actor`; внутренние модули импортируют его оттуда | хук получает актора, иначе API зависел бы от `common.actor` |
| Р6 | §11 В2 | доставка jar в образ не сделана; `Dockerfile` копирует `examples` (реактор) и берёт `server-*-exec.jar` | вопрос эксплуатации, открыт |

Для CLI пункта 6.1 (шаблоны генератора): импорты объявления и хуков — из
`com.smartup24.cms.platform.api.entity..`; манифест встроенного модуля —
`apps/server/src/main/resources/META-INF/smartupcms/modules/<код>.json` с
полями §6.2 (`${project.version}` и `${platform-api.version}` подставляет
сборка); строка `md_installed_modules` больше не имеет колонки `version`.
Пункт 6.6 (2026-10-03): `examples/external-module` — эталон рецепта «модуль
вне монорепо» [cookbook](../cookbook/external-module.md); фрагменты манифеста,
объявления и теста в рецепте сверяет контракт документации
(`scripts/docs/test-docs-contract.mjs`). Генератор стороннего модуля
(`--external` у `cms module new`) не сделан: модулю вне монорепо нужен
опубликованный артефакт платформы и свой родительский `pom.xml`, а
репозитория выпусков нет (§4.1) — пока образец копируется. Тест-кит (точки
входа без изменений) выдаёт своим пользователям `view` на цели ссылок и
строже проверяет права на поля (ADR-0032 §11.7, К12–К13).
CLI `cms` следует этому (2026-10-03): `cms module new` пишет манифест
`<область>.json` (код в реестре — область права модуля) ровно с полями §6.2,
без собственных полей CLI; область, префикс таблиц и иконку CLI читает из
`PermissionAreas`, `ModuleBoundariesTest` и объявлений модуля.
`create-module.ps1` удалён.
