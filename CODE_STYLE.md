# Стандарты разработки и стиль кодовой базы (CODE_STYLE)

**Версия:** 1.1
**Дата:** 2026-10-01
**Основание:** ADR-0001 (логика в приложении), ADR-0002 (Java 25, Spring Boot 4.1, Angular 22), ADR-0006 (модульный монолит), ADR-0008 (безопасность), ADR-0009 (логирование), ADR-0011 (Provider SPI), ADR-0012 (UI), ADR-0020–ADR-0025 (схема БД, ошибки, API, блокировка, журналы и кэш)

---

## 1. Архитектурные правила, префиксы модулей и опыт Biruni/Smartup

1. **Преемственность префиксов:** Архитектура и кодовая база платформы наследуют проверенную в Biruni и Smartup систему префиксов:
   - **`md` (Master Data):** `com.smartup24.cms.instance.md` — пользователи, роли, формы, права, настройки.
   - **`kauth` (Kernel Auth):** `com.smartup24.cms.instance.kauth` — аутентификация, сессии, токены, 2FA, сброс паролей.
   - **`ms` (Messaging & Services):** `com.smartup24.cms.instance.ms` — задачи, проекты, комментарии, оповещения, outbox, объявления.
   - **`mf` (Media & Files):** `com.smartup24.cms.instance.mf` — файловое хранилище (Garage S3).
   - **`audit` (Audit & Security):** `com.smartup24.cms.instance.audit` — журнал изменений (JSONB) и security-события.
   - **`common` (Platform):** `com.smartup24.cms.instance.common` — модель сущности (`entity`), реестр полей (`query`), массовые действия, история, провайдеры.
   - **`jobs`, `warehouse`, `units`:** `com.smartup24.cms.instance.{jobs,warehouse,units}` — общая очередь фоновых заданий, вторая база (pg-dwh) с журналом загрузок, единицы измерения (бывший `fnd`, ADR-0030).
   - **`upl`, `report`, `search`, `webhook`:** загрузки данных, экспорт списков, поиск, вебхуки (`webhook` до пункта 4.3 назывался `kwh`, таблицы `kwh_*` прежние). Полный список — [карта модулей](docs/architecture/module-map.md).
2. **Именование классов с модульным префиксом:**
   - **Контроллеры:** `{Prefix}{Entity}Controller` (например, `MdUserController`, `KauthAuthController`, `MsTaskController`, `MfFileController`).
   - **Сервисы и Фасады:** `{Prefix}{Entity}Service` / `{Prefix}{Entity}Facade` (например, `MdUserService`, `KauthSessionService`, `MsTaskService`, `MsNotificationService`).
   - **Репозитории:** `{Prefix}{Entity}Repository` (например, `MdUserRepository`, `KauthSessionRepository`, `MsTaskRepository`).
   - **DTO:** `{Prefix}{Entity}{Action}Request` / `{Prefix}{Entity}Response` (например, `MdUserCreateRequest`, `KauthLoginRequest`, `MsTaskResponse`).
   - **Доменные события:** `{Prefix}{Entity}{Action}Event` (например, `MdUserBlockedEvent`, `MsTaskAssignedEvent`, `KauthSessionRevokedEvent`).
   - **Константы и настройки (`*Pref`):** Системные коды, роли и параметры объявляются в специализированных классах `*Pref` (`MdPref`, `MsTaskPref`, `KauthPref`, `MsNotifyPref`).
3. **Публичные фасады:** Внутренние репозитории, сущности и мапперы помечаются `package-private` и не экспортируются за пределы модуля.
4. **Изоляция данных:** Прямые SQL-запросы к таблицам чужого модуля запрещены. Межмодульное взаимодействие происходит исключительно через методы публичных сервисов либо через публикацию доменных событий.
5. **Связь через события:** Реакция одного модуля на действие в другом строится через публикацию событий:
   - `ms.task` **не вызывает** `ms.notify` напрямую. Задачник публикует событие `MsTaskAssignedEvent`, а сервис нотификаций подписывается на него.
6. **Контроль в CI:** Все правила изоляции модулей, префиксов и запрет циклов валидируются тестами **ArchUnit**. Нарушение ломает сборку.

---

## 2. Стандарты Java 25 и Spring Boot 4.1.x

### 2.1. Идиоматический современный Java
- **Records:** Все DTO, события, Value Objects и проекции запросов объявляются как `record`:
  ```java
  public record CreateTaskRequest(
      @NotNull Long projectId,
      @NotBlank @Size(max = 250) String title,
      @NotNull TaskPriority priority,
      @NotNull Long assigneeId
  ) {}
  ```
- **Pattern Matching & Switch:** Использование pattern matching для проверки типов и запечатанных интерфейсов (`sealed interface`):
  ```java
  public sealed interface DeliveryResult permits DeliveryResult.Success, DeliveryResult.Failure {
      record Success(String messageId, Instant deliveredAt) implements DeliveryResult {}
      record Failure(String errorCode, String errorMessage, boolean retryable) implements DeliveryResult {}
  }
  ```
- **Virtual Threads:** Блокирующий I/O в фоновых задачах и воркерах выполняется на виртуальных потоках Java 25 (`Executors.newVirtualThreadPerTaskExecutor()`).

### 2.1.1. Форматирование и статический анализ (план 10/10, пункт 1.2)
- **Формат** задаёт Spotless с palantir-java-format (4 пробела, 120 колонок): `mvn spotless:apply` форматирует, `mvn verify` и CI проверяют. Разовое переформатирование записано в `.git-blame-ignore-revs`.
- **Checkstyle** (`config/checkstyle/checkstyle.xml`) проверяет то, что форматтер не видит: имена, импорты, пустые блоки, `equals` без `hashCode`, `System.out` и `printStackTrace` в обход журнала. Исключение — только по месту: `// CHECKSTYLE.OFF: Правило` … `// CHECKSTYLE.ON: Правило` с причиной строкой выше. Модуль с `id` (например, один из нескольких `Regexp`: `noSystemOut`, `singleObjectMapper`) выключают только по id: `// CHECKSTYLE.OFF-ID: id` … `// CHECKSTYLE.ON-ID: id` — `CHECKSTYLE.OFF: Regexp` выключил бы все такие модули сразу.
- **Error Prone** работает при каждой компиляции: его ошибки роняют сборку, предупреждения видны в выводе и сборку не роняют; новый код их не добавляет (проверяется на ревью).
- **Null.** Пакет, помеченный `@NullMarked` в `package-info.java`, проверяет NullAway: всё, что может быть `null`, объявлено `@Nullable` (JSpecify), иначе сборка падает. Сейчас помечен `common`; модуль подключается, когда размечены его пакеты.

### 2.1.2. Комментарии в коде (план 10/10, пункт 3.14)
- **Один язык — английский.** Комментарии и Javadoc в коде (Java, TypeScript, SQL, YAML, скрипты) пишутся по-английски. Документы в `docs/`, ADR и сообщения для пользователя остаются на своих языках (ru/uz/en через i18n).
- **Ссылки — только на то, что новичок может открыть:** `ADR-NNNN`, требование `FR-…`/`NFR-…` технического задания, пункт плана («plan 10/10, item N»). Номера пунктов рабочих заданий, черновиков и их дополнений запрещены: вместо ссылки пишется смысл.
- **Проверки.** Что проверяется автоматически, а что — на ревью:
  - `scripts/docs/test-repository-hygiene.ps1` ищет ссылки на рабочие задания в каждом отслеживаемом текстовом файле репозитория (двоичные файлы пропускаются по расширению и по нулевому байту в первых 8 КБ). Исключение — закрытый список из семи выпущенных миграций, замороженных контрольной суммой (`MigrationManifestTest`); новая миграция проверяется, как любой файл.
  - `CommentLanguageTest` считает строки комментариев с кириллицей в Java (`apps/server`, `libs/*`, main и test), в `application*.yml` сервера и в SQL-миграциях (`db/**`). `comment-language-baseline.txt` хранит для каждого старого файла число таких строк: новый файл или рост числа — ошибка; уменьшение тоже ошибка, пока число в списке не понижено (`-Dcomment.baseline.update=true` только понижает и вычёркивает, никогда не повышает и не добавляет). Выпущенные миграции заморожены контрольной суммой (`migration-manifest.sha256`, `MigrationManifestTest`): их русские комментарии перевести нельзя, поэтому это не долг, который может уменьшаться, и тест их не считает. Исключение — только для миграций из манифеста с версией не выше последней выпущенной до правила: V145 в `db/migration`, V003 в `db/dwh`. Миграция с большей версией пишет комментарии по-английски и проверяется, как любой файл, даже после добавления в манифест.
  - `npm run comments:audit` (`apps/web/scripts/comment-language-audit.mjs`, в CI) делает то же для TypeScript в `apps/web/src` со своим списком `apps/web/scripts/comment-language-baseline.txt` (`-- --write-baseline` только понижает).
  - Остальное — HTML-шаблоны, CSS, скрипты PowerShell и shell, прочие YAML (Compose, workflows) и SQL вне миграций — автоматически не проверяется: язык комментариев там смотрят на ревью.

### 2.2. Слои приложения и доступ к данным
- **Controller:** Валидация входных данных (`@Valid`), вызов сервиса, маппинг в DTO ответа. Запрещено размещение бизнес-логики. Ошибки транслируются через RFC 9457 `ProblemDetail`.
- **Service / Facade:** Управление транзакциями, исполнение бизнес-правил и инвариантов агрегатов (I-T*, I-P*, I-U*), публикация событий.
- **Repository:** Доступ к данным через **Spring JDBC (`JdbcClient`)** или **jOOQ**.
  - Использование ORM/JPA запрещено для сложных запросов во избежание проблем N+1 и неконтролируемого lazy-loading.
  - Маппинг результатов SQL на Java records выполняется через конструкторы или RowMapper.
  - JSON-колонки (`jsonb`) пишутся и читаются через `JsonColumns` (`common.json`) с единым `ObjectMapper`
    приложения; своя копия `toJson`/`parseJson` и `new ObjectMapper()` в `src/main` запрещены Checkstyle. Вне
    Spring (каталоги при старте, обработка ошибок) — `JsonMapper.shared()`. Нечитаемый документ — ошибка с
    именем таблицы в логе, а не пустой `{}`, который следующее сохранение запишет поверх настоящего
    (план 10/10, пункт 3.11).

### 2.3. Контракт API, данные и размеры (фаза 3 плана 10/10)
Подробно — [руководство по модулям](docs/guidelines/module-development-guide.md#серверные-правила) и [как ведёт себя API](docs/api/README.md); генератор `scripts/dev/create-module.ps1` уже следует этим правилам.
- **Ошибки** ([ADR-0021](docs/adr/ADR-0021-error-model.md)): только `ApiException` с `ErrorCode`, ключом `error.<модуль>.<имя>` и параметрами; ключ — в каталогах ru, uz и en; предложений в коде нет. Ответ — `application/problem+json` с `code`, `messageKey`, `params`. Проверки: `ErrorModelTest`, `ErrorTextsTest`.
- **API** ([ADR-0022](docs/adr/ADR-0022-openapi-from-code.md), [ADR-0023](docs/adr/ADR-0023-uniform-rest.md)): DTO — records в пакете `api` модуля; описание API генерирует springdoc, копия `docs/api/openapi.json` обновляется `-Dopenapi.update=true`, типы веба — `npm run api:types`. Один путь на операцию, параметры в camelCase, 201 + `Location` / 202 / 204 объявлены `@ResponseStatus`, переключатель принимает состояние. Ломающее изменение — метка `api-breaking` или трейлер `Api-Breaking:` и запись в `CHANGELOG.md`. Проверки: `OpenApiContractTest`, `ResponseStatusDeclaredTest`, `scripts/api/test-api-contract.ps1`, `npm run api:audit`.
- **Страницы** (план 10/10, пункт 3.5): растущая коллекция отдаётся `KeysetPage` — через реестр полей (`QueryList`) или `TimePage`; `limit` выше максимума — 422; таблица без предела — `QueryList.withEstimatedTotal()`. Целиком — только ограниченные справочники из `CollectionsArePagedTest`.
- **Оптимистическая блокировка** ([ADR-0024](docs/adr/ADR-0024-optimistic-locking.md)): запись с `revision`, ответ реализует `Revisioned` (`ETag`), `PUT`/`PATCH` принимает `If-Match` (`Revisions.required`), запись — `update … where revision = :expected`; без ревизии 428, устаревшая — 409. Проверка: `ChangesNameTheirRevisionTest`.
- **Размер** (план 10/10, пункт 3.10): класс до 400 строк, метод до 60 (Checkstyle `FileLength`, `MethodLength`; тесты не ограничены). Больше — делится, а не получает исключение.
- **Кэш и журналы** ([ADR-0025](docs/adr/ADR-0025-retention-and-cluster-cache.md)): имя кэша — в `CacheConfig`, очистка доходит до всех узлов после коммита; журнальная таблица объявляет бин `RetentionPolicy` со сроком по умолчанию (`smc.retention.days.<имя>`).

---

## 3. Транзакции и Transactional Outbox

1. **Дисциплина `@Transactional`:**
   - Сервисные классы по умолчанию помечаются `@Transactional(readOnly = true)`.
   - Мутирующие методы явно помечаются `@Transactional`.
2. **Строгий запрет сетевого I/O в транзакциях:**
   - Запрещено выполнять внешние HTTP-запросы, отправку писем через SMTP, вызовы Telegram Bot API или обращение к Garage S3 внутри активной транзакции базы данных.
   - Транзакции БД должны быть минимально короткими (миллисекунды).
3. **Паттерн Transactional Outbox:**
   - Для гарантированной доставки нотификаций задача на отправку пишется в таблицу `ms_notification_outbox` **в той же транзакции БД**, где мутирует бизнес-сущность (очередь вебхуков — `kwh_outbox`).
   - Фоновый воркер асинхронно вычитывает `ms_notification_outbox` пачками через `SELECT ... FOR UPDATE SKIP LOCKED` и отправляет сообщения.
4. **События `@TransactionalEventListener`:**
   - Слушатели доменных событий, выполняющие побочные эффекты после сохранения данных, обязаны использовать фазу `AFTER_COMMIT`:
   ```java
   @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
   public void onTaskAssigned(TaskAssignedEvent event) {
       notificationService.enqueueTaskNotification(event);
   }
   ```

---

## 4. Безопасность и правила написания SQL

1. **Параметризация SQL:**
   - Любой запрос к PostgreSQL обязан быть строго параметризован (`:paramName` в `JdbcClient` или binding в jOOQ).
   - Запрещена строковая конкатенация пользовательского ввода в тело SQL:
   ```java
   // ЗАПРЕЩЕНО:
   String sql = "SELECT * FROM users WHERE name = '" + userInput + "'";
   
   // ОБЯЗАТЕЛЬНО:
   jdbcClient.sql("SELECT * FROM users WHERE name = :name")
             .param("name", userInput)
             .query(UserRow.class)
             .list();
   ```
2. **Триггеры в БД:**
   - Триггеры разрешены **только для аудита изменений (JSONB) и ограничений целостности**.
   - Запрещено размещать бизнес-логику, вычисления и маршрутизацию в триггерах PostgreSQL.
3. **Хеширование паролей:**
   - Исключительно **Argon2id** (параметры: memory 64MB, iterations 3, parallelism 2).
   - Исходные пароли и токены никогда не сохраняются в открытом виде и не пишутся в лог.

---

## 5. Логирование и защита персональных данных (ПДн)

1. **Структурированный JSON:** Все логи приложения пишутся в `stdout` в формате JSON через Logstash Logback Encoder с обязательными полями:
   `timestamp, level, logger, message, client_code, module, trace_id`.
2. **Запрет ПДн в логах:**
   - В логи **запрещено** выводить: пароли, токены, заголовки `Authorization`, номера телефонов, email-адреса, паспортные данные и полные ФИО.
   - В логах разрешено выводить только системные идентификаторы: `user_id=42, task_id=105, project_id=7`.
   - В Logback настраивается маскирующий фильтр для перехвата случайных утечек секретов.
3. **Ни одна ошибка не проглатывается** (план 10/10, пункт 3.11). `catch` пробрасывает, пишет в лог или
   использует пойманное (возвращает как результат, записывает в статус). Пустой `catch` запрещён Checkstyle
   при любом имени переменной и любом комментарии; `NoSwallowedErrorsTest` следит за остальным. Без следа
   допускается только исход разбора значения: число или дата не разобрались, ключа нет, клиент ушёл — эти
   типы перечислены в тесте. Уровень: `debug` для ожидаемой деградации (зависимость недоступна, временный
   файл не удалён), `warn` — для того, что говорит о дефекте.
4. **Сквозной `trace_id`:**
   - Каждый входящий HTTP-запрос извлекает или генерирует `trace_id`, помещает его в MDC и пробрасывает в SQL-комментарий: `/*traceparent=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01*/`.

---

## 6. Стандарты Frontend (Angular 22 & TypeScript)

1. **Архитектура компонентов:**
   - Все компоненты используют `ChangeDetectionStrategy.OnPush`.
   - Управление реактивным состоянием строится на **Angular Signals** (`signal()`, `computed()`, `effect()`).
   - Использование тяжелых сторонних сторов (NgRx) запрещено без отдельного ADR.
2. **Дизайн-система и стили (AC-02, ADR-0012):**
   - Прямое использование hex-цветов, произвольных радиусов и отступов в CSS компонентов **запрещено**. Цвета проверяются в CI: stylelint (`color-no-hex`, `color-named`) в файлах `.css`/`.scss` и `npm run contrast:audit` во встроенных стилях компонентов; радиусы и отступы — на ревью.
   - Разрешено использовать исключительно CSS-переменные дизайн-токенов (`var(--ui-color-accent)`, `var(--ui-space-2)`).
   - Все экраны строятся из обёрток `ui-*` (`ui-grid`, `ui-button`, `ui-dialog`, `ui-form-field`).
3. **Синхронизация состояния:**
   - Фильтры, сортировка и параметры пагинации списков обязаны синхронизироваться с Query Params URL.
4. **Типизация:**
   - Режим `strict: true` в `tsconfig.json`. Использование типа `any` запрещено (проверяется eslint).
5. **Линтеры и форматирование (план 10/10, пункт 1.1):**
   - `npm run lint` в `apps/web` запускает ESLint (typescript-eslint, angular-eslint: OnPush, сигнальные input/output/query, `@if`/`@for` вместо `*ngIf`/`*ngFor`, доступность шаблонов), Stylelint и проверку Prettier; CI выполняет то же.
   - Файл bulk suppressions ESLint `apps/web/eslint-suppressions.json` пуст (`{}`): нарушений, зафиксированных 2026-09-28, не осталось, и любое нарушение роняет CI. Новую запись в файл не добавляют; если она всё же появилась и исправлена, её убирает `npm run lint:prune`.
   - Код форматирует Prettier (`npm run format`, конфигурация `apps/web/.prettierrc.json`). Разовое переформатирование записано в `.git-blame-ignore-revs`: `git config blame.ignoreRevsFile .git-blame-ignore-revs` возвращает авторство строк.
