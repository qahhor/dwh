# Журнал работ и отчётов (REPORT.md)

Документ ведется в режиме реального времени. Фиксирует прогресс, команды, результаты проверок и открытые вопросы.

---

## [2026-08-29 13:45] Этап 0. Bootstrap, аудит и сбор карты статистик

### Что сделано:
- Выполнен детальный аудит монорепозитория, проверен статус веток Git (основная ветка `main`, удаленные неактуальные ветки вычищены, `origin/main` синхронизирован).
- Проверена работоспособность полной цепочки сборки:
  - Java 25 LTS / Spring Boot 4.1.1.
  - PostgreSQL 18.6 в Docker / Testcontainers.
  - Angular 22.1.4 (приложения `web-instance` и `web-cp`).
- Запущен полный набор из **57 тестов** (ArchUnit, Security CSRF/RateLimit, RBAC Integration, User Blocking Invariant, SSE Registry, Outbox Workers, Flyway Migrations) — 100% SUCCESS.
- Создан документ **`STATS_MAP.md`** со структурированной картой метрик, зависимостей, тестового покрытия и архитектурных схем.
- Подготовлен каталог этапов **`MILESTONES.md`** для последовательного помодульного закрытия (M1 → M18).

### Команды:
```bash
# 1. Запуск полного набора тестов бэкенда
mvn test

# 2. Подсчет статистики исходного кода
powershell -ExecutionPolicy Bypass -File scripts/calc-stats.ps1

# 3. Сборка фронтенд-приложений
cd apps/web-instance && npm run build
cd apps/web-cp && npm run build
```

### Результат:
- **Backend:** `Reactor Summary: 5/5 SUCCESS` (57 тестов успешно).
- **Frontend `web-instance`:** Production bundle generation complete (110.8 kB Gzip).
- **Frontend `web-cp`:** Production bundle generation complete (62.4 kB Gzip).
- **База данных:** PostgreSQL 18 контейнер активен, миграции Flyway V001–V004 проверены.

### Следующие шаги:
- Переход к модулю **M2 (Пользователи и профили / USR)**.

---

## [2026-08-29 13:52] Этап M2. Пользователи и профили (USR)

### Что сделано:
- **FR-USR-2 (Сложность паролей и защита от слабых паролей)**:
  - Создан компонент `PasswordValidator`: валидация минимальной длины (10 символов), проверка по словарю скомпрометированных паролей (blacklist), запрет использования логина в качестве пароля.
  - Интегрирован в `MdUserService.createUser`, `MdUserService.changePassword`.
- **FR-USR-1 (Уникальность телефона)**:
  - В `MdUserRepository` и `MdUserService` добавлен метод `existsByPhone` и валидация уникальности номеров телефонов среди активных пользователей при создании и редактировании.
- **FR-USR-7 (Смена пароля с проверкой старого)**:
  - Реализован метод `changePassword(userId, oldPassword, newPassword)` с верификацией текущего хеша через `PasswordHasher.verifyPassword` и валидацией сложности.
  - Добавлен REST-эндпоинт `POST /api/v1/iam/users/me/password` с аннотацией `@RequiresPermission(form = "iam.profile", action = "update")`.
- **FR-USR-8 (Удаление / Анонимизация пользователя)**:
  - Реализована безопасная анонимизация `anonymizeUser`: затирание ПДн (`name = 'Deleted User ' || id`, `email = 'deleted_' || id || '@anonymized.local'`, `phone = null`), перевод в `state = 'P'`, отзыв всех активных сессий и токенов (`UserSessionInvalidator.invalidateAllAccess`).
  - Защищен системный администратор `admin` от удаления (I-IAM-1).
  - Добавлена миграция `V005__user_delete_and_profile_actions.sql` с регистрацией действия `delete` для формы `iam.users` и выдачей прав роли `admin`.
  - Добавлен эндпоинт `DELETE /api/v1/iam/users/{id}`.
- **Frontend (UI)**:
  - В таблицу пользователей `users.component.ts` добавлена кнопка и модальное подтверждение удаления (анонимизации) пользователя.
  - Обновлен плейсхолдер пароля ("Минимум 10 символов").
- **Тесты**:
  - Написаны и запущены 8 unit-тестов в `MdUserServiceTest`, проверяющие инварианты I-IAM-1, длину пароля, словарные проверки, уникальность телефона, смену пароля и анонимизацию.
  - Общее количество тестов в монорепозитории выросло с 57 до **61 теста** (100% SUCCESS).

### Команды:
```bash
# Прогон тестов M2
mvn test -Dtest=MdUserServiceTest,UserBlockingInvariantTest,RbacSystemRolesIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false

# Полный регрессионный прогон
mvn test

# Сборка UI
cd apps/web-instance && npm run build
```

### Результат:
- **Backend:** 61/61 тестов успешно (`BUILD SUCCESS`).
- **Frontend:** Сборка `web-instance` собрана без предупреждений (110.8 kB Gzip).

### Следующие шаги:
- Переход к модулю **M3 (Авторизация и сессии / AUTH)**:
  - Эндпоинт управления сессиями всех пользователей для администратора.
  - Планировщик фонового закрытия неактивных сессий (12 ч).
  - Проверка анти-brute-force логики и задержек.

### Риски и технический долг:
- Интеграция внешних SMS/Email провайдеров для 2FA OTP требует настройки боевых ключей в фазе F.

