# Структура монорепозитория SmartupCMS

**Версия:** 2.0

**Обновлено:** 2026-09-03

**Основание:** [каноническое ТЗ](../technical-specification.md),
[ADR-0014](../adr/ADR-0014-unified-open-source-runtime.md) и фактический корневой
`pom.xml`.

Этот документ описывает текущую структуру единого open-source runtime. Один
репозиторий содержит сервер, браузерное приложение, общие библиотеки,
Compose-поставку, автоматические проверки и документацию.

## Карта верхнего уровня

```text
apps/server/              Spring Boot modular monolith
apps/web/                 Angular SPA and NGINX image
libs/core-types/          shared value/error contracts
libs/platform-common/     shared backend infrastructure
libs/provider-spi/        storage/mail/SMS/messenger interfaces
deploy/compose/           production Compose bundle
deploy/images/            hardened image extensions
deploy/nginx/             production reverse proxy
e2e/                      Playwright and configuration/security tests
scripts/                  architecture, docs, dev, prod, release, security gates
docs/                     requirements, ADRs, engineering and operations docs
audit/                    dated evidence, never normative requirements
graphify-out/              current generated navigation outputs only
```

Корневой Maven reactor собирает `libs/core-types`, `libs/platform-common`,
`libs/provider-spi` и `apps/server`. Angular-приложение собирается из
`apps/web`. Каталог `audit/` хранит датированные наблюдения и доказательства;
он не изменяет требования ТЗ или текущие архитектурные решения. Содержимое
`graphify-out/` служит только сгенерированной навигацией по текущему состоянию
репозитория.

## Границы и направление зависимостей

1. Функциональные модули сервера могут зависеть от общего ядра,
   `platform-common` и контрактов `provider-spi`.
2. Общие библиотеки не могут зависеть от модулей приложения. В частности,
   контракты провайдеров не импортируют реализации из сервера.
3. Связь между функциональными модулями проходит через явные публичные
   интерфейсы или события, а не через внутренние пакеты соседнего модуля.
4. Браузер обращается только к API сервера через web origin; он не обращается
   напрямую к PostgreSQL, Typesense или внутренним management endpoints.
5. PostgreSQL является источником истины. Typesense содержит производный,
   перестраиваемый индекс и никогда не принимает решения об авторизации.

Эти правила проверяются Maven/ArchUnit и
[`scripts/architecture/test-unified-boundaries.ps1`](../../scripts/architecture/test-unified-boundaries.ps1).

## Исполняемая модель

Поддерживаемая поставка использует Docker Compose. Обязательный путь запуска
состоит из PostgreSQL, отдельного шага `migrate`, сервера, web/NGINX и
Typesense. ClamAV и зашифрованный backup включаются согласно production
конфигурации. Схема базы должна быть готова до старта сервера, а внешней
публичной точкой приложения остаётся только web origin.
