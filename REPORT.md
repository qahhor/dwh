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
- Переход к модулю **M1 (Экземпляр и инициализация / INST)**:
  - Проверка инвариантов `SchemaVersionGate`, `InstanceBootstrap`, `HeartbeatSenderWorker`.
  - Доработка валидации лицензий и grace-периода (INST-4).
  - Верификация unit/integration тестов модуля M1.

### Риски и технический долг:
- `license_token` в `md_instance_info` содержит заглушку `UNLICENSED` до внедрения полноценной подписи через Vault Transit в фазе P.
- Допущение (Assumption): в соответствии с `AUDIT-02` и решением CEO, Typesense отложен до появления высоконагруженных профилей M/L; поиск по умолчанию работает на `pg_trgm`.
