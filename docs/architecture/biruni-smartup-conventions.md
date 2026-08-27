# Стандарт архитектурной преемственности: опыт Biruni и Smartup

**Версия:** 1.0
**Дата:** 2026-08-28
**Основание:** ADR-0001 (концепции предметной области Biruni), ADR-0006 (модульный монолит), CODE_STYLE.md
**Назначение:** официальное руководство по переносу опыта, префиксов модулей, структуры пакетов, классов и таблиц из экосистемы Biruni / Smartup в DWH Platform.

---

## 1. Концепция преемственности

Платформа DWH Platform создаётся на современном технологическом стеке (Java 25 LTS, Spring Boot 4.1.x, PostgreSQL 18, Angular 22), однако полностью наследует **проверенную многолетней эксплуатацией модель предметной области, семантику данных и правила именования** систем Biruni и Smartup.

### 1.1. Что мы берём из Biruni и Smartup
1. **Префиксы модулей и таблиц (`md_`, `kauth_`, `ms_`, `mf_`, `audit_`):** мгновенная узнаваемость назначения сущностей и модулей.
2. **Семантика ролевой модели (RBAC):** концепция `(форма, действие)`, материализованные права, неизменяемые системные роли (`pcode`), версионирование прав.
3. **Классификация констант и инвариантов (`*Pref`):** централизованные классы преференсов и системных кодов.
4. **Именование слоёв и сервисов:** публичные фасады модулей, DTO и доменных событий с модульным префиксом.
5. **Сквозной аудит:** фиксация автора, времени, типа операции (`I`, `U`, `D`) и старого/нового состояния строк.

### 1.2. Что мы модернизируем
1. **Логика в приложении:** вместо Oracle PL/SQL бизнес-логика реализуется на типобезопасном Java 25 (Records, Virtual Threads, Spring Boot 4.1).
2. **Прямой REST API и OpenAPI:** вместо динамического шлюза `biruni_routes` используются строго типизированные Spring `@RestController` с аннотацией `@RequiresPermission`.
3. **Физическая изоляция тенантов:** вместо `company_id` в каждом составном ключе используется отдельный экземпляр на клиента (Single-tenant).
4. **Аудит PostgreSQL:** вместо генерации сотен X-таблиц используется единая партиционированная таблица `audit_log` с полями `jsonb`.

---

## 2. Каталог модулей и префиксов

| Префикс | Полное наименование | Ответственность в DWH Platform | Таблицы в БД | Пакет в Java |
|---|---|---|---|---|
| **`md`** | **Master Data & Metadata** | Пользователи, профили, роли, каталог форм и действий, эффективные права, настройки инстанса | `md_users`, `md_roles`, `md_forms`, `md_settings`, `md_instance_info` | `com.greenwhite.dwh.instance.md` |
| **`kauth`** | **Kernel Auth & Security** | Аутентификация, сессии, токены, 2FA/OTP, сброс паролей, привязка каналов устройств | `kauth_sessions`, `kauth_login_attempts`, `kauth_otp_codes`, `kauth_api_tokens` | `com.greenwhite.dwh.instance.kauth` |
| **`ms`** | **Messaging & Services** | Проекты и задачи, комментарии, оповещения, Transactional Outbox, объявления | `ms_tasks`, `ms_task_projects`, `ms_notifications`, `ms_notification_outbox`, `ms_announcements` | `com.greenwhite.dwh.instance.ms` |
| **`mf`** | **Media / File Manager** | Файловое хранилище, SHA-256 дедупликация, интеграция с Garage S3 | `mf_files` | `com.greenwhite.dwh.instance.mf` |
| **`audit`** | **Audit & Security Log** | Журнал изменений данных (JSONB) и журнал событий безопасности | `audit_log`, `security_events` | `com.greenwhite.dwh.instance.audit` |
| **`cp`** | **Control Plane** | Центральный реестр клиентов, инвентарь версий, лицензии, телеметрия флота | `cp_clients`, `cp_instances`, `cp_licenses` | `com.greenwhite.dwh.cp` |

---

## 3. Стандарты именования Java-классов

Каждый класс в кодовой базе имеет строгий модульный префикс, отражающий его принадлежность к домену.

### 3.1. Контроллеры (REST Endpoints)
Формат: `{Prefix}{Entity}Controller`
- `MdUserController` — эндпоинты `/api/v1/users`
- `MdRoleController` — эндпоинты `/api/v1/roles`, `/api/v1/forms`
- `MdProfileController` — эндпоинты `/api/v1/profile`
- `KauthAuthController` — эндпоинты `/api/v1/auth/login`, `/api/v1/auth/otp`
- `KauthSessionController` — эндпоинты `/api/v1/users/{id}/sessions`
- `KauthTokenController` — эндпоинты `/api/v1/tokens`
- `MsTaskController` — эндпоинты `/api/v1/tasks`
- `MsTaskProjectController` — эндпоинты `/api/v1/task-projects`
- `MsNotificationController` — эндпоинты `/api/v1/notifications`
- `MsAnnouncementController` — эндпоинты `/api/v1/announcements`
- `MfFileController` — эндпоинты `/api/v1/files`
- `AuditLogController` — эндпоинты `/api/v1/audit`

### 3.2. Сервисы и Публичные Фасады
Формат: `{Prefix}{Entity}Service` или `{Prefix}{Entity}Facade`
- `MdUserService`, `MdRoleService`, `MdPermissionEvaluator`
- `KauthAuthService`, `KauthSessionService`, `KauthOtpService`, `KauthTokenService`
- `MsTaskService`, `MsTaskProjectService`, `MsNotificationService`, `MsOutboxWorker`
- `MfFileStorageService`
- `AuditLogService`, `AuditSecurityEventService`

### 3.3. Репозитории и доступ к данным
Формат: `{Prefix}{Entity}Repository`
- `MdUserRepository`, `MdRoleRepository`, `MdFormRepository`, `MdEffectivePermissionRepository`
- `KauthSessionRepository`, `KauthLoginAttemptRepository`, `KauthTokenRepository`
- `MsTaskRepository`, `MsTaskMemberRepository`, `MsNotificationOutboxRepository`
- `MfFileRepository`

### 3.4. Классы констант и преференсов (`*Pref` в стиле Biruni)
Централизованное место объявления доменных кодов, ролей, статусов и системных параметров.
- **`MdPref`:**
  ```java
  public final class MdPref {
      public static final String PCODE_ROLE_ADMIN = "admin";
      public static final String PCODE_ROLE_MANAGER = "manager";
      public static final String PCODE_ROLE_AUDITOR = "auditor";
      public static final String PCODE_ROLE_USER = "user";
      
      public static final String USER_STATE_ACTIVE = "A";
      public static final String USER_STATE_PASSIVE = "P";
      public static final String DEFAULT_LANGUAGE = "ru";
  }
  ```
- **`MsTaskPref`:**
  ```java
  public final class MsTaskPref {
      public static final String PCODE_STATUS_NEW = "new";
      public static final String PCODE_STATUS_IN_PROGRESS = "in_progress";
      public static final String PCODE_STATUS_DONE = "done";
      public static final String PCODE_STATUS_CANCELLED = "cancelled";

      public static final String INVOLVE_KIND_RESPONSIBLE = "R";
      public static final String INVOLVE_KIND_EXECUTOR = "E";
      public static final String INVOLVE_KIND_PARTICIPANT = "P";
      public static final String INVOLVE_KIND_AUTHOR = "A";
      public static final String INVOLVE_KIND_OBSERVER = "O";
  }
  ```
- **`KauthPref`:**
  ```java
  public final class KauthPref {
      public static final int OTP_LENGTH = 6;
      public static final Duration OTP_TTL = Duration.ofMinutes(5);
      public static final int MAX_LOGIN_ATTEMPTS = 5;
      public static final Duration SESSION_TTL = Duration.ofDays(30);
  }
  ```

### 3.5. DTO и Запросы/Ответы
Формат: `{Prefix}{Entity}{Action}Request` / `{Prefix}{Entity}Response`
- `MdUserCreateRequest`, `MdUserUpdateRequest`, `MdUserResponse`
- `KauthLoginRequest`, `KauthOtpVerifyRequest`, `KauthTokenResponse`
- `MsTaskCreateRequest`, `MsTaskStatusUpdateRequest`, `MsTaskResponse`
- `MfFileUploadResponse`

### 3.6. Доменные события (Domain Events)
Формат: `{Prefix}{Entity}{Action}Event`
- `MdUserCreatedEvent`, `MdUserBlockedEvent`, `MdRolePermissionsChangedEvent`
- `KauthSessionRevokedEvent`, `KauthSecurityAlertEvent`
- `MsTaskAssignedEvent`, `MsTaskStatusChangedEvent`, `MsTaskCommentAddedEvent`

---

## 4. Спецификация именования таблиц в PostgreSQL 18

```
Master Data (md_*):
├── md_instance_info                    # Метаданные инстанса (профиль S/M/L, ключи CP)
├── md_settings                         # Иерархические настройки (экземпляр/пользователь)
├── md_users                            # Учётные записи сотрудников
├── md_roles                            # Роли пользователей (с pcode для системных)
├── md_role_permissions                 # Права ролей (form_code, action)
├── md_user_roles                       # Связка пользователей и ролей
├── md_user_permissions                 # Персональные права пользователей
├── md_effective_permissions            # Материализованные права (FR-PERM-6)
├── md_user_permission_versions         # Версии кэша прав для мгновенной инвалидации
├── md_forms                            # Реестр экранных форм
└── md_form_actions                     # Реестр действий по формам

Kernel Auth (kauth_*):
├── kauth_sessions                      # Серверные сессии пользователей
├── kauth_login_attempts                # Журнал попыток входа
├── kauth_otp_codes                     # Одноразовые OTP-коды 2FA
├── kauth_api_tokens                    # Сервисные токены API (FR-AUTH-6)
├── kauth_password_reset_codes          # Одноразовые коды сброса паролей
└── kauth_user_channels                 # Подтверждённые каналы (Telegram chat_id, телефон)

Messaging & Services (ms_*):
├── ms_task_projects                    # Проекты задач
├── ms_task_project_members             # Участники проектов и их уровень доступа (R/W)
├── ms_task_statuses                    # Справочник статусов задач (new, in_progress, done)
├── ms_tasks                            # Агрегат задачи
├── ms_task_members                     # Участники задачи по ролям (R, E, P, A, O)
├── ms_task_comments                    # Комментарии к задачам
├── ms_task_comment_files               # Вложения к комментариям
├── ms_notifications                    # In-app уведомления
├── ms_notification_outbox              # Transactional Outbox очередь доставки
├── ms_notification_prefs               # Матрица настроек каналов пользователя
├── ms_announcements_cache              # Кэш объявлений из Control Plane
└── ms_announcement_reads               # Серверные отметки о прочтении

Media / Files (mf_*):
└── mf_files                            # Метаданные файлов и SHA-256 хеши в Garage S3

Аудит и безопасность:
├── audit_log                           # Партиционированный журнал изменений данных (JSONB)
├── security_events                     # Журнал событий безопасности
└── idempotency_keys                    # Таблица фиксации идемпотентности API (FR-API-3)
```
