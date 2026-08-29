# DWH Platform

Платформа для клиентов GreenWhite: административное ядро (пользователи, RBAC, задачник,
оповещения) + хранилище данных (DWH) + дашборды. Поставляется **отдельным экземпляром на
каждого клиента**; флот экземпляров управляется центральным **control plane**.

**Статус на 30.08.2026:** Этап 1 (CMS) — код всех 18 вех написан, сборка зелёная
(**122 теста**, `BUILD SUCCESS`). Закрытыми по требованиям считаются 5 вех; остальные
помечены «частично» с указанием, чего именно ждут — Vault, Garage, стенда фазы P или
договора с оператором связи. Разделение введено осознанно: раньше отчётность заявляла
«100% completed», и это расходилось с фактом (см. [AUDIT-05](docs/audit/AUDIT-05-deep-review.md),
дефект Д-6). Точный статус каждой вехи — [MILESTONES.md](MILESTONES.md).

**До передачи пилотному клиенту остаётся:** боевой SMS-шлюз (SMTP и Telegram уже работают),
Garage/S3 вместо локального хранилища и Vault вместо `.env`.

Начало чтения — [docs/onboarding.md](docs/onboarding.md).

## Архитектура (коротко)

- **Экземпляр клиента:** Spring Boot 4.1 (Java 25 Virtual Threads) + PostgreSQL 18 (+pgvector, OLTP) + Typesense 27.1 (FTS) + Angular 20 (Signals, OnPush). Модульный монолит: чистая архитектура, бизнес-логика в сервисах, в БД — целостность и секционированный аудит. Без multi-tenancy: изоляция физическая (Database-per-Tenant). Профили ресурсов S/M/L.
- **Control plane (наш, один):** реестр клиентов и инстансов, инвентарь версий, лицензии, глобальные объявления, heartbeat-мониторинг. Связь — только исходящая от экземпляра.
- **Инфраструктура и оркестрация:** Docker Compose Fleet + NGINX Hardened Reverse Proxy. Обновления по кольцам с авто-проверкой здоровья (`SchemaVersionGate`). Миграции — отдельным шагом по правилу expand/contract.
- **Модель предметной области:** унаследована от платформы Biruni (формы/действия/роли, матрица 43 прав, эффективные права, аудит), архитектура переписана под современные cloud-native стандарты.

## Системные требования (Requirements)

- **Java Development Kit:** JDK 25 LTS (с поддержкой Virtual Threads)
- **Node.js & npm:** Node.js 22.x / 24.x LTS, npm 10+
- **Docker & Docker Compose:** Docker Engine 24+, Compose v2+
- **СУБД (для локального запуска без Docker):** PostgreSQL 18+

---

## Быстрый старт (Quickstart)

### Вариант 1. Запуск через Docker Compose (Рекомендуемый)

```bash
# 1. Клонирование и настройка переменных окружения
cp .env.example .env

# 2. Выполнение миграций схемы БД (отдельным шагом)
docker compose run --rm migrate
docker compose run --rm migrate-cp

# 3. Запуск всех сервисов (Backend + Frontend + Control Plane + PostgreSQL)
docker compose up -d
```

### Вариант 2. Локальная разработка (Backend + Frontend)

```bash
# 1. Запуск базы данных PostgreSQL
docker compose up -d postgres

# 2. Сборка и прогон тестов бэкенда (57 тестов)
mvn clean test

# 3. Применение миграций схемы
mvn -pl apps/instance -Dspring.profiles.active=migrate spring-boot:run

# 4. Запуск бэкенда инстанса
mvn -pl apps/instance spring-boot:run

# 5. Запуск фронтенда CMS (в отдельном терминале)
cd apps/web-instance
npm install
npm start
```

### Точки доступа к сервисам:

| Сервис / Интерфейс | Адрес | Учётные данные по умолчанию |
| :--- | :--- | :--- |
| **CMS Интерфейс экземпляра** | http://localhost:4200 | `admin` / `Admin123!` (или `ADMIN_PASSWORD` из `.env`) |
| **Панель управления флотом (CP)** | http://localhost:4300 | `cp_admin` / `CPAdmin123!` (из `.env`) |
| **REST API экземпляра** | http://localhost:8080 | Сессионная Cookie `DWH_SESSION` или Bearer Token |
| **REST API Control Plane** | http://localhost:8082 | Сессионная Cookie `DWH_CP_SESSION` |
| **Actuator Health Check** | http://localhost:8080/actuator/health | `{"status":"UP"}` |

---

## Устранение неполадок (Troubleshooting)

1. **Ошибка `SchemaVersionGate: Pending migrations detected`**:
   - Приложение блокирует запуск, если схема БД не актуальна.
   - **Решение:** Запустите `mvn -pl apps/instance -Dspring.profiles.active=migrate spring-boot:run` или `docker compose run --rm migrate`.
2. **Ошибка `CSRF Token Invalid` (403 Forbidden)**:
   - Мутирующие HTTP-запросы (POST, PUT, DELETE, PATCH) с сессионной cookie требуют передачи заголовка `X-XSRF-TOKEN` (со значением из cookie `XSRF-TOKEN`).
   - Запросы с заголовком `Authorization: Bearer <token>` освобождены от CSRF-проверки.
3. **Ошибка `Rate limit exceeded` (429 Too Many Requests)**:
   - Превышен лимит запросов с IP-адреса или токена. В заголовке ответа `Retry-After` указано время ожидания в секундах.
4. **Конфликт чексумм Flyway миграций**:
   - При модификации уже примененных миграций на локальной базе: сбросьте базу `docker exec dwh-postgres psql -U postgres -c "DROP DATABASE dwh_instance; CREATE DATABASE dwh_instance;"` и выполните миграцию заново.

---

## Навигация по проекту

- **[STATS_MAP.md](STATS_MAP.md)** — Карта статистик проекта (файлы, строки кода, зависимости, тесты).
- **[MILESTONES.md](MILESTONES.md)** — Каталог вех и дорожная карта (M1 → M18).
- **[REPORT.md](REPORT.md)** — Журнал работ и отчётов.

## Документация

| Документ | Что внутри |
|---|---|
| [ТЗ-01: CMS](docs/trd/TRD-01-cms.md) | Требования Этапа 1: экземпляр + control plane; разд. 6 — DDL-спецификация, разд. 8.2 — матрица результатов |
| [ТЗ-02: UI/UX](docs/trd/TRD-02-uiux.md) | Корпоративный минимализм: токены, паттерны, каталог экранов, SSE-реконнект, адаптивность |
| [ТЗ-03: Сценарии](docs/trd/TRD-03-flows.md) | 13 пользовательских flows с ошибочными ветками — динамика системы |
| [ТЗ-04: API](docs/trd/TRD-04-api.md) | Полная спецификация ядра Этапа 1 (M1–M4 + Control Plane v2.0): эндпоинты, SSE, каталог ошибок |
| [CODE_STYLE](CODE_STYLE.md) | Стандарты разработки: Java 25, Spring Boot 4.1, транзакции, SQL, логи без ПДн, Angular 22 |
| [Стратегия тестирования](docs/guidelines/testing-strategy.md) | Пирамида тестов: ArchUnit правила, Testcontainers (PG18 + S3), контрактные тесты SPI |
| [Миграции БД](docs/guidelines/database-migrations.md) | Регламент безопасных миграций Flyway и шаблоны expand/contract в PostgreSQL 18 |
| [Структура монорепо](docs/architecture/monorepo-structure.md) | Спецификация структуры Maven multi-module и Angular workspace |
| [Biruni/Smartup Стандарты](docs/architecture/biruni-smartup-conventions.md) | Наследование опыта: префиксы модулей (md, kauth, ms, mf), именование классов, *Pref константы |
| [Создание новых модулей](docs/guidelines/module-development-guide.md) | Пошаговый алгоритм добавления нового бизнес-модуля (MDK): DDL, *Pref, сервисы, RBAC, UI |
| [Runbooks](docs/runbooks/) | Эксплуатационные регламенты: отказ узла (RB-01), Vault unseal (RB-02), ротация ключей (RB-03), сбои миграций (RB-04) |
| [AUDIT-01](docs/audit/AUDIT-01-design-review.md) | Аудит проектных решений и статус устранения находок |
| [AUDIT-02](docs/audit/AUDIT-02-implementation-review.md) | Ревизия реализации 28.08: реестр расхождений, фазы R/P/F |
| [План ремедиации](docs/plan/remediation-plan.md) | Фазы R (санация кода) → P (платформа) → F (достройка) |
| [ADR-0001](docs/adr/ADR-0001-architecture-model.md) | Где живёт логика: приложение, не БД (анализ модели Biruni) |
| [ADR-0002](docs/adr/ADR-0002-backend-stack.md) | Стек и фиксация версий + политика обновлений |
| [ADR-0003](docs/adr/ADR-0003-tenancy-rbac.md) | RBAC и аудит (раздел изоляции заменён ADR-0004) |
| [ADR-0004](docs/adr/ADR-0004-deployment-model.md) | Экземпляр на клиента + control plane |
| [ADR-0005](docs/adr/ADR-0005-ai-ml-readiness.md) | Принципы готовности к AI/ML |
| [ADR-0006](docs/adr/ADR-0006-modular-monolith.md) | Модульный монолит: границы модулей и инварианты |
| [ADR-0007](docs/adr/ADR-0007-fleet-strategy.md) | Флот: Nomad, миграции, кольца развёртывания, бэкапы |
| [ADR-0008](docs/adr/ADR-0008-security-baseline.md) | Базовые требования безопасности |
| [ADR-0009](docs/adr/ADR-0009-observability.md) | Наблюдаемость флота: логи, метрики, трейсы, алертинг |
| [ADR-0010](docs/adr/ADR-0010-resilience-tiers.md) | Отказоустойчивость: тарифы доступности, кворум платформы |
| [ADR-0011](docs/adr/ADR-0011-provider-spi.md) | Механизм провайдеров: Provider SPI, выбор конфигурацией |
| [ADR-0012](docs/adr/ADR-0012-ui-foundation.md) | UI-фундамент: Angular Material + CDK, строгая тема из токенов |
| [ADR-0013](docs/adr/ADR-0013-data-scope.md) | Скоуп данных: оргструктура, правило видимости, доступ к строкам |
| [План M0](docs/plan/M0-plan.md) | Декомпозиция первой вехи: спайк, потоки, календарь |
| [Онбординг](docs/onboarding.md) | Порядок чтения: 2 часа — и ты в контексте |
| **Эксплуатация** | |
| [Развёртывание](docs/ops/deployment-guide.md) | Prerequisites Checklist и пошаговый деплой экземпляра |
| [Operations Runbook](docs/ops/operations-runbook.md) | Ежедневный контроль, диагностика, матрица эскалации |
| [Откат релиза](docs/ops/rollback.md) | Откат образа и восстановление из бэкапа |
| [Обслуживание](docs/ops/maintenance-guide.md) | Обновления, патчи, бэкапы, ротация секретов |
| [Архитектура для эксплуатации](docs/ops/architecture-overview.md) | Состав, потоки данных, порты, состояние |
| [Launch Checklist](docs/ops/production-launch-checklist.md) | Критерии go/no-go |
| [AUDIT-04](docs/audit/AUDIT-04-devops-readiness.md) | DevOps-аудит: PARTIALLY READY, gap-анализ |
| [CONTRIBUTING](CONTRIBUTING.md) | Процесс: ветки/PR/ревью, DoR/DoD (действует со старта разработки) |

## Этапы

1. **CMS** — авторизация, пользователи, RBAC, задачник, оповещения, control plane (ТЗ-01).
2. **DWH** — ELT из источников (в т.ч. Oracle Smartup5x), слои raw → core → marts (ТЗ-02, будет).
3. **Дашборды** — динамическая аналитика поверх DWH (ТЗ-03, будет).

## Лицензия

Проприетарное программное обеспечение. © 2026 Smartup. Все права защищены.
См. [LICENSE](LICENSE). Использование — только по письменному соглашению с правообладателем.
