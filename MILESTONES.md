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

### M5. Мини таск-менеджер (TASK)
- **Цель:** Управление проектами и задачами, инварианты I-T1 (ровно один ответственный) и I-T2 (защита от циклов в дереве задач).
- **DoD:**
  - Проекты, статусы (системные/кастомные, терминальные).
  - Рекурсивный CTE `isDescendantOf` предотвращает зацикливание подзадач.
  - Доменные события `TaskCreatedEvent`, `TaskStatusChangedEvent` публикуются в модуль `ms.notify`.
- **Файлы:** `apps/instance/.../ms/task/service/MsTaskService.java`, `MsProjectService.java`.
- **Команда проверки:** `mvn test -Dtest=MsTaskServiceTest`

---

### M6. Оповещения и события (NOTIF)
- **Цель:** Доставка уведомлений in-app, realtime через SSE (`/api/v1/events`), transactional outbox с `SELECT FOR UPDATE SKIP LOCKED`.
- **DoD:**
  - In-app лента уведомлений со счетчиком непрочитанных.
  - SSE поток событий с поддержкой автореконнекта.
  - Outbox воркер с экспоненциальным backoff и обработкой dead-letter.
- **Файлы:** `apps/instance/.../ms/notify/service/MsNotificationService.java`, `MsSseRegistry.java`, `MsOutboxWorker.java`.
- **Команда проверки:** `mvn test -Dtest=MsSseRegistryTest`

---

### M7. Файловое хранилище (FILE)
- **Цель:** Загрузка, стриминг и хранение файлов с дедупликацией по SHA-256 и проверкой прав доступа.
- **DoD:**
  - Потоковый расчет SHA-256 хеша на лету.
  - Дедупликация идентичных файлов.
  - Блокировка потенциально опасных исполняемых расширений.
- **Файлы:** `apps/instance/.../mf/service/MfFileService.java`.
- **Команда проверки:** `mvn test -Dtest=MfFileServiceTest`

---

### M8. Аудит и безопасность (AUD)
- **Цель:** Неизменяемый партиционированный журнал аудита изменений (`audit_log`) и журнал security-событий (`security_events`).
- **DoD:**
  - Запись старых и новых значений при мутациях бизнес-сущностей.
  - Фиксация событий авторизации, смены паролей, выдачи прав и срабатывания rate limiting.
- **Файлы:** `apps/instance/.../audit/service/AuditLogService.java`.

---

### M9. Настройки и локализация (SET & I18N)
- **Цель:** Иерархические настройки ключ-значение и мультиязычный интерфейс (ru, uz, en).
- **DoD:**
  - Наследование настроек: системные $\rightarrow$ пользовательские.
  - Внешние JSON-словари i18n без захардкоженных строк в UI.
- **Файлы:** `apps/instance/.../md/service/MdSettingService.java`, `apps/web-instance/src/app/core/services/i18n.service.ts`.

---

### M10. API-контракт и идемпотентность (API)
- **Цель:** Стандартизированный REST API с RFC 9457 ProblemDetail, Keyset пагинацией и поддержкой `Idempotency-Key`.
- **DoD:**
  - Обработка заголовка `Idempotency-Key` на мутирующих POST запросах.
  - Keyset-курсоры для списков со сложностью $O(\log N)$.
- **Файлы:** `libs/core-types/src/main/java/com/greenwhite/dwh/core/error/ProblemDetailRecord.java`.

---

### M11. Безопасность и соответствие (SEC)
- **Цель:** Защита от OWASP Top 10, CSRF double-submit, Rate Limiting, безопасные HTTP заголовки, SCA сканирование.
- **DoD:**
  - Все эндпоинты защищены Spring Security.
  - CI проверяет уязвимости через Trivy и Gitleaks.
- **Файлы:** `apps/instance/.../config/security/SecurityConfig.java`, `RateLimitFilter.java`.

---

### M12. Модульность и архитектурные границы (MOD)
- **Цель:** Изоляция подсистем модульного монолита, запрет циклических зависимостей, прямого доступа к чужим репозиториям.
- **DoD:**
  - ArchUnit тесты валидируют направленный ациклический граф (DAG).
- **Файлы:** `apps/instance/src/test/java/com/greenwhite/dwh/instance/architecture/ModularArchitectureTest.java`.
- **Команда проверки:** `mvn test -Dtest=ModularArchitectureTest`

---

### M13. Наблюдаемость флота (OBS)
- **Цель:** Централизованный сбор логов с маскированием ПДн, сквозные `trace_id`, Micrometer метрики.

---

### M14. Провайдеры SPI (PLUG)
- **Цель:** Абстракция внешних сервисов (Storage, Mail, SMS, Messenger) через SPI интерфейсы.

---

### M15. Control Plane и управление флотом (CP)
- **Цель:** Центральный реестр инстансов, прием heartbeat, дашборд флота, публикация объявлений.
- **DoD:**
  - Реестр клиентов и инстансов с отображением версий и статусов.
  - Веб-панель `apps/web-cp` для администраторов платформы.
- **Файлы:** `apps/control-plane/...`, `apps/web-cp/...`.

---

### M16. Динамические атрибуты (ATTR)
- **Цель:** Добавление произвольных атрибутов к пользователям, проектам и задачам в `jsonb` с валидацией типов и GIN-индексацией.
- **DoD:**
  - Валидация типов (string, number, boolean, date, select).
  - Компонент `ui-custom-fields` для динамического рендеринга.
- **Файлы:** `apps/instance/.../md/service/MdCustomFieldService.java`.
- **Команда проверки:** `mvn test -Dtest=MdCustomFieldServiceTest`

---

### M17. Полнотекстовый поиск (SEARCH)
- **Цель:** Палитра быстрого поиска Command Palette (`Ctrl+K`) с откликом < 50ms на `pg_trgm`.
- **DoD:**
  - Параллельный поиск по пользователям, задачам и проектам.
- **Файлы:** `apps/instance/.../search/service/SearchService.java`.
- **Команда проверки:** `mvn test -Dtest=SearchServiceTest`

---

### M18. Исходящие вебхуки (KWH)
- **Цель:** Подписка на доменные события, доставка через Outbox с подписью HMAC-SHA256 (`X-Signature-SHA256`).
- **DoD:**
  - Валидация URL подписок (защита от SSRF).
  - Outbox воркер с автоматическими повторами.
- **Файлы:** `apps/instance/.../kwh/service/KwhWebhookService.java`, `KwhOutboxWorker.java`.
- **Команда проверки:** `mvn test -Dtest=KwhWebhookServiceTest`
