# Дорожная карта и вехи разработки (MILESTONES.md)

**Цель:** поэтапный помодульный пересмотр, доводка и приёмка модулей Этапа 1 (CMS Core & Fleet).  
**Правило приёмки:** Модуль считается закрытым, когда закрыты все его M-требования (ТЗ-01 разд. 4), написаны unit/интеграционные тесты и функционал воспроизводится на живом экземпляре.

---

## Каталог вех (M1 → M18)

```
Круг 1 (Ядро доступа):      M2 Пользователи  →  M3 Авторизация  →  M4 RBAC
                                   │
Круг 2 (Сквозные контракты): M8 Аудит        →  M10 API-контракт →  M9 Настройки/i18n
                                   │
Круг 3 (Бизнес-функции):    M5 Задачник      →  M6 Оповещения   →  M7 Файлы
                            M16 Атрибуты     →  M17 Поиск       →  M18 Вебхуки
                                   │
Круг 4 (Платформа и флот):   M1 Экземпляр     →  M15 Control Plane → M11 Безопасность
                            M12 Модульность  →  M14 Провайдеры  →  M13 Наблюдаемость
```

---

### M1. Экземпляр и инициализация (INST)
- **Цель:** Надежный жизненный цикл клиентского инстанса, schema-gate защита, регистрация первого админа, периодический heartbeat в Control Plane.
- **DoD:**
  - `InstanceBootstrap` инициализирует `md_instance_info` и аккаунт `admin` (с `force_password_change = true`).
  - `SchemaVersionGate` блокирует запуск приложения при несовпадении версий схемы Flyway.
  - `HeartbeatSenderWorker` отправляет периодические heartbeats в Control Plane (`POST /api/v1/cp/heartbeat`).
- **Файлы:** `apps/instance/.../config/bootstrap/InstanceBootstrap.java`, `SchemaVersionGate.java`, `HeartbeatSenderWorker.java`.
- **Команда проверки:** `mvn test -Dtest=MigrationGateAndBootstrapTest`

---

### M2. Пользователи и профили (USR) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Управление жизненным циклом пользователей, защита учетной записи суперпользователя `admin` (I-IAM-1), аннулирование сессий при блокировке (I-U1), валидация паролей и анонимизация (FR-USR-1..8).
- **DoD:**
  - `PasswordValidator`: проверка минимальной длины (10 символов), blacklist скомпрометированных паролей, запрет логина в пароле.
  - Инвариант I-U1: блокировка пользователя закрывает все сессии и отзывает токены в одной транзакции (`UserBlockingInvariantTest`).
  - Уникальность email, логина и телефона среди активных пользователей.
  - FR-USR-8: безопасная анонимизация пользователя с затиранием ПДн (`DELETE /api/v1/iam/users/{id}`).
  - FR-USR-7: смена пароля в профиле с обязательной проверкой текущего пароля (`POST /api/v1/iam/users/me/password`).
  - Frontend: удаление (анонимизация) в гриде пользователей и обновленная валидация.
- **Файлы:** `apps/instance/.../md/service/MdUserService.java`, `PasswordValidator.java`, `UserSessionInvalidator.java`, `MdUserController.java`.
- **Команда проверки:** `mvn test -Dtest=UserBlockingInvariantTest,MdUserServiceTest` (100% SUCCESS)


---

### M3. Авторизация и аутентификация (AUTH) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Безопасный вход, серверные сессии с CSRF защитой, 2FA OTP через Telegram/SMS, отзываемые API-токены, защита от подбора паролей и управление жизненным циклом сессий.
- **DoD:**
  - FR-AUTH-4: Защита от brute-force с блокировкой на 10 минут после 5 неудачных попыток (`ErrorCode.LOGIN_LOCKED`, HTTP 423).
  - FR-AUTH-7: Восстановление пароля по 6-значному OTP-коду (SHA-256 хэш, 15 мин TTL, `PasswordValidator`, аннулирование всех активных сессий).
  - FR-AUTH-8: Фоновый воркер `KauthSessionCleanupWorker` для автоматического закрытия сессий, неактивных более 12 ч.
  - FR-AUTH-3: Эндпоинты управления сессиями пользователей администратором (`GET/DELETE /api/v1/iam/profile/sessions/users/{userId}`).
  - FR-SEC-1: Двухфакторная CSRF double-submit защита (cookie `XSRF-TOKEN` + заголовок `X-XSRF-TOKEN`).
  - FR-AUTH-6: Генерация и аутентификация через Bearer API-токены (`dwh_...`) с SHA-256 хэшированием и префиксом.
- **Файлы:** `apps/instance/.../kauth/service/KauthAuthService.java`, `KauthSessionService.java`, `KauthSessionCleanupWorker.java`, `KauthAuthController.java`, `KauthSessionController.java`, `KauthApiTokenController.java`.
- **Команда проверки:** `mvn test` (62/62 тестов пройдено), `powershell scripts/dev/test-api.ps1` (15/15 E2E сценариев на Docker пройдены).


---

### M4. Ролевой доступ RBAC (PERM) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Разграничение прав на базе пар `(form, action)`, материализованная таблица прав `md_effective_permissions`, защита системных ролей (I-P4), мгновенная инвалидация и современный UI матрицы прав.
- **DoD:**
  - Автоматическая синхронизация каталога форм из аннотаций `@RequiresPermission`.
  - Матрица системных ролей (admin, manager, user, auditor) проверена интеграционными тестами.
  - Изменение ролей инкрементирует `permissions_version` и мгновенно пересчитывает эффективные права всех затронутых пользователей (`getUserIdsByRole`).
  - Защита системных ролей от удаления и суперадминистратора от перевода в пассивный статус (`ErrorCode.SUPERADMIN_IMMUTABLE`).
  - Минималистичный и функциональный UI матрицы прав с группировкой по модулям, поиском и пакетными действиями (`Выбрать все` / `Снять все`).
- **Файлы:** `apps/instance/.../md/service/MdRoleService.java`, `MdPermissionService.java`, `MdRoleRepository.java`, `apps/web-instance/src/app/features/iam/roles/roles.component.ts`.
- **Команда проверки:** `mvn test -Dtest=RbacSystemRolesIntegrationTest` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (15/15 SUCCESS).


---

### M5. Мини таск-менеджер (TASK) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Управление проектами и задачами, инварианты I-T1 (ровно один ответственный) и I-T2 (защита от циклов в дереве задач), минималистичный UI/UX.
- **DoD:**
  - Проекты, статусы (системные/кастомные, терминальные с временем завершения).
  - Рекурсивный CTE `isDescendantOf` предотвращает зацикливание подзадач при обновлении и назначении родительской задачи.
  - Доменные события `TaskAssigned`, `TaskStatusChanged` публикуются через `eventPublisher`.
  - Эндпоинт справочника статусов `GET /api/v1/tasks/statuses`.
  - Минималистичный и адаптивный интерфейс управления задачами и проектами (`tasks.component.ts`, `projects.component.ts`) с фильтрацией, динамическими полями и комментариями Markdown.
- **Файлы:** `apps/instance/.../ms/task/service/MsTaskService.java`, `MsProjectService.java`, `MsTaskController.java`, `apps/web-instance/src/app/features/tasks/tasks.component.ts`, `projects.component.ts`.
- **Команда проверки:** `mvn test -Dtest=MsTaskServiceTest` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (15/15 SUCCESS).


---

### M6. Оповещения и события (NOTIF) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Доставка уведомлений in-app, realtime через SSE (`/api/v1/events`), transactional outbox с `SELECT FOR UPDATE SKIP LOCKED`.
- **DoD:**
  - In-app лента уведомлений со счетчиком непрочитанных.
  - SSE поток событий `/api/v1/events` с поддержкой автореконнекта и keep-alive (`NotificationService.connectSse()`).
  - Outbox воркер `MsOutboxWorker` с экспоненциальным backoff и обработкой dead-letter.
  - Доменные слушатели `MsTaskNotificationListener` для автоматического создания уведомлений при назначении задач, смене статусов и комментариях.
- **Файлы:** `apps/instance/.../ms/notify/service/MsNotificationService.java`, `MsSseRegistry.java`, `MsSsePublisher.java`, `MsOutboxWorker.java`, `apps/web-instance/.../core/services/notification.service.ts`.
- **Команда проверки:** `mvn test -Dtest=MsSseRegistryTest` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (15/15 SUCCESS).


---

### M7. Файловое хранилище и квоты компании/сотрудников (FILE) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Загрузка, стриминг и хранение файлов с дедупликацией по SHA-256, проверка прав доступа, прикрепление файлов к задачам, многоуровневые дисковые квоты компании и сотрудников, управление лимитами по ролям и Control Plane, раздел «Файловое хранилище» (`/files`).
- **DoD:**
  - Потоковый расчет SHA-256 хеша на лету (`LocalStorageProvider`).
  - Дедупликация идентичных файлов по SHA-256 (`MfFileService`).
  - Блокировка потенциально опасных исполняемых расширений (`.exe`, `.sh`, `.bat` и др.).
  - Таблица связей `ms_task_files` (миграция `V008__task_file_attachments.sql`).
  - Миграция `V009__storage_quotas_and_file_management.sql` (`storage_quota_bytes` в `md_instance_info`, `md_roles`, `md_users`).
  - Проверка инвариантов: `companyQuota >= userQuota`, блокировка загрузки при превышении лимитов (`STORAGE_QUOTA_EXCEEDED`, `USER_STORAGE_QUOTA_EXCEEDED`).
  - Телеметрия хранилища в Heartbeat для Control Plane (`storageUsedBytes`, `storageQuotaBytes`).
  - Эндпоинты `/api/v1/files/storage/stats`, `/api/v1/files`, `/api/v1/files/{id}` (удаление с очисткой квоты).
  - Полнофункциональный интерфейс «Файловое хранилище» (`FilesComponent`): карточки квот компании и пользователя с цветовой градацией, вкладки «Все файлы компании» / «Мои файлы», поиск, фильтрация, пагинация, скачивание и удаление.
- **Файлы:** `apps/instance/.../mf/service/MfFileService.java`, `MfFileRepository.java`, `MfFileController.java`, `LocalStorageProvider.java`, `apps/web-instance/.../features/files/files.component.ts`.
- **Команда проверки:** `mvn test -Dtest=MfFileServiceTest` (100% SUCCESS, 4/4 тестов), `powershell scripts/dev/test-api.ps1` (15/15 SUCCESS).



---

### M8. Аудит и безопасность (AUD) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Неизменяемый партиционированный журнал аудита изменений (`audit_log`) и журнал security-событий (`security_events`), REST API аудита со статистикой и полноценный веб-интерфейс с Visual Diff сравнением.
- **DoD:**
  - Запись старых и новых значений при мутациях бизнес-сущностей (`md_users`, `ms_tasks`, `ms_projects`, `md_roles`, `md_custom_fields`).
  - Фиксация событий авторизации (`LOGIN_SUCCESS`, `LOGIN_FAILED`), смены паролей, сброса, 2FA, выдачи API-токенов и срабатывания rate limiting (`LOGIN_LOCKED`, `IP_RATE_LIMITED`).
  - REST API эндпоинты `/api/v1/audit/logs`, `/api/v1/audit/security-events`, `/api/v1/audit/stats` с фильтрами по таблицам, событиям, пользователям, IP и датам.
  - Полноценный веб-интерфейс `AuditComponent` (`/audit`) со сводными карточками метрик за 24ч, вкладками «Журнал изменений» и «События безопасности», модальным окном интерактивного Visual Diff (подсветка измененных полей) и просмотром JSON параметров.
- **Файлы:** `apps/instance/.../audit/service/AuditLogService.java`, `AuditLogRepository.java`, `AuditLogController.java`, `apps/web-instance/.../features/audit/audit.component.ts`.
- **Команда проверки:** `mvn test -Dtest=AuditLogServiceTest` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (16/16 SUCCESS).


---

### M9. Настройки и локализация (SET & I18N) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Иерархические настройки ключ-значение (Defaults $\rightarrow$ Instance Settings $\rightarrow$ User Settings), аудит мутаций настроек, эндпоинты API и мультиязычный интерфейс (ru, uz, en).
- **DoD:**
  - Наследование настроек: системные $\rightarrow$ пользовательские (автоматический fallback на значения по умолчанию при отсутствии пользовательских переопределений).
  - Безопасный upsert системных и пользовательских настроек в `MdSettingRepository`.
  - Автоматическая фиксация изменений системных настроек в `audit_log` через `AuditLogService`.
  - Эндпоинты `/api/v1/settings` (effective), `/api/v1/settings/system` (view/update), `/api/v1/settings/user` (view/update), `/api/v1/i18n/{lang}` (словари).
  - Полнофункциональный веб-интерфейс «Настройки системы и персонализация» (`SettingsComponent` по адресу `/settings`): вкладки «Общие настройки», «Безопасность и пароли», «Хранилище», «Мои предпочтения».
  - Полноценные JSON-словари `ru`, `uz`, `en` с динамическим переключением языка на лету без перезагрузки страницы.
- **Файлы:** `apps/instance/.../md/service/MdSettingService.java`, `MdSettingRepository.java`, `MdSettingController.java`, `MdI18nController.java`, `apps/web-instance/.../features/settings/settings.component.ts`, `i18n.service.ts`.
- **Команда проверки:** `mvn test -Dtest=MdSettingServiceTest` (100% SUCCESS, 3/3 тестов), `powershell scripts/dev/test-api.ps1` (17/17 SUCCESS).


---

### M10. API-контракт и идемпотентность (API) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Стандартизированный REST API с RFC 9457 ProblemDetail, Keyset пагинацией, поддержкой заголовка `Idempotency-Key` и спецификацией OpenAPI 3.1.
- **DoD:**
  - Обработка заголовка `Idempotency-Key` (UUID) на мутирующих запросах (`POST`, `PUT`, `PATCH`, `DELETE`).
  - Повторный вызов с тем же ключом и телом возвращает закэшированный ответ с заголовком `Idempotent-Replay: true` без повторного выполнения бизнес-логики.
  - Повторный вызов с тем же ключом, но измененным телом возвращает ошибку `409 Conflict` (`idempotency_key_payload_mismatch`).
  - Проверка формата UUID ключа с возвратом `400 Bad Request` (`idempotency_key_invalid`).
  - Единый формат ошибок RFC 9457 `ProblemDetail` для всех кодов (`type`, `title`, `status`, `code`, `detail`, `instance`, `timestamp`).
  - Спецификация OpenAPI 3.1 по адресу `GET /api/v1/openapi.json` и `GET /v3/api-docs`.
- **Файлы:** `libs/core-types/.../error/ErrorCode.java`, `ProblemDetailRecord.java`, `apps/instance/.../config/idempotency/IdempotencyFilter.java`, `IdempotencyService.java`, `IdempotencyRepository.java`, `CachedBodyHttpServletRequest.java`, `apps/instance/.../config/openapi/OpenApiController.java`.
- **Команда проверки:** `mvn test -Dtest=IdempotencyServiceTest` (100% SUCCESS, 3/3 тестов), `powershell scripts/dev/test-api.ps1` (18/18 SUCCESS).


---

### M11. Безопасность и соответствие (SEC) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Защита от OWASP Top 10, CSRF double-submit, Rate Limiting, безопасные HTTP заголовки, Argon2id, блокировка перебора паролей.
- **DoD:**
  - Все эндпоинты защищены Spring Security (RBAC, ролевая модель, Kauth сессии и Bearer токены).
  - CSRF double-submit защита (`CookieCsrfTokenRepository.withHttpOnlyFalse()` + `X-XSRF-TOKEN`).
  - Rate Limiting на уровне IP и пользователей (`RateLimitFilter` + Bucket4j + фиксация в security events).
  - Брутфорс-защита: временная блокировка аккаунта после 5 неудачных попыток (`HTTP 423 Locked`).
  - Полный набор HTTP заголовков безопасности (CSP, HSTS, X-Frame-Options, Permissions-Policy, Referrer-Policy).
  - Неизменяемость суперпользователя `admin` (инвариант `I-IAM-1`).
- **Файлы:** `apps/instance/.../config/security/SecurityConfig.java`, `RateLimitFilter.java`, `RateLimitService.java`, `ProblemDetailAuthHandlers.java`, `KauthPasswordHasher.java`.
- **Команда проверки:** `mvn test -Dtest=SecurityConfigTest,RateLimitFilterTest,KauthPasswordHasherTest` (10/10 SUCCESS), `powershell scripts/dev/test-api.ps1` (18/18 SUCCESS).

---

### M12. Модульность и архитектурные границы (MOD) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Изоляция подсистем модульного монолита, запрет циклических зависимостей, прямого доступа к чужим репозиториям.
- **DoD:**
  - ArchUnit тесты валидируют направленный ациклический граф (DAG).
  - Отсутствие циклических зависимостей между пакетами (`md`, `kauth`, `ms`, `mf`, `audit`, `kwh`, `search`).
- **Файлы:** `apps/instance/src/test/java/com/greenwhite/dwh/instance/architecture/ModularArchitectureTest.java`.
- **Команда проверки:** `mvn test -Dtest=ModularArchitectureTest` (100% SUCCESS, 3/3 тестов).

---

### M13. Наблюдаемость флота (OBS) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Сквозная трассировка W3C `traceparent`, структурированное логирование с контекстом в MDC (`traceparent`, `trace_id`, `client_code`), бизнес-метрики Prometheus через Micrometer и мониторинг здоровья подсистем.
- **DoD:**
  - `TraceparentFilter` перехватывает или генерирует валидный W3C заголовок (`00-{traceId}-{spanId}-01`), помещает его в MDC SLF4J для обогащения логов и возвращает заголовок `traceparent` клиенту в HTTP-ответе.
  - Бизнес-метрики `PlatformMetrics` (Micrometer / Prometheus): счетчики логинов (`dwh_auth_logins_total`), превышений rate-limit (`dwh_security_rate_limit_exceeded_total`), создания задач (`dwh_tasks_created_total`), объема файлов (`dwh_files_uploaded_bytes_total`), мутаций данных (`dwh_audit_mutations_total`).
  - Комплексный мониторинг подсистем `DwhInfoContributor` на `/actuator/info` (PostgreSQL latency, Typesense cluster status, storage free space).
  - Экспорт метрик Prometheus на management-порту (`/actuator/prometheus`).
- **Файлы:** `apps/instance/.../config/web/TraceparentFilter.java`, `apps/instance/.../common/metrics/PlatformMetrics.java`, `apps/instance/.../config/health/DwhInfoContributor.java`.
- **Команда проверки:** `mvn test -Dtest=TraceparentFilterTest,AuditLogServiceTest` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (19/19 SUCCESS).


---

### M14. Провайдеры SPI (PLUG) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Абстракция внешних сервисов (Storage, Mail, SMS, Messenger) через SPI интерфейсы.
- **DoD:**
  - Библиотека `libs/provider-spi` с интерфейсами `StorageProvider`, `MailProvider`, `SmsProvider`, `MessengerProvider`.
  - Дефолтные реализации `LocalStorageProvider`, `ConsoleMailProvider`, `ConsoleSmsProvider`, `ConsoleMessengerProvider`.
- **Файлы:** `libs/provider-spi/...`.

---

### M15. Control Plane и управление флотом (CP)
- **Цель:** Центральный реестр инстансов, прием heartbeat, дашборд флота, публикация объявлений.
- **DoD:**
  - Реестр клиентов и инстансов с отображением версий и статусов.
  - Веб-панель `apps/web-cp` для администраторов платформы.
- **Файлы:** `apps/control-plane/...`, `apps/web-cp/...`.

---

### M16. Динамические атрибуты (ATTR) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Добавление произвольных атрибутов к пользователям, проектам и задачам в `jsonb` с валидацией типов и GIN-индексацией.
- **DoD:**
  - Валидация типов (string, number, boolean, date, select).
  - Компонент `ui-custom-fields` для динамического рендеринга.
- **Файлы:** `apps/instance/.../md/service/MdCustomFieldService.java`.
- **Команда проверки:** `mvn test -Dtest=MdCustomFieldServiceTest`


---

### M17. Полнотекстовый поиск (SEARCH) [✅ ВЫПОЛНЕНО 2026-08-29]
- **Цель:** Высокопроизводительный поиск Typesense + палитра быстрого поиска Command Palette (`Ctrl+K`) с поддержкой опечаток, Soundex, префиксного поиска и Postgres Fallback.
- **DoD:**
  - Интеграция Typesense (порт 8108, образ 27.1).
  - Схемы коллекций `tasks`, `projects`, `users` с `enable_phonetic`, `num_typos: 2`, `prefix: true`.
  - Фоновая первичная синхронизация и асинхронный индексатор `TypesenseIndexer`.
  - Автоматический Graceful Fallback на PostgreSQL при недоступности движка.
  - Панель Command Palette (`Ctrl + K`) с клавиатурной навигацией (`↑`, `↓`, `Enter`) и локализованными бейджами.
- **Файлы:** `apps/instance/.../search/typesense/TypesenseClient.java`, `TypesenseIndexer.java`, `SearchService.java`, `apps/web-instance/.../command-palette/command-palette.component.ts`.
- **Команда проверки:** `mvn test -Dtest=SearchServiceTest` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (15/15 SUCCESS).


---

### M18. Исходящие вебхуки (KWH)
- **Цель:** Подписка на доменные события, доставка через Outbox с подписью HMAC-SHA256 (`X-Signature-SHA256`).
- **DoD:**
  - Валидация URL подписок (защита от SSRF).
  - Outbox воркер с автоматическими повторами.
- **Файлы:** `apps/instance/.../kwh/service/KwhWebhookService.java`, `KwhOutboxWorker.java`.
- **Команда проверки:** `mvn test -Dtest=KwhWebhookServiceTest`
