# Дорожная карта и вехи разработки (MILESTONES.md)

**Цель:** поэтапный помодульный пересмотр, доводка и приёмка модулей Этапа 1 (CMS Core & Fleet).  
**Правило приёмки:** Модуль считается закрытым, когда закрыты все его M-требования (ТЗ-01 разд. 4),
написаны unit/интеграционные тесты и функционал воспроизводится на живом экземпляре.

**Обозначения статуса (введены 30.08 по итогам AUDIT-05, дефект Д-6).** Раньше все 18 вех
стояли «ВЫПОЛНЕНО» при том, что часть требований физически нельзя закрыть без инфраструктуры
фазы P. Отчётность разошлась с фактом, и именно от этого затевался помодульный пересмотр.
Теперь «код написан» и «требование закрыто» — разные вещи:

| Статус | Что означает |
|---|---|
| ✅ **ЗАКРЫТО** | все M-требования вехи выполнены, тесты зелёные, воспроизведено вживую |
| ◐ **ЧАСТИЧНО** | код написан и работает, но часть требований ждёт инфраструктуру или решение |
| ❄️ **ЗАМОРОЖЕНО** | веха закрыта и выведена из развития: изменения только по дефектам |

Веха, зависящая от Vault, Garage, Nomad или боевого шлюза, **не может быть закрытой** до фазы P.
Такое требование помечается «ждёт фазу P» с указанием, чего именно не хватает.

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

### M1. Экземпляр и инициализация (INST) [◐ ЧАСТИЧНО] код 2026-08-29 · FR-INST-4 (лицензии) и FR-INST-5 (pull объявлений) ждут фазу P: нет Vault и воркера
- **Цель:** Надежный жизненный цикл клиентского инстанса, schema-gate защита, регистрация первого админа, периодический heartbeat в Control Plane.
- **DoD:**
  - `InstanceBootstrap` инициализирует `md_instance_info` и аккаунт `admin` (с `force_password_change = true`).
  - `SchemaVersionGate` блокирует запуск приложения при несовпадении версий схемы Flyway.
  - `CpHeartbeatWorker` отправляет периодические heartbeats в Control Plane (`POST /api/v1/instances/heartbeat`).
- **Файлы:** `apps/instance/.../config/bootstrap/InstanceBootstrap.java`, `SchemaVersionGate.java`, `CpHeartbeatWorker.java`.
- **Команда проверки:** `mvn test` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (Сценарии 1-3, 100% SUCCESS).


---

### M2. Пользователи и профили (USR) [✅ ЗАКРЫТО 2026-08-29]
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

### M3. Авторизация и аутентификация (AUTH) [◐ ЧАСТИЧНО] код 2026-08-29 · восстановление пароля и OTP работают только на настроенном SMTP или Telegram; боевой SMS-шлюз ждёт договор
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

### M4. Ролевой доступ RBAC (PERM) [✅ ЗАКРЫТО 2026-08-30] FR-PERM-1 закрыт: каталог синхронизируется из @RequiresPermission, устаревшие пары помечаются и не выдаются (Д-5) · 30.08 добавлен скоуп данных (ADR-0013)
- **Цель:** Разграничение прав на базе пар `(form, action)`, материализованная таблица прав `md_effective_permissions`, защита системных ролей (I-P4), мгновенная инвалидация и современный UI матрицы прав.
- **DoD:**
  - Автоматическая синхронизация каталога форм из аннотаций `@RequiresPermission`.
  - Матрица системных ролей (admin, manager, user, auditor) проверена интеграционными тестами.
  - Изменение ролей инкрементирует `permissions_version` и мгновенно пересчитывает эффективные права всех затронутых пользователей (`getUserIdsByRole`).
  - Защита системных ролей от удаления и суперадминистратора от перевода в пассивный статус (`ErrorCode.SUPERADMIN_IMMUTABLE`).
  - Минималистичный и функциональный UI матрицы прав с группировкой по модулям, поиском и пакетными действиями (`Выбрать все` / `Снять все`).
- **Закрыто 30.08 (Д-5):** каталог форм синхронизируется при старте из аннотаций
  `@RequiresPermission` (`MdFormCatalogSynchronizer`), имена берутся из `MdFormCatalog`,
  запись без эндпоинта помечается устаревшей и не может быть выдана ни роли, ни лично.
  Найдено и помечено четыре мёртвых пары: `notify.preferences.view/.update`,
  `iam.profile.manage_channels`, `platform.files.manage_quotas`.
- **Файлы:** `apps/instance/.../md/service/MdRoleService.java`, `MdPermissionService.java`,
  `MdFormCatalogSynchronizer.java`, `md/pref/MdFormCatalog.java`, `MdRoleRepository.java`,
  `apps/web-instance/src/app/features/iam/roles/roles.component.ts`.
- **Команда проверки:** `mvn test` (139 тестов, BUILD SUCCESS), живой прогон 9/9.


---

### M5. Мини таск-менеджер (TASK) [❄️ ЗАМОРОЖЕНО 2026-08-30] закрыто и выведено из развития
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

### M6. Оповещения и события (NOTIF) [◐ ЧАСТИЧНО] 30.08 реализованы SMTP и Telegram Bot API · FR-NOTIF-5 (SMS) остаётся заглушкой: ждёт договор с оператором
- **Цель:** Доставка уведомлений in-app, realtime через SSE (`/api/v1/events`), transactional outbox с `SELECT FOR UPDATE SKIP LOCKED`.
- **DoD:**
  - In-app лента уведомлений со счетчиком непрочитанных.
  - SSE поток событий `/api/v1/events` с поддержкой автореконнекта и keep-alive (`NotificationService.connectSse()`).
  - Outbox воркер `MsOutboxWorker` с экспоненциальным backoff и обработкой dead-letter.
  - Доменные слушатели `MsTaskNotificationListener` для автоматического создания уведомлений при назначении задач, смене статусов и комментариях.
- **Файлы:** `apps/instance/.../ms/notify/service/MsNotificationService.java`, `MsSseRegistry.java`, `MsSsePublisher.java`, `MsOutboxWorker.java`, `apps/web-instance/.../core/services/notification.service.ts`.
- **Команда проверки:** `mvn test -Dtest=MsSseRegistryTest` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (15/15 SUCCESS).


---

### M7. Файловое хранилище и квоты компании/сотрудников (FILE) [◐ ЧАСТИЧНО] код 2026-08-29 · FR-FILE-1 (S3/Garage) ждёт фазу P: работает только LocalStorageProvider
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

### M8. Аудит и безопасность (AUD) [✅ ЗАКРЫТО 2026-08-30] аудит расставлен по всем мутирующим сервисам и закреплён тестом-стражем · FR-AUD-2 срок хранения отцеплением партиций · журнал стал действительно неизменяемым (V014)
- **Цель:** Неизменяемый партиционированный журнал аудита изменений (`audit_log`) и журнал security-событий (`security_events`), REST API аудита со статистикой и полноценный веб-интерфейс с Visual Diff сравнением.
- **DoD:**
  - Запись старых и новых значений при мутациях бизнес-сущностей (`md_users`, `ms_tasks`, `ms_projects`, `md_roles`, `md_custom_fields`).
  - Фиксация событий авторизации (`LOGIN_SUCCESS`, `LOGIN_FAILED`), смены паролей, сброса, 2FA, выдачи API-токенов и срабатывания rate limiting (`LOGIN_LOCKED`, `IP_RATE_LIMITED`).
  - REST API эндпоинты `/api/v1/audit/logs`, `/api/v1/audit/security-events`, `/api/v1/audit/stats` с фильтрами по таблицам, событиям, пользователям, IP и датам.
  - Полноценный веб-интерфейс `AuditComponent` (`/audit`) со сводными карточками метрик за 24ч, вкладками «Журнал изменений» и «События безопасности», модальным окном интерактивного Visual Diff (подсветка измененных полей) и просмотром JSON параметров.
- **Закрыто 30.08:**
  - аудит пишут все мутирующие сервисы: права и роли, оргструктура и скоуп, файлы,
    вебхуки, динамические поля, проекты и их участники, комментарии;
  - `AuditCoverageTest` не даёт этому разъехаться снова: мутирующий сервис без
    `AuditLogService` валит сборку, список исключений закрытый и проверяется на протухание;
  - FR-AUD-2: партиции старше срока хранения отцепляются и переименовываются в
    `audit_log_archived_YYYY_MM`. Данные не удаляются — автоматическое удаление аудита
    необратимо, решение за эксплуатацией;
  - V014: журнал стал действительно неизменяемым — UPDATE и DELETE по `audit_log`
    отклоняются на уровне базы. До этого «неизменяемость» была только словом в цели вехи.
  - Секреты в журнал не попадают: токен подписи вебхука и текст комментария не пишутся.
- **Файлы:** `apps/instance/.../audit/service/AuditLogService.java`, `AuditLogRepository.java`,
  `AuditPartitionRepository.java`, `AuditPartitionWorker.java`, `AuditLogController.java`,
  `apps/web-instance/.../features/audit/audit.component.ts`.
- **Команда проверки:** `mvn test` (139 тестов, BUILD SUCCESS), живой прогон 9/9
  плюс проверка неизменяемости прямым psql к стенду.


---

### M9. Настройки и локализация (SET & I18N) [✅ ЗАКРЫТО 2026-08-29]
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

### M10. API-контракт и идемпотентность (API) [✅ ЗАКРЫТО 2026-08-30] Д-9 закрыт: 405 вместо 500, плюс 409 на нарушение целостности
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

### M11. Безопасность и соответствие (SEC) [◐ ЧАСТИЧНО] код 2026-08-29 · FR-SEC-3 (секреты из Vault) ждёт фазу P: секреты живут в .env
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

### M12. Модульность и архитектурные границы (MOD) [✅ ЗАКРЫТО 2026-08-29]
- **Цель:** Изоляция подсистем модульного монолита, запрет циклических зависимостей, прямого доступа к чужим репозиториям.
- **DoD:**
  - ArchUnit тесты валидируют направленный ациклический граф (DAG).
  - Отсутствие циклических зависимостей между пакетами (`md`, `kauth`, `ms`, `mf`, `audit`, `kwh`, `search`).
- **Файлы:** `apps/instance/src/test/java/com/greenwhite/dwh/instance/architecture/ModularArchitectureTest.java`.
- **Команда проверки:** `mvn test -Dtest=ModularArchitectureTest` (100% SUCCESS, 3/3 тестов).

---

### M13. Наблюдаемость флота (OBS) [◐ ЧАСТИЧНО] есть traceparent и метрики Prometheus · FR-OBS-1/5/6 (стек, алерты, дашборды) ждут фазу P
- **Цель:** Сквозная трассировка W3C `traceparent`, структурированное логирование с контекстом в MDC (`traceparent`, `trace_id`, `client_code`), бизнес-метрики Prometheus через Micrometer и мониторинг здоровья подсистем.
- **DoD:**
  - `TraceparentFilter` перехватывает или генерирует валидный W3C заголовок (`00-{traceId}-{spanId}-01`), помещает его в MDC SLF4J для обогащения логов и возвращает заголовок `traceparent` клиенту в HTTP-ответе.
  - Бизнес-метрики `PlatformMetrics` (Micrometer / Prometheus): счетчики логинов (`dwh_auth_logins_total`), превышений rate-limit (`dwh_security_rate_limit_exceeded_total`), создания задач (`dwh_tasks_created_total`), объема файлов (`dwh_files_uploaded_bytes_total`), мутаций данных (`dwh_audit_mutations_total`).
  - Комплексный мониторинг подсистем `DwhInfoContributor` на `/actuator/info` (PostgreSQL latency, Typesense cluster status, storage free space).
  - Экспорт метрик Prometheus на management-порту (`/actuator/prometheus`).
- **Файлы:** `apps/instance/.../config/web/TraceparentFilter.java`, `apps/instance/.../common/metrics/PlatformMetrics.java`, `apps/instance/.../config/health/DwhInfoContributor.java`.
- **Команда проверки:** `mvn test -Dtest=TraceparentFilterTest,AuditLogServiceTest` (100% SUCCESS), `powershell scripts/dev/test-api.ps1` (19/19 SUCCESS).


---

### M14. Провайдеры SPI (PLUG) [✅ ЗАКРЫТО 2026-08-30] механизм проверен на настоящих провайдерах SMTP и Telegram, а не только на заглушках
- **Цель:** Абстракция внешних сервисов (Storage, Mail, SMS, Messenger) через SPI интерфейсы, динамический реестр `ProviderRegistry` и единый мониторинг здоровья всех провайдеров.
- **DoD:**
  - Библиотека `libs/provider-spi` с интерфейсами `StorageProvider`, `MailProvider`, `SmsProvider`, `MessengerProvider` и структурой `ProviderHealth`.
  - Реализации провайдеров: `LocalStorageProvider` (файлы и квоты), `ConsoleMailProvider` (email), `ConsoleSmsProvider` (SMS), `TelegramMessengerProvider` (Telegram Bot).
  - Компонент `ProviderRegistry` с поддержкой конфигурационных свойств Spring Boot (`dwh.providers.storage`, `dwh.providers.mail`, `dwh.providers.sms`, `dwh.providers.messenger`) и методом `checkAllHealth()`.
- **Файлы:** `libs/provider-spi/...`, `apps/instance/.../common/provider/ProviderRegistry.java`.
- **Команда проверки:** `mvn test -Dtest=ProviderRegistryTest` (100% SUCCESS, 2/2 тестов), `powershell scripts/dev/test-api.ps1` (19/19 SUCCESS).


---

### M15. Control Plane и управление флотом (CP) [◐ ЧАСТИЧНО] код 2026-08-29 · FR-CP-3 (инвентарь из Nomad), FR-CP-4 (лицензии), FR-CP-8 (приём метрик) ждут фазу P · тестами покрыто 4 сценария на 12 таблиц
- **Цель:** Центральный реестр инстансов, прием heartbeat, мониторинг флота, управление клиентами, публикация системных объявлений и веб-панель `web-cp`.
- **DoD:**
  - Реестр клиентов и инстансов с генерацией безопасных SHA-256 токенов аутентификации.
  - Прием heartbeats (`POST /api/v1/instances/heartbeat`) с сохранением версий приложения, схемы БД, статистики CPU/RAM/задач/файлов и динамическим расчетом статуса здоровья флота (`UP`, `DOWN`, `NEVER`).
  - Управление глобальными мультиязычными объявлениями (`POST /api/v1/announcements` и `/publish`) с таргетингом на клиентов.
  - Отдельное приложение веб-панели `apps/web-cp` (Angular 20 SPA на порту 4300) для администраторов и инженеров платформы.
- **Файлы:** `apps/control-plane/...`, `apps/web-cp/...`, `scripts/dev/test-cp-api.ps1`.
- **Команда проверки:** `mvn test -Dtest=CpAuthServiceTest -pl apps/control-plane` (100% SUCCESS, 3/3 тестов), `powershell scripts/dev/test-cp-api.ps1` (100% SUCCESS).


---

### M16. Динамические атрибуты (ATTR) [❄️ ЗАМОРОЖЕНО 2026-08-30] закрыто и выведено из развития
- **Цель:** Добавление произвольных атрибутов к пользователям, проектам и задачам в `jsonb` с валидацией типов (string, number, boolean, date, select) и GIN-индексацией.
- **DoD:**
  - `MdCustomFieldRepository`, `MdCustomFieldService`, `MdCustomFieldController` (`/api/v1/custom-fields`).
  - Строгая валидация типов данных перед сохранением в `jsonb attributes` (числа, даты, булевы значения, списки опций, обязательные поля).
  - Компонент `ui-custom-fields` для динамического рендеринга на веб-фронтенде.
- **Файлы:** `apps/instance/.../md/service/MdCustomFieldService.java`, `MdCustomFieldRepository.java`, `MdCustomFieldController.java`.
- **Команда проверки:** `mvn test -Dtest=MdCustomFieldServiceTest` (100% SUCCESS, 3/3 тестов), `powershell scripts/dev/test-api.ps1` (Сценарии 5 и 7, 100% SUCCESS).



---

### M17. Полнотекстовый поиск (SEARCH) [❄️ ЗАМОРОЖЕНО 2026-08-30] закрыто и выведено из развития
- **Цель:** Высокопроизводительный поиск Typesense + палитра быстрого поиска Command Palette (`Ctrl+K`) с поддержкой опечаток, Soundex, префиксного поиска и Postgres Fallback.
- **DoD:**
  - Интеграция Typesense (порт 8108, образ 27.1).
  - Схемы коллекций `tasks`, `projects`, `users` с `enable_phonetic`, `num_typos: 2`, `prefix: true`.
  - Фоновая первичная синхронизация и асинхронный индексатор `TypesenseIndexer`.
  - Автоматический Graceful Fallback на PostgreSQL при недоступности движка.
  - Панель Command Palette (`Ctrl + K`) с клавиатурной навигацией (`↑`, `↓`, `Enter`) и локализованными бейджами.
- **Команда проверки:** `mvn test -Dtest=SearchServiceTest` (100% SUCCESS, 2/2 тестов), `powershell scripts/dev/test-api.ps1` (Сценарий 8, 100% SUCCESS).



---

### M18. Исходящие вебхуки (KWH) [❄️ ЗАМОРОЖЕНО 2026-08-30] закрыто и выведено из развития
- **Цель:** Доставка событий во внешние системы через Webhooks с гарантией at-least-once, подписью HMAC-SHA256 (`X-Signature-SHA256`), фоновой outbox-очередью, экспоненциальным backoff и автоматической деактивацией.
- **DoD:**
  - `KwhSubscriptionRepository`, `KwhSubscriptionController`, `KwhWebhookService`: CRUD подписок, генерация криптографических секретных токенов, валидация протоколов (`http://`, `https://`).
  - Очередь `kwh_outbox` с конкурентной выборкой (`FOR UPDATE OF o SKIP LOCKED`).
  - Фоновый worker `KwhOutboxWorker` (@Scheduled): вычисление `HMAC-SHA256` от JSON payload, передача заголовков `X-Signature-SHA256`, `X-Signature-Timestamp`, `X-Event-Type`, экспоненциальный retry ($2^{\text{attempts}} \times 15$s), перевод в `DEAD_LETTER` при превышении лимита попыток, журнал доставок `kwh_logs`.
- **Файлы:** `apps/instance/.../kwh/service/KwhWebhookService.java`, `KwhOutboxWorker.java`, `KwhSubscriptionController.java`, `KwhSubscriptionRepository.java`, `KwhOutboxRepository.java`.
- **Команда проверки:** `mvn test -Dtest=KwhWebhookServiceTest` (100% SUCCESS, 2/2 тестов), `powershell scripts/dev/test-api.ps1` (Сценарий 20, 100% SUCCESS).



---

## Заморозка обслуживающих модулей (решение CEO, 2026-08-30)

Вехи Этапа 1 неравноценны. Часть из них — **несущие**: на них встанут витрины Этапа 2 и
дашборды Этапа 3, и каждая их слабость умножится. Часть — **обслуживающие**: они нужны,
но не создают преимущества и никогда не будут причиной, по которой нас выберут.

Доводить обслуживающие модули «до блеска» — это то же расширение, только вглубь. Поэтому:

| Класс | Вехи | Что с ними делаем |
|---|---|---|
| **Несущие — углубляем** | M3 авторизация, M4 RBAC и скоуп, M8 аудит, M10 API-контракт, M1 экземпляр и миграции, M13 наблюдаемость | пересматриваем по протоколу из шести шагов, доводим до закрытия |
| **Обслуживающие — заморожены** | M5 задачник, M16 динамические поля, M17 поиск, M18 вебхуки | изменения только по дефектам; новые требования отклоняются |
| **Обслуживающие, но с долгом фазы P** | M6 оповещения, M7 файлы | заморожены по функциям; открыты ровно на подключение боевого шлюза и Garage |

Правило простое: **дефект чиним, улучшение отклоняем.** Если требование к замороженному
модулю кажется необходимым — оно сначала проходит через вопрос «приближает ли это Этап 2».

## Что открыто и чего ждёт

| Пункт | Чего ждёт |
|---|---|
| Д-7 — аудитор не может сменить свой пароль | **решения CEO** (рекомендация — вариант «б» из AUDIT-05) |
| FR-NOTIF-5 — боевой SMS | договора с оператором |
| FR-FILE-1 — S3/Garage, FR-SEC-3 — Vault, FR-OBS-* — стек, FR-CP-3/4/8 | стенда фазы P |
| Применение предиката скоупа к остальным спискам | по мере пересмотра модулей (ADR-0013 разд. 4) |
