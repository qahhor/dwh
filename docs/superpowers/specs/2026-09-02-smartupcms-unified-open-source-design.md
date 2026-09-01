# SmartupCMS Unified Open Source — архитектурный дизайн

- **Статус:** утверждён владельцем продукта 2026-09-02
- **Базовый commit:** `f3d5ad87f4f4f03d20b49271a151b881a9ab8767`
- **Целевой продукт:** SmartupCMS
- **Лицензия:** Apache License 2.0
- **Правообладатель:** Smartup
**Область:** унификация существующего продукта перед первым публичным релизом; новые продуктовые домены не добавляются

## 1. Контекст и мотивация

Текущий репозиторий реализует две пользовательские системы и два runtime-контура:

- tenant instance: Spring Boot backend, Angular frontend, PostgreSQL и Typesense;
- Control Plane: отдельный Spring Boot backend, отдельный Angular frontend и отдельная PostgreSQL.

Control Plane отвечает за fleet registry, heartbeat, enrollment credentials, license status,
центральные объявления, releases, deployment targets и rollout state. Эта модель зафиксирована
в `README.md`, `ADR-0004`, `ADR-0007`, корневом `pom.xml` и Compose-конфигурациях.

Новая продуктовая стратегия меняет эту границу:

- SmartupCMS становится единым self-hosted продуктом;
- одна установка обслуживает одну организацию и множество её пользователей;
- полный продукт доступен всем под Apache-2.0;
- один и тот же код используется community-пользователями и managed hosting Smartup;
- монетизация строится на hosting, внедрении, поддержке и SLA, а не на edition-флагах;
- обязательные исходящие соединения, telemetry и license checks отсутствуют.

## 2. Утверждённые решения

1. Control Plane удаляется, а не переименовывается и не переводится в optional mode.
2. Одна установка SmartupCMS соответствует одной организации; multi-tenancy не вводится.
3. Публичная лицензия — Apache-2.0, правообладатель — Smartup.
4. Продукт имеет один URL и одну команду запуска, но сохраняет разделение runtime-контейнеров.
5. Стандартная конфигурация не выполняет telemetry, heartbeat или update check.
6. Текущий Git-репозиторий планируется публиковать с полной историей после обязательного аудита.
7. Полный функционал одинаков для self-hosted и managed deployments.
8. Публичное имя во всех пользовательских поверхностях — SmartupCMS.
9. Первый релиз включает source, GitHub Release, GHCR multi-arch images и Docker Compose.
10. Внешние contributions принимаются с DCO `Signed-off-by`, без CLA.
11. UI не получает Docker socket и не запускает backup, restore или update.
12. Объявления становятся локальной функцией организации.
13. Небезопасная загрузка пользовательских модулей отключается до появления отдельной plugin-модели.

## 3. Рассмотренные архитектурные варианты

### 3.1. Hard cut до первого публичного релиза — выбран

Удалить Control Plane и все runtime-контракты сразу, затем локализовать только необходимые
операционные функции. Это создаёт одну поддерживаемую архитектуру и минимальную матрицу тестов.

### 3.2. Compatibility bridge — отклонён

Временно сохранить heartbeat, license API и CP deployments. Вариант уменьшает размер одного
диффа, но сохраняет двойную архитектуру, секреты enrollment и внешнюю связность без продуктовой
ценности.

### 3.3. Optional Control Plane — отклонён

Оставить CP отдельным профилем. Вариант требует сопровождать две топологии, две БД, два UI и
два security perimeter. Это противоречит требованию единого продукта.

## 4. Целевая архитектура

```text
Browser
  |
  | HTTPS, one origin
  v
Reverse Proxy
  |-- /          -> SmartupCMS Web
  |-- /api/*     -> SmartupCMS Server
  `-- /events/*  -> SmartupCMS Server

SmartupCMS Server
  |-- PostgreSQL
  |-- Typesense
  `-- StorageProvider
        |-- local volume
        `-- S3-compatible API
              |-- Cloudflare R2 for Smartup managed hosting
              `-- customer-selected compatible provider

Backup sidecar
  |-- reads PostgreSQL through a dedicated backup role
  |-- writes encrypted backup to configured storage
  `-- writes non-secret status to a shared read-only status volume
```

### 4.1. Runtime units

Единый Compose поднимает:

- `proxy` — TLS termination/reverse proxy в production-профиле;
- `server` — Spring Boot modular monolith;
- `web` — Angular static application;
- `postgres` — primary transactional database;
- `typesense` — full-text search;
- `backup` — непривилегированный backup sidecar.

Database и search не объединяются с application container. Это сохраняет независимые health,
backup, upgrade и resource limits без усложнения пользовательской установки.

### 4.2. Исходящие соединения

При стандартной конфигурации SmartupCMS не выполняет запросов во внешний Internet. Внутренние
HTTP/DNS соединения между контейнерами Compose являются частью runtime. Внешние соединения
возникают только после явной настройки администратора: SMTP, Telegram, SMS, webhook или S3
provider. Update check отсутствует и может быть спроектирован позднее только как explicit opt-in.

### 4.3. Storage

`StorageProvider` остаётся vendor-neutral:

- `local` — quickstart и development;
- `s3` — production;
- Cloudflare R2 документируется как рекомендуемый managed-профиль Smartup;
- customer-hosted installation может использовать любой совместимый provider.

Credentials существуют только в server/backup runtime и не возвращаются через API или UI.

## 5. Изменение модульных границ

### 5.1. Удаляемые модули

Удаляются:

- `apps/control-plane`;
- `apps/web-cp`;
- control-plane module из корневого Maven reactor;
- `db-cp`, `migrate-cp`, `control-plane`, `web-cp` из Compose;
- CP migrations и отдельная CP database;
- CP E2E suites, API scripts и release-config assertions;
- heartbeat, enrollment, instance credentials и CP request guards;
- fleet, releases, targets, deployments и rollout rings;
- license status propagation и license gates;
- central module moderation;
- central announcement authoring and targeting.

### 5.2. Сохраняемые модули

Сохраняются существующие продуктовые домены server:

- authentication, sessions и MFA;
- users, roles, permissions и audit;
- tasks, projects, notifications и SSE;
- files и storage quotas;
- search;
- analytics/DWH foundation;
- provider SPI;
- system configuration;
- local announcements.

### 5.3. Переименование каталогов

После функционального удаления CP выполняется отдельный механический commit:

- `apps/instance` -> `apps/server`;
- `apps/web-instance` -> `apps/web`.

Java namespace `com.greenwhite.dwh` в этом релизе не меняется. Массовая замена package names
не влияет на пользовательскую ценность и повышает риск merge/test regressions.

## 6. Модель данных и миграции

### 6.1. Общие правила

- `V001`–`V018` не редактируются.
- Переход выполняет новая forward-only migration `V019`.
- Production deployment запускает backup до migration.
- При ошибке migration server не стартует.
- Миграция проверяется как на пустой БД, так и на snapshot схемы `V018`.

### 6.2. Instance metadata

Из `md_instance_info` удаляются obsolete CP/license columns:

- `license_token`;
- `license_status`;
- `grace_until`;
- `cp_public_keys`.

Сохраняются:

- organization code and name;
- resource profile;
- storage quota;
- timestamps.

Bootstrap больше не создаёт marker `UNLICENSED` и не зависит от CP configuration.

### 6.3. Local announcements

`ms_announcements_cache` преобразуется в `ms_announcements`. Существующие rows и
`ms_announcement_reads` сохраняются. Модель получает:

- локальную sequence/identity, продолженную после максимального существующего `id`;
- local author;
- `DRAFT`, `PUBLISHED`, `ARCHIVED` lifecycle;
- publish/archive timestamps;
- optimistic concurrency field;
- audit events для create, update, publish и archive.

### 6.4. Custom modules

Таблица `md_custom_modules` не удаляется, чтобы не уничтожить введённые metadata. Все active и
pending records переводятся в `DISABLED`. Runtime loading, active listing, create/submit actions
и UI удаляются. Возврат plugin capability требует отдельного threat model, sandbox design,
integrity verification и новой утверждённой спецификации.

### 6.5. Control Plane database

CP data не переносится в tenant database: client registry и cross-organization fleet data
нарушили бы границу одной организации. Перед удалением CP deployment создаётся финальный
архивный backup с retention, определённым оператором. Этот backup не входит в public quickstart.

## 7. API и RBAC

### 7.1. Удаляемые контракты

Удаляются:

- `/api/v1/instances/*`;
- enrollment and heartbeat contracts;
- CP fleet, client, license, release, target and deployment endpoints;
- `/api/v1/system/license-info`;
- custom-module create, submit, callback и active-runtime endpoints.

Поскольку публичного stable release ещё нет, compatibility bridge не создаётся.

### 7.2. System information

`GET /api/v1/system/info` возвращает только non-secret state:

- application version;
- database schema version;
- configured storage type, без endpoint credentials;
- dependency health summary;
- last successful/failed backup status and timestamp;
- installation identifier, если он локально сконфигурирован.

Endpoint требует `settings:view`. В ответе нет environment dump, filesystem paths, database
DSN, access keys или stack traces.

### 7.3. Announcements

Пользовательские endpoints чтения и mark-as-read сохраняются. Локальное управление получает
отдельные actions:

- `announcements:view`;
- `announcements:create`;
- `announcements:update`;
- `announcements:publish`;
- `announcements:archive`.

Publish/archive transitions проверяются service layer, выполняются транзакционно и пишутся в
immutable audit log.

## 8. UI/UX

### 8.1. Единая shell-навигация

Остаётся один login, layout и navigation tree. Все links на CP и отдельную fleet console
удаляются. Product title и metadata унифицируются как `SmartupCMS`.

### 8.2. System screen

Раздел `Система и лицензия` заменяется на `Система`. Экран показывает:

- installed version;
- schema version;
- PostgreSQL/Typesense/storage status;
- backup status;
- deployment profile;
- ссылки на локальную документацию CLI операций.

Экран не содержит кнопок, требующих Docker socket, host shell или privileged agent.

### 8.3. Announcements

Локальный администратор может создать draft, просмотреть preview, опубликовать и архивировать
объявление. Пользователи видят опубликованные объявления и могут отметить их прочитанными.

### 8.4. Custom modules

Раздел модулей, CP moderation badges, ticket identifiers и arbitrary entrypoint controls скрыты.
Старые данные не отображаются как активные расширения.

### 8.5. Quality states

Для каждого изменённого экрана обязательны:

- loading, empty, error and success states;
- keyboard navigation and visible focus;
- screen-reader labels;
- mobile layout;
- reduced-motion compliance;
- axe-core regression checks.

## 9. Deployment и operations

### 9.1. Quickstart

```bash
cp .env.example .env
docker compose run --rm migrate
docker compose up -d --wait
```

Quickstart использует local storage и один published HTTP port. Production documentation требует
TLS reverse proxy, S3 storage, off-host backup и secret management.

### 9.2. Backup status contract

Backup sidecar не вызывает privileged server API. После операции он атомарно записывает
non-secret JSON status в отдельный volume. Server монтирует volume read-only, валидирует размер
и schema документа и отображает status. Backup credentials и archive paths в status не попадают.

### 9.3. Restore и update

Restore и update остаются CLI/Compose workflows. Документация содержит prerequisites,
preflight, backup, migration, readiness, smoke и rollback steps. UI предоставляет только ссылки
на соответствующий локальный runbook.

## 10. Open-source governance

Перед public visibility в root должны существовать:

- `LICENSE` с неизменённым текстом Apache License 2.0;
- `NOTICE` с `Copyright 2026 Smartup`;
- `SECURITY.md` с private vulnerability reporting channel;
- `CODE_OF_CONDUCT.md`;
- `GOVERNANCE.md`;
- `SUPPORT.md` с границей community и paid support;
- `CHANGELOG.md`;
- обновлённый `CONTRIBUTING.md`;
- DCO check для pull requests;
- issue и pull request templates.

Полная Git-история публикуется только после:

- secret scan всех refs и commit objects;
- проверки удалённых `.env`, keys, tokens и private URLs;
- проверки персональных данных в docs, fixtures и history;
- инвентаризации third-party source, fonts, icons and media;
- подтверждения права Smartup перелицензировать весь repository под Apache-2.0;
- явного relicensing statement для исходного кода в исторических commits, где сохранена
  proprietary `LICENSE`;
- ротации любого обнаруженного действующего секрета.

Найденный критичный секрет блокирует публикацию независимо от release schedule.

## 11. Release supply chain

Публичный release создаётся immutable SemVer tag и содержит:

- source archive;
- release notes and checksums;
- GHCR images `smartupcms/server` и `smartupcms/web`;
- `linux/amd64` и `linux/arm64` manifests;
- CycloneDX/SPDX SBOM;
- build provenance;
- keyless image signatures;
- versioned Compose bundle and `.env.example`.

Release workflow имеет минимальные permissions, pinning third-party actions by commit SHA и не
выдаёт secrets pull requests из forks.

## 12. Реализация проверяемыми срезами

1. **Recovery baseline:** tag, DB backups, dependency inventory.
2. **Control Plane removal:** backend, frontend, DB, Compose, CI and tests.
3. **Server decoupling:** heartbeat, enrollment, licensing and CP configuration.
4. **Local capabilities:** `V019`, system info, announcements, module disablement.
5. **Product unification:** naming, paths, Compose, one-origin proxy and storage profiles.
6. **Open-source readiness:** license, governance, contribution and history audit.
7. **Release pipeline:** multi-arch images, SBOM, provenance, signatures and release artifacts.
8. **Final verification:** clean install, `V018` upgrade, E2E, accessibility and security gates.

Каждый срез оформляется отдельным reviewable commit. Функциональный diff и механическое
переименование каталогов не смешиваются.

## 13. Проверка и Definition of Done

Релиз считается готовым, когда одновременно выполнено следующее:

- Maven reactor и Compose не содержат Control Plane runtime modules;
- в активном коде и UI отсутствуют fleet, heartbeat и license contracts;
- исключения допускаются только в superseded ADR, migration history и changelog;
- clean database проходит все migrations и стартует;
- database на `V018` обновляется до `V019` без потери announcements или module metadata;
- standard configuration не выполняет Internet egress; разрешён только внутренний runtime
  traffic между контейнерами;
- quickstart воспроизводим по README на чистом host;
- backend unit/integration/architecture tests зелёные;
- frontend unit/typecheck/build tests зелёные;
- browser E2E покрывает login, system info, announcements, tasks, users, files and settings;
- keyboard/mobile/axe checks зелёные;
- backup failure блокирует destructive migration/update workflow;
- runtime image scan не содержит HIGH/CRITICAL findings;
- full-history secret and provenance audit завершён;
- GHCR manifests содержат amd64 и arm64;
- SBOM, provenance и signatures проверяются отдельным CI step;
- GitHub repository остаётся private до отдельного финального подтверждения владельца.

## 14. Rollback

- Исходный Fleet baseline сохраняется commit `f3d5ad8` и отдельным pre-unification tag.
- До `V019` создаются backups instance и CP databases.
- При migration failure deployment остаётся остановленным.
- До public release откат выполняется возвратом к baseline images и восстановлением instance DB.
- CP backup хранится отдельно и не подключается к unified runtime.
- После public release destructive rewrite Git history допускается только как security incident.

## 15. Не входит в этот scope

- multi-tenancy;
- optional или hosted Control Plane;
- automatic telemetry/update checks;
- self-update из UI;
- Docker socket/privileged deployment agent;
- public plugin registry;
- arbitrary remote microfrontends;
- новые платные editions или license gates;
- Kubernetes/Helm;
- массовое переименование Java packages;
- продуктовые модули вне существующего SmartupCMS scope.
