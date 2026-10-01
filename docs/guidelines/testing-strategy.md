# Стратегия тестирования SmartupCMS

**Версия:** 2.1

**Обновлено:** 2026-10-01

**Основание:** текущий CI и критерии `AC-01..12` из
[канонического ТЗ](../technical-specification.md).

Качество релиза доказывается несколькими слоями. Прохождение одного слоя не
заменяет другой: unit test не доказывает production Compose, а успешный E2E не
заменяет проверку архитектурной границы или supply chain.

## Обязательные локальные gates

Из корня репозитория в PowerShell выполните эти команды без изменения их
семантики:

```powershell
mvn -B verify
./scripts/quality/test-no-skipped-tests.ps1
./scripts/quality/test-coverage-floors.ps1
Push-Location apps/web; npm run lint; npm test; npm run typecheck; npm run build; Pop-Location
./scripts/architecture/test-unified-boundaries.ps1
./scripts/docs/test-public-docs.ps1
./scripts/docs/test-repository-hygiene.ps1
./scripts/release/verify-release.ps1
./scripts/prod/test-release-config.ps1
./scripts/prod/test-backup-status.ps1
./scripts/acceptance/test-managed-acceptance.ps1
Push-Location e2e; npm run test:config; npm run typecheck; npm run test:artifact-security; npm test; Pop-Location
```

Перед первым npm-запуском зависимости устанавливаются через `npm ci` в
соответствующем каталоге. Browser E2E (`e2e` `npm test`) требует Compose-стек,
подготовленный по root README. Ненулевой код любого обязательного gate блокирует
merge или release.

## Автоматизированные слои и критерии приёмки

| Слой | Что доказывает автоматизированный baseline | Критерии ТЗ |
|---|---|---|
| Unit | Изолированные инварианты backend и Angular-компонентов, обработка ошибок и негативные ветви. | `AC-01`, `AC-02`, часть `AC-07` и `AC-10` |
| Integration | Spring/SQL/provider поведение на реальных границах, вся цепочка Flyway, upgrade данных, portable S3-compatible storage и authorization integration. | `AC-01`, `AC-04`, `AC-07`, часть `AC-09` и `AC-10` |
| Architecture | Maven/ArchUnit и unified-boundary правила проверяют состав reactor/runtime и настроенные package-level ограничения. Они не доказывают каждое dependency edge или каждый путь browser-запроса. | `AC-03`, поддерживает `AC-10` |
| Configuration | Рендеринг Compose/NGINX, fail-closed deploy, schema readiness и backup-status contracts. | `AC-03`, часть `AC-05` и `AC-08` |
| Security и supply chain | Secret/artifact controls, upload/scanner отказ, negative authorization/IDOR, dependency/image scan и release supply-chain configuration contracts. | `AC-03`, `AC-07`, `AC-10`, часть `AC-11` |
| E2E | Чистый migrate/start и критические Chromium journeys через публичный web origin. | `AC-05`, `AC-06` |

Эта таблица описывает автоматизированный baseline, а не полное release
acceptance. Все `AC-01..12` требуют evidence на точном release commit/image
digest; часть критериев закрывается только release/operator проверками ниже.
Требование обращаться из browser только к server API подтверждается review и
сетевыми/E2E-сценариями через публичный web origin; отдельного статического gate,
гарантирующего это для каждого вызова, в текущем CI нет.

## Покрытие тестами (план 10/10, пункт 1.4)

- **Модуль Maven.** `jacoco-maven-plugin` в каждом модуле пишет отчёт в
  `target/site/jacoco` и в фазе `verify` проверяет порог строк и ветвлений
  (`coverage.line.minimum`, `coverage.branch.minimum` в `pom.xml` модуля).
- **Бизнес-модуль сервера.** Модуль (`md`, `kauth`, `ms.task` и т. д.)
  занимает несколько пакетов, поэтому его порог хранится в
  `apps/server/coverage-floors.csv` и проверяется
  `scripts/quality/test-coverage-floors.ps1`; новый модуль без порога — ошибка.
- **Изменения PR.** `diff-cover` сравнивает PR с базовой веткой: изменённые
  строки сервера покрыты не меньше чем на 80 %.
- **Ни один тест не пропущен.** `scripts/quality/test-no-skipped-tests.ps1`
  падает, если в отчётах surefire есть пропуск: Testcontainers без Docker
  больше не проходят молча.

Пороги равны покрытию на момент введения (2026-09-27) и только поднимаются,
к 80 % по каждому модулю. Когда покрытие выросло, порог поднимают в том же PR.

## Правила сервера фазы 3 (план 10/10)

Каждое правило из
[руководства по модулям](module-development-guide.md#серверные-правила)
проверяет тест в `mvn -B verify`; список исключений в тесте только сокращается.

| Тест | Что не пропускает |
|---|---|
| `ErrorModelTest`, `ErrorTextsTest` | исключение запроса не от `ApiException`; текст вместо ключа; ключ, которого нет в ru, uz или en ([ADR-0021](../adr/ADR-0021-error-model.md)) |
| `OpenApiContractTest` | обработчик без описания; `docs/api/openapi.json`, отставший от кода ([ADR-0022](../adr/ADR-0022-openapi-from-code.md)) |
| `ResponseStatusDeclaredTest` | 201/202/204 без `@ResponseStatus` ([ADR-0023](../adr/ADR-0023-uniform-rest.md)) |
| `ChangesNameTheirRevisionTest` | `PUT`/`PATCH` записи без `If-Match` или ревизии в теле ([ADR-0024](../adr/ADR-0024-optimistic-locking.md)) |
| `CollectionsArePagedTest` | `GET` с целым растущим списком (план 10/10, пункт 3.5) |
| `NoSwallowedErrorsTest` | `catch`, который не пробрасывает, не пишет в лог и не использует пойманное (пункт 3.11) |
| `CommentLanguageTest` | новый Java-файл с комментарием по-русски (пункт 3.14) |
| `ModuleBoundariesTest` | контроллер, видящий `repository`; обращение к соседнему модулю мимо `service`/`api` |
| `MigrationLintTest`, `MigrationFileRulesTest`, `MigrationManifestTest` | нарушение [ADR-0020](../adr/ADR-0020-database-naming.md), DDL вместе с данными, изменённая выпущенная миграция |

Рядом — пороги покрытия бизнес-модулей (`scripts/quality/test-coverage-floors.ps1`),
job `api contract` (`scripts/api/test-api-contract.ps1`: Spectral, свежесть
типов веба, openapi-diff) и в вебе `npm run api:audit`. Генератор модулей
проверяет `scripts/dev/test-create-module.ps1`: результат генератора
собирается, проходит эти тесты и стартует на встроенном PostgreSQL.

## Соответствие CI

CI выполняет следующие независимые jobs:

- **backend:** сначала формат и стиль (`spotless:check`, `checkstyle:check`), затем
  `mvn -B verify` с Error Prone и NullAway в компиляции, включая unit/integration/ArchUnit и пороги
  покрытия JaCoCo, затем проверки «ни один тест не пропущен», пороги покрытия
  бизнес-модулей, покрытие изменённых строк PR (не ниже 80 %) и формирование
  CycloneDX SBOM;
- **frontend:** `npm ci`, lint (ESLint — файл подавлений пуст, Stylelint,
  Prettier), аудиты контраста, ARIA, i18n (включая синхронность
  `packaged-russian.ts` с `ru.json`), устаревших и несуществующих вызовов API (`api:audit`),
  порядка членов классов (`signals:audit`) и отсутствия сторонних ресурсов,
  unit tests с покрытием, typecheck, production build и
  проверка доступности `npm run test:a11y` из `e2e`;
- **api contract:** `scripts/api/test-api-contract.ps1` — Spectral, типы веба
  из текущего описания, openapi-diff против базовой ветки (метка
  `api-breaking` или трейлер `Api-Breaking:` разрешает объявленный слом);
- **release config:** unified architecture, public docs, repository hygiene,
  release supply-chain, production Compose, encrypted-backup и managed
  acceptance contracts, а также fail-closed deploy test;
- **E2E:** два шарда на двух одноразовых стендах: ephemeral credentials,
  Compose build, runtime-image scan (в первом шарде), отдельный migrate,
  healthy startup, public smoke и Playwright Chromium через
  `scripts/dev/test-e2e.ps1 -Shard N/2`;
- **security:** Gitleaks по истории Git и Trivy по зависимостям.

Новый push в PR отменяет прогон предыдущего; у каждого job есть
`timeout-minutes`.

Required job с ошибкой должен блокировать merge. Исключение теста, понижение
severity или обновление snapshot требует review с явным обоснованием и ссылкой
на затронутый критерий ТЗ.

**Нестабильные тесты (план 10/10, пункт 1.8).** Повторов нет ни в Playwright,
ни в surefire: повтор прячет нестабильность. Тест, который падает то так, то
так, заносится в `e2e/quarantine.json` с причиной, владельцем и сроком;
основной набор его пропускает, а отдельный неблокирующий шаг CI запускает только
тесты из карантина, чтобы их состояние оставалось видно.

**Nightly** (`.github/workflows/nightly.yml`, 03:30 по Ташкенту): учения
готовности релиза (`scripts/release/test-final-readiness.ps1`: поиск,
конкурентный доступ к БД, ограничения нагрузки, контракты репозитория),
обновление production Compose с резервной копией на V018, наблюдение
no-default-egress, live API smoke на чистом стенде, проверка генератора модулей
(`scripts/dev/test-create-module.ps1`) и Trivy по свежим advisory. `scripts/docs/test-repository-hygiene.ps1` падает, если какой-то
`scripts/**/test-*` не запускает ни один workflow.

`.github/workflows/ci.yml` и nightly не запускают изолированный restore drill
или lifecycle/recovery на целевом S3/R2. Наличие unit/integration/config
contract для этих функций не является evidence их production-приёмки.

## Release/operator evidence сверх текущего CI

Для `AC-03` выполните фактический standalone gate наблюдения исходящего трафика
на disposable Compose project:

```powershell
./scripts/security/test-no-default-egress.ps1
```

Скрипт строит отдельный стек, наблюдает network traffic не менее 65 секунд и
удаляет созданные им containers/network/volumes. Его exit code и secret-safe log
включаются в release evidence; этот gate существует в репозитории, но сейчас не
включён в CI workflow.

Для `AC-08` выполните изолированный restore drill по
[maintenance guide](../ops/maintenance-guide.md): отдельные `PROJECT_NAME`, env,
PostgreSQL volume и object-storage location; проверенный encrypted backup и
SHA-256; restore; сверка схемы, representative data/audit counts и object
consistency. Evidence фиксирует archive timestamp, checksum, release/image
digests, начало/окончание и измеренные RPO/RTO. Backup-status contract или
успешное создание архива не заменяет restore.

Для `AC-09` выполните lifecycle на фактическом target S3/R2 bucket/prefix:
upload, byte-for-byte download, existence check, удаление первой и последней
ссылки, физическое delete и отдельное object recovery. Запишите target
endpoint class/region без credentials, object checksums и результат recovery.
`S3StorageProviderIntegrationTest` с disposable S3-compatible service доказывает
portable baseline, но не принимает конкретный production provider.

Для `AC-11` release owner проверяет опубликованные digests, signatures,
provenance, SBOM и checksums, а не только структуру release scripts. `AC-12` не
автоматизируется целиком: до production для каждой установки должны быть
назначены SLO, privacy/retention, incident, RPO/RTO, domain, region и rollback
owners.

## Требования к тестам и evidence

1. Исправление дефекта получает regression test, воспроизводящий исходный сбой.
2. Авторизационная функция покрывает разрешённую и запрещённую роли, прямой
   запрос по чужому ID и отсутствие доверия к скрытию элемента в UI.
3. Миграция проверяется на пустой и upgrade базе, включая повторный запуск и
   `flyway_schema_history`.
4. Внешний provider тестируется контрактом SPI; production integration test не
   публикует credentials или customer data.
5. Failure artifacts проходят `npm run test:artifact-security`; логи, отчёты и
   screenshots не содержат пароли, tokens, cookies или содержимое `.env`.
6. Release evidence фиксирует commit SHA, image digest, версии инструментов,
   точные команды, exit codes и ссылки на сохранённые безопасные artifacts.
