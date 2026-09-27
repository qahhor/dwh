# Стандарты разработки и стиль кодовой базы (CODE_STYLE)

**Версия:** 1.0
**Дата:** 2026-08-28
**Основание:** ADR-0001 (логика в приложении), ADR-0002 (Java 25, Spring Boot 4.1, Angular 22), ADR-0006 (модульный монолит), ADR-0008 (безопасность), ADR-0009 (логирование), ADR-0011 (Provider SPI), ADR-0012 (UI)

---

## 1. Архитектурные правила, префиксы модулей и опыт Biruni/Smartup

1. **Преемственность префиксов:** Архитектура и кодовая база платформы наследуют проверенную в Biruni и Smartup систему префиксов:
   - **`md` (Master Data):** `com.smartup24.cms.instance.md` — пользователи, роли, формы, права, настройки.
   - **`kauth` (Kernel Auth):** `com.smartup24.cms.instance.kauth` — аутентификация, сессии, токены, 2FA, сброс паролей.
   - **`ms` (Messaging & Services):** `com.smartup24.cms.instance.ms` — задачи, проекты, комментарии, оповещения, outbox, объявления.
   - **`mf` (Media & Files):** `com.smartup24.cms.instance.mf` — файловое хранилище (Garage S3).
   - **`audit` (Audit & Security):** `com.smartup24.cms.instance.audit` — журнал изменений (JSONB) и security-события.
   - **`common` (Platform):** `com.smartup24.cms.instance.common` — модель сущности (`entity`), реестр полей (`query`), массовые действия, история, провайдеры.
   - **`fnd` (Foundation):** `com.smartup24.cms.instance.fnd` — очередь фоновых заданий, основа загрузок данных.
   - **`upl`, `report`, `search`, `kwh`:** загрузки данных, экспорт списков, поиск, вебхуки.
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
- **Checkstyle** (`config/checkstyle/checkstyle.xml`) проверяет то, что форматтер не видит: имена, импорты, пустые блоки, `equals` без `hashCode`, `System.out` и `printStackTrace` в обход журнала. Исключение — только по месту: `// CHECKSTYLE.OFF: Правило` … `// CHECKSTYLE.ON: Правило` с причиной строкой выше.
- **Error Prone** работает при каждой компиляции: его ошибки роняют сборку, предупреждения видны в выводе и разбираются в фазе 2.
- **Null.** Пакет, помеченный `@NullMarked` в `package-info.java`, проверяет NullAway: всё, что может быть `null`, объявлено `@Nullable` (JSpecify), иначе сборка падает. Сейчас помечен `common`; модуль подключается, когда размечены его пакеты.

### 2.2. Слои приложения и доступ к данным
- **Controller:** Валидация входных данных (`@Valid`), вызов сервиса, маппинг в DTO ответа. Запрещено размещение бизнес-логики. Ошибки транслируются через RFC 9457 `ProblemDetail`.
- **Service / Facade:** Управление транзакциями, исполнение бизнес-правил и инвариантов агрегатов (I-T*, I-P*, I-U*), публикация событий.
- **Repository:** Доступ к данным через **Spring JDBC (`JdbcClient`)** или **jOOQ**.
  - Использование ORM/JPA запрещено для сложных запросов во избежание проблем N+1 и неконтролируемого lazy-loading.
  - Маппинг результатов SQL на Java records выполняется через конструкторы или RowMapper.

---

## 3. Транзакции и Transactional Outbox

1. **Дисциплина `@Transactional`:**
   - Сервисные классы по умолчанию помечаются `@Transactional(readOnly = true)`.
   - Мутирующие методы явно помечаются `@Transactional`.
2. **Строгий запрет сетевого I/O в транзакциях:**
   - Запрещено выполнять внешние HTTP-запросы, отправку писем через SMTP, вызовы Telegram Bot API или обращение к Garage S3 внутри активной транзакции базы данных.
   - Транзакции БД должны быть минимально короткими (миллисекунды).
3. **Паттерн Transactional Outbox:**
   - Для гарантированной доставки нотификаций задача на отправку пишется в таблицу `notification_outbox` **в той же транзакции БД**, где мутирует бизнес-сущность.
   - Фоновый воркер асинхронно вычитывает `notification_outbox` пачками через `SELECT ... FOR UPDATE SKIP LOCKED` и отправляет сообщения.
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
3. **Сквозной `trace_id`:**
   - Каждый входящий HTTP-запрос извлекает или генерирует `trace_id`, помещает его в MDC и пробрасывает в SQL-комментарий: `/*traceparent=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01*/`.

---

## 6. Стандарты Frontend (Angular 22 & TypeScript)

1. **Архитектура компонентов:**
   - Все компоненты используют `ChangeDetectionStrategy.OnPush`.
   - Управление реактивным состоянием строится на **Angular Signals** (`signal()`, `computed()`, `effect()`).
   - Использование тяжелых сторонних сторов (NgRx) запрещено без отдельного ADR.
2. **Дизайн-система и стили (ТЗ-02, ADR-0012):**
   - Прямое использование hex-цветов, произвольных радиусов и отступов в CSS компонентов **запрещено**. Цвета проверяются в CI: stylelint (`color-no-hex`, `color-named`) в файлах `.css`/`.scss` и `npm run contrast:audit` во встроенных стилях компонентов; радиусы и отступы — на ревью.
   - Разрешено использовать исключительно CSS-переменные дизайн-токенов (`var(--ui-color-accent)`, `var(--ui-space-2)`).
   - Все экраны строятся из обёрток `ui-*` (`ui-grid`, `ui-button`, `ui-dialog`, `ui-form-field`).
3. **Синхронизация состояния:**
   - Фильтры, сортировка и параметры пагинации списков обязаны синхронизироваться с Query Params URL.
4. **Типизация:**
   - Режим `strict: true` в `tsconfig.json`. Использование типа `any` запрещено (проверяется eslint).
5. **Линтеры и форматирование (план 10/10, пункт 1.1):**
   - `npm run lint` в `apps/web` запускает ESLint (typescript-eslint, angular-eslint: OnPush, сигнальные input/output/query, `@if`/`@for` вместо `*ngIf`/`*ngFor`, доступность шаблонов), Stylelint и проверку Prettier; CI выполняет то же.
   - Нарушения на 2026-09-28 зафиксированы в `apps/web/eslint-suppressions.json` (bulk suppressions ESLint). Новое нарушение роняет CI; исправленное нужно убрать из файла командой `npm run lint:prune`, иначе CI тоже упадёт. Так число нарушений только уменьшается; фаза 2 плана их устраняет.
   - Код форматирует Prettier (`npm run format`, конфигурация `apps/web/.prettierrc.json`). Разовое переформатирование записано в `.git-blame-ignore-revs`: `git config blame.ignoreRevsFile .git-blame-ignore-revs` возвращает авторство строк.
