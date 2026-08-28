# DWH Platform

Платформа для клиентов GreenWhite: административное ядро (пользователи, RBAC, задачник,
оповещения) + хранилище данных (DWH) + дашборды. Поставляется **отдельным экземпляром на
каждого клиента**; флот экземпляров управляется центральным **control plane**.

**Статус:** ТЗ утверждено (v1.1); реализация ядра создана 28.08.2026, идёт **фаза ремедиации R**
(приведение к утверждённым версиям и требованиям безопасности) — см.
[AUDIT-02](docs/audit/AUDIT-02-implementation-review.md) и
[план ремедиации](docs/plan/remediation-plan.md). Объём заморожен: «углублять, не расширять»
(CEO, 28.08). Начало чтения — [docs/onboarding.md](docs/onboarding.md).

## Архитектура (коротко)

- **Экземпляр клиента:** Spring Boot 4.1 (Java 25 LTS) + PostgreSQL 18 (+pgvector, OLTP и DWH)
  + Garage (S3) + Angular 22. Модульный монолит: бизнес-логика в приложении, в БД — целостность
  и аудит. Без multi-tenancy: изоляция физическая. Профили ресурсов S/M/L.
- **Control plane (наш, один):** реестр клиентов, инвентарь версий, лицензии, объявления,
  центральный мониторинг. Связь — только исходящая от экземпляра.
- **Флот:** Nomad + Consul + Vault. Обновления — по кольцам R0 → R1 → R2 с canary
  и автоматическим откатом. Миграции — отдельным шагом, по правилу expand/contract.
- Модель предметной области унаследована от платформы Biruni (формы/действия/роли,
  эффективные права, аудит), механизм — переписан под PostgreSQL и приложение-центричную модель.

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
| [План M0](docs/plan/M0-plan.md) | Декомпозиция первой вехи: спайк, потоки, календарь |
| [Онбординг](docs/onboarding.md) | Порядок чтения: 2 часа — и ты в контексте |
| [CONTRIBUTING](CONTRIBUTING.md) | Процесс: ветки/PR/ревью, DoR/DoD (действует со старта разработки) |

## Этапы

1. **CMS** — авторизация, пользователи, RBAC, задачник, оповещения, control plane (ТЗ-01).
2. **DWH** — ELT из источников (в т.ч. Oracle Smartup5x), слои raw → core → marts (ТЗ-02, будет).
3. **Дашборды** — динамическая аналитика поверх DWH (ТЗ-03, будет).

## Лицензия

Проприетарное программное обеспечение. © 2026 Smartup. Все права защищены.
См. [LICENSE](LICENSE). Использование — только по письменному соглашению с правообладателем.
