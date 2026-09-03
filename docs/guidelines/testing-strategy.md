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

## Слои и критерии приёмки

| Слой | Что доказывает | Критерии ТЗ |
|---|---|---|
| Unit | Изолированные инварианты backend и Angular-компонентов, обработка ошибок и негативные ветви. | `AC-01`, `AC-02`, часть `AC-07` и `AC-10` |
| Integration | Spring/SQL/provider поведение на реальных границах, вся цепочка Flyway, upgrade данных, storage и authorization integration. | `AC-01`, `AC-04`, `AC-07`, `AC-09`, `AC-10` |
| Architecture | Maven/ArchUnit и unified-boundary правила: направление зависимостей, единственный server/web runtime, отсутствие обхода API. | `AC-03`, поддерживает `AC-10` |
| Configuration | Рендеринг Compose/NGINX, fail-closed deploy, schema readiness, backup status и installation-specific входы. | `AC-03`, `AC-05`, `AC-08`, `AC-12` |
| Security и supply chain | Secret/artifact controls, upload/scanner отказ, negative authorization/IDOR, dependency/image scan, SBOM, checksum, signature и provenance. | `AC-03`, `AC-07`, `AC-10`, `AC-11` |
| E2E | Чистый migrate/start и критические Chromium journeys через публичный web origin; smoke, файлы и восстановительные проверки. | `AC-05`, `AC-06`, `AC-08`, `AC-09` |

Все `AC-01..12` требуют evidence на точном release commit/image digest.
`AC-12` не автоматизируется целиком: владелец релиза проверяет, что для каждой
установки назначены SLO, privacy/retention, incident, RPO/RTO, domain, region и
rollback owners.

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
