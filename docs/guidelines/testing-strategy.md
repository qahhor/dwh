# Стратегия тестирования SmartupCMS

**Версия:** 2.0

**Обновлено:** 2026-09-03

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
Push-Location apps/web; npm test; npm run typecheck; npm run build; Pop-Location
./scripts/architecture/test-unified-boundaries.ps1
./scripts/docs/test-public-docs.ps1
./scripts/docs/test-repository-hygiene.ps1
./scripts/release/verify-release.ps1
./scripts/prod/test-release-config.ps1
./scripts/prod/test-backup-status.ps1
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

## Соответствие CI

CI выполняет следующие независимые jobs:

- **backend:** `mvn -B verify`, включая unit/integration/ArchUnit, затем
  формирование CycloneDX SBOM;
- **frontend:** `npm ci`, unit tests, typecheck и production build из
  `apps/web`;
- **release config:** unified architecture, public docs, repository hygiene,
  release supply-chain, production Compose и encrypted-backup contracts, а
  также fail-closed deploy test;
- **E2E:** ephemeral credentials, Compose build, runtime-image scan, отдельный
  migrate, healthy startup, public smoke и Playwright Chromium;
- **security:** Gitleaks по истории Git и Trivy по зависимостям.

Required job с ошибкой должен блокировать merge. Исключение теста, понижение
severity или обновление snapshot требует review с явным обоснованием и ссылкой
на затронутый критерий ТЗ.

Текущий `.github/workflows/ci.yml` не запускает no-default-egress observation,
изолированный restore drill или lifecycle/recovery на целевом S3/R2. Наличие
unit/integration/config contract для этих функций не является evidence их
production-приёмки.

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
