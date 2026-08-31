# Express Health Check — DWH Platform

**Дата:** 2026-08-31

**Base commit:** `f65431a`; inventory включает существующие незакоммиченные изменения рабочего дерева

**Метод:** read-only inventory исходников, Graphify, CI/CD и конфигурации. Build и тесты в рамках экспресс-аудита не запускались.

## 1. Executive Summary

DWH Platform — enterprise CMS/DWH-монорепозиторий: модульный Java backend для изолированного клиентского instance, отдельный fleet control plane, две Angular SPA, PostgreSQL, Typesense и Playwright E2E. Архитектура осознанная: отдельные миграционные jobs, schema gates, RBAC, аудит, outbox, health checks, SBOM и security gates в CI. Graphify не обнаруживает циклов импорта.

**Оценка зрелости:** **Growing / pilot-ready**, но ещё не Mature. Сильный фундамент уже есть; основной долг сосредоточен в глубине frontend, полноте автоматических гейтов и непротиворечивости production-readiness.

Главные три риска:

1. Production-код Typesense fail-open подставляет известный dev API key при пустой конфигурации.
2. UI концентрирует большие шаблоны, стили и orchestration в пяти компонентах >1000 строк, а frontend unit-тесты в CI не запускаются.
3. Release-источники противоречат друг другу: `FULL PRODUCTION GO` сосуществует с незакрытым M3 и устаревшим README.

Сильные стороны:

- Java 25 LTS, Spring Boot 4.1.1, Angular 22 и Node 24 — поддерживаемый современный стек.
- 70 тестовых файлов и 241 статически найденная test declaration.
- Один CI workflow покрывает backend verify, frontend builds, clean-deploy E2E, SBOM, Gitleaks и Trivy.
- В кодовой области не найдено TODO/FIXME/HACK; нет AWS keys, private keys или JWT literals.
- Docker runtime запускает собственные приложения от непривилегированного пользователя.

## 2. Карта репозитория

```text
apps/
  instance/               клиентский Spring Boot instance
  control-plane/          fleet/control-plane Spring Boot service
  web-instance/           Angular SPA клиентского instance
  web-cp/                 Angular SPA control plane
libs/
  core-types/             общие типы и error contracts
  platform-common/        общая инфраструктура backend
  provider-spi/           SPI внешних провайдеров
deploy/
  compose/                production Compose topology
  nginx/                  reverse-proxy configuration
  spike/                  Nomad/Vault deployment spike and drills
e2e/
  tests/browser/          Playwright UI journeys
  tests/config/           dotenv/config tests
  tests/security/         artifact credential probes
docs/
  adr/                    архитектурные решения
  audit/                  предыдущие аудиты
  guidelines/             engineering rules
  ops/, runbooks/         эксплуатация и восстановление
scripts/
  dev/                    local smoke/API tooling
  prod/                   backup, restore and deploy scripts
```

Точки входа:

- Backend instance: `apps/instance/src/main/java/com/greenwhite/dwh/instance/InstanceApplication.java:9`.
- Control plane: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/ControlPlaneApplication.java:6`.
- Instance SPA: `apps/web-instance/src/main.ts:7`.
- Control-plane SPA: `apps/web-cp/src/main.ts:7`.
- Dev topology: `docker-compose.yml:55`.
- Production topologies: `deploy/compose/docker-compose.prod.yml:48`, `deploy/compose/docker-compose.fleet.prod.yml:37`.

## 3. Технологический стек

| Компонент | Технология | Версия | Статус | Комментарий |
|---|---|---:|---|---|
| Backend runtime | Java | 25 | Supported LTS | Premier support заявлен до 2030 |
| Backend framework | Spring Boot | 4.1.1 | Current stable | Совпадает с актуальным стабильным релизом на дату аудита |
| Frontend | Angular | 22.1.4 | Active | Active support до 2027-06 |
| Frontend toolchain | Node.js | 24 | Active LTS | Подходящая production-линия |
| TypeScript | TypeScript | 6.0.x | Supported pairing | В диапазоне Angular 22 |
| Database | PostgreSQL | 18 | Supported | Major поддерживается до 2030; image tag не фиксирует minor/digest |
| Search | Typesense | 27.1 | Behind current docs | В официальной документации уже доступна линия 30.2 |
| Web runtime | nginx | 1.27-alpine | Behind current stable | Текущая stable-линия nginx — 1.28 |
| Browser E2E | Playwright | 1.62.1 | Current project pin | CI устанавливает только Chromium |
| Architecture tests | ArchUnit | 1.5.0 | Current | Java 25 bytecode импортируется |

Внешние lifecycle-источники:

- Java: https://www.oracle.com/java/technologies/java-se-support-roadmap.html
- Angular: https://angular.dev/reference/releases
- Node.js: https://nodejs.org/en/about/previous-releases
- PostgreSQL: https://www.postgresql.org/support/versioning/
- Spring Boot: https://github.com/spring-projects/spring-boot/releases
- Typesense: https://typesense.org/docs/latest/
- nginx: https://nginx.org/en/download.html

## 4. Top-10 рисков с фиксами

### R-01: Typesense API key имеет известный fail-open fallback [High]

- **Где:** `apps/instance/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseProperties.java:12`, `apps/instance/src/main/resources/application.yml:126`, `deploy/compose/docker-compose.fleet.prod.yml:30`.
- **Что:** пустой ключ заменяется на `dwh_typesense_local_dev_key`; production Compose не требует переменную через `:?`.
- **Impact:** ошибка secret provisioning может незаметно запустить приложение с публично известным ключом или рассинхронизировать app и Typesense.
- **Fix proposal:**
  - **Approach:** оставить fallback только dev-профилю; production должен fail-fast на blank/placeholder и Compose должен требовать `TYPESENSE_API_KEY`.
  - **Effort:** S
  - **Risk of change:** Medium
  - **Validation:** negative startup test для prod и positive dev-profile test; clean Compose boot с ephemeral key.
  - **Recommended deeper audit:** `audit-security`.
- Подробно: `audit/fixes/R-01.md`.

### R-02: Frontend mega-components создают высокий blast radius [High]

- **Где:** `apps/web-instance/src/app/features/tasks/tasks.component.ts:23`, `apps/web-instance/src/app/features/iam/users/users.component.ts:20`, `apps/web-instance/src/app/features/iam/roles/roles.component.ts:30`, `apps/web-instance/src/app/features/settings/settings.component.ts:11`, `apps/web-instance/src/app/features/audit/audit.component.ts:45`.
- **Что:** пять компонентов превышают 1000 строк; `TasksComponent` — 2907 строк и объединяет template, styles и orchestration.
- **Impact:** локальные UI-изменения затрагивают широкую поверхность, увеличивают регрессии и стоимость ревью.
- **Fix proposal:**
  - **Approach:** по одному user journey извлекать presentational components и facade/state boundary, сохраняя внешний UI contract.
  - **Effort:** L
  - **Risk of change:** Medium
  - **Validation:** component tests + текущие Playwright journeys + screenshot/a11y assertions на каждую извлечённую область.
  - **Recommended deeper audit:** `audit-widgets`, затем `audit-quality`.
- Подробно: `audit/fixes/R-02.md`.

### R-03: Frontend unit-тесты не являются merge gate [High] `[auto-applicable]`

- **Где:** `.github/workflows/ci.yml:56`, `.github/workflows/ci.yml:62`, `apps/web-instance/package.json:10`, `apps/web-cp/package.json:10`.
- **Что:** CI выполняет production builds, но не `npm test`, хотя найдено 31 spec-файл и 58 test declarations.
- **Impact:** unit/component regression может попасть в `main`, если build и ограниченный E2E остаются зелёными.
- **Fix proposal:**
  - **Approach:** после `npm ci` запускать unit tests обеих SPA до production build.
  - **Effort:** S
  - **Risk of change:** Low
  - **Validation:** намеренно сломанный component spec обязан красить frontend job.
  - **Recommended deeper audit:** `audit-testing`.
- Подробно: `audit/fixes/R-03.md`.

### R-04: Нет измеряемого coverage baseline и quality threshold [Medium]

- **Где:** `pom.xml:105`, `.github/workflows/ci.yml:29`, `.github/workflows/ci.yml:56`.
- **Что:** JaCoCo/codecov/Angular coverage thresholds не настроены; наличие тестов не показывает защищённость ключевых ветвей.
- **Impact:** покрытие может незаметно деградировать, особенно при разбиении frontend-компонентов и изменении auth/audit flows.
- **Fix proposal:**
  - **Approach:** сначала публиковать baseline без блокировки, затем ввести дифф-порог для изменённого кода и минимумы для security/business-critical packages.
  - **Effort:** M
  - **Risk of change:** Low
  - **Validation:** CI публикует отчёт; искусственно непокрытая ветвь снижает показатель и нарушает заданный threshold.
  - **Recommended deeper audit:** `audit-testing`.
- Подробно: `audit/fixes/R-04.md`.

### R-05: Browser E2E покрывает узкий happy-path и только Chromium [Medium]

- **Где:** `e2e/playwright.config.ts:9`, `e2e/playwright.config.ts:20`, `.github/workflows/ci.yml:116`, `e2e/tests/browser/instance/tasks.spec.ts:6`.
- **Что:** найдено 9 browser tests; отсутствуют полноценные journeys для files, RBAC, settings, audit и analytics; a11y scan отсутствует; CI устанавливает только Chromium.
- **Impact:** визуальные, keyboard/a11y и role-specific regressions могут пройти все gates.
- **Fix proposal:**
  - **Approach:** добавить по одному критическому journey на существующий модуль и WCAG smoke для основных страниц; второй browser запускать nightly, если Chromium — официальный baseline.
  - **Effort:** M
  - **Risk of change:** Low
  - **Validation:** каждый principal module имеет auth-aware journey; axe/keyboard failures блокируют CI.
  - **Recommended deeper audit:** `audit-testing` и `audit-widgets`.
- Подробно: `audit/fixes/R-05.md`.

### R-06: Supply-chain inputs не закреплены immutable SHA/digest [High]

- **Где:** `.github/workflows/ci.yml:19`, `.github/workflows/ci.yml:153`, `Dockerfile:9`, `apps/web-instance/Dockerfile:5`, `docker-compose.yml:58`.
- **Что:** GitHub Actions используют mutable major tags, а base/service images — mutable version tags без digest.
- **Impact:** одинаковый commit может собрать другой артефакт; компрометация upstream tag расширяет supply-chain blast radius.
- **Fix proposal:**
  - **Approach:** pin Actions на commit SHA и production/base images на digest, оставив автоматический PR-процесс обновлений.
  - **Effort:** S
  - **Risk of change:** Medium
  - **Validation:** повторная сборка одного commit разрешает те же digests; dependency bot создаёт контролируемые update PR.
  - **Recommended deeper audit:** `audit-devops` и `audit-security`.
- Подробно: `audit/fixes/R-06.md`.

### R-07: Dependency currency контролируется сканером, но не обновляющим процессом [Medium]

- **Где:** `.github/workflows/ci.yml:147`, `docker-compose.yml:180`, `apps/web-instance/Dockerfile:16`, область `.github/`.
- **Что:** Trivy обнаруживает известные уязвимости, но Dependabot/Renovate отсутствует; Typesense 27.1 отстаёт от 30.2, nginx 1.27 — от stable 1.28.
- **Impact:** исправления безопасности и compatibility changes накапливаются в более рискованные пакетные апгрейды.
- **Fix proposal:**
  - **Approach:** еженедельные сгруппированные PR для Maven/npm/images/Actions с обязательными существующими gates.
  - **Effort:** S
  - **Risk of change:** Low
  - **Validation:** бот открывает тестовый PR и не объединяет его при красном backend/frontend/E2E/security.
  - **Recommended deeper audit:** `audit-devops`.
- Подробно: `audit/fixes/R-07.md`.

### R-08: Runtime state не допускает безопасное горизонтальное масштабирование [Medium]

- **Где:** `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/security/RateLimitService.java:16`, `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/security/RateLimitService.java:26`, `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/sse/MsSseRegistry.java:20`, `apps/instance/src/main/java/com/greenwhite/dwh/instance/ms/notify/sse/MsSseRegistry.java:29`.
- **Что:** rate-limit buckets и SSE emitters находятся в памяти процесса. Это допустимо для текущего one-instance topology, но нигде не найден fail-fast guard против replicas > 1.
- **Impact:** случайное масштабирование нарушит единый лимит и доставку realtime событий.
- **Fix proposal:**
  - **Approach:** сейчас явно закрепить replica count = 1 и startup invariant; проектировать distributed adapter только при принятом HA SLO.
  - **Effort:** S сейчас / L для HA
  - **Risk of change:** Low сейчас / High для HA
  - **Validation:** deployment policy запрещает вторую replica; architecture test фиксирует выбранную модель.
  - **Recommended deeper audit:** `audit-architecture` и `audit-performance`.
- Подробно: `audit/fixes/R-08.md`.

### R-09: Release promotion и rollback остаются ручными [Medium]

- **Где:** `.github/workflows/ci.yml:1`, `scripts/prod/deploy.sh:1`, `scripts/prod/restore.sh:1`, `deploy/compose/docker-compose.fleet.prod.yml:13`.
- **Что:** найден только CI workflow; production deploy/backup/restore выполняются скриптами, а fleet Compose допускает default `APP_VERSION=1.0.0`.
- **Impact:** оператор может развернуть не тот артефакт; provenance, approvals и rollback evidence зависят от ручной дисциплины.
- **Fix proposal:**
  - **Approach:** publish-on-tag с immutable digest, environment approval, migration gate, health verification и rollback на предыдущий digest.
  - **Effort:** L
  - **Risk of change:** Medium
  - **Validation:** staging promotion и intentional failed rollout автоматически возвращают предыдущую версию.
  - **Recommended deeper audit:** `audit-devops`.
- Подробно: `audit/fixes/R-09.md`.

### R-10: Production-readiness не имеет единого источника истины [High] `[auto-applicable]`

- **Где:** `docs/audit/AUDIT-05-production-readiness-final.md:32`, `REPORT.md:449`, `README.md:57`, `docs/audit/AUDIT-06-m3-auth-review.md:122`.
- **Что:** один документ утверждает `FULL PRODUCTION GO`, другой фиксирует частичный M3 и отсутствие экспоненциальной задержки; README всё ещё говорит о 57 backend tests.
- **Impact:** CTO/operations могут принять неверное go-live решение; незакрытое требование теряется в хронологическом журнале.
- **Fix proposal:**
  - **Approach:** единая versioned release-readiness matrix с owner, evidence, expiry и статусами Blocked/Pilot/GA; исторические аудиты маркировать superseded.
  - **Effort:** S
  - **Risk of change:** Low
  - **Validation:** README, release checklist и CI badge ссылаются на один актуальный статус; M3 остаётся открытым до тестируемого решения.
  - **Recommended deeper audit:** `audit-business-logic`, затем `audit-synthesis`.
- Подробно: `audit/fixes/R-10.md`.

## 5. Метрики «на глаз»

### LoC / язык

| Тип | Файлы | Строки |
|---|---:|---:|
| TypeScript | 100 | 22,943 |
| Java | 244 | 18,747 |
| SQL | 22 | 1,175 |
| PowerShell | 10 | 1,018 |
| CSS/SCSS | 2 | 990 |
| YAML | 8 | 717 |
| XML | 6 | 464 |
| Shell | 10 | 305 |
| MJS | 3 | 190 |
| HTML | 2 | 34 |

Итого: примерно **46.6k строк code/config** в 407 файлах, без generated/vendor/build output.

### Quick smells

- TODO/FIXME/HACK в code/infrastructure scope: **0**.
- Файлов >1000 строк: **5**.
- Очевидные AWS keys / private keys / JWT literals: **0**.
- `.env.example`, `.env.prod.example` и `deploy/compose/.env.example`: присутствуют.
- Material secret candidate: известный Typesense dev fallback — R-01.

### Файлы >1000 строк

| Файл | Строки |
|---|---:|
| `apps/web-instance/src/app/features/tasks/tasks.component.ts` | 2,907 |
| `apps/web-instance/src/app/features/iam/users/users.component.ts` | 1,454 |
| `apps/web-instance/src/app/features/iam/roles/roles.component.ts` | 1,251 |
| `apps/web-instance/src/app/features/settings/settings.component.ts` | 1,072 |
| `apps/web-instance/src/app/features/audit/audit.component.ts` | 1,007 |

### Tests inventory

- Test files: **70** — 38 Java, 31 TypeScript, 1 MJS.
- Static declarations: **171 JUnit**, **58 Angular**, **9 Playwright browser**, **3 Node config/security**.
- Архитектурные правила: ArchUnit.
- Integration: JUnit/Testcontainers/PostgreSQL/Flyway.
- UI: Angular TestBed и Playwright.
- Coverage report/threshold: не найден.

### CI inventory

- Workflows: **1** (`.github/workflows/ci.yml`).
- Jobs: **4** — backend, frontend, e2e, security.
- Named steps: **16**.
- Gates: Maven verify, SBOM, Angular production builds, clean Compose deploy, Playwright Chromium, artifact security, Gitleaks, Trivy.
- Gap: frontend unit tests и coverage не входят в gate.

## 6. Что НЕ удалось оценить

- Реальные production SLO, RPO/RTO, число клиентов, объём данных и concurrency — нужен контекст.
- Фактические cloud/Vault/Garage/SMS/Telegram contracts и коммерческие SLA — внешние системы не проверялись.
- Реальное покрытие строк/ветвей — coverage tooling отсутствует.
- Производительность SQL, Typesense indexing и JVM под production-like нагрузкой — нужен отдельный performance audit.
- Branch protection и GitHub environment approvals — настройки репозитория недоступны из файлов.
- Живое UI-поведение и accessibility — browser tests в этом аудите не запускались.

## 7. Рекомендованные следующие шаги

1. Немедленно закрыть R-01: production fail-fast для Typesense key.
2. Добавить R-03 как быстрый CI gate и синхронизировать release truth по R-10.
3. Провести `audit-testing`: coverage baseline, frontend unit gate, E2E module matrix.
4. Провести `audit-widgets` и последовательно разрезать R-02 без редизайна продукта.
5. Провести `audit-devops` по R-06/R-07/R-09 и `audit-security` для secret/config startup invariants.
6. До решения HA SLO сохранить текущую one-instance модель явным invariant по R-08.
