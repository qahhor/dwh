# Контекст SmartupCMS для AI-ассистентов

**Актуализировано:** 2026-09-04

**Назначение:** краткий воспроизводимый handoff для следующей AI-сессии

**Статус:** справочный контекст, не нормативный источник требований

Этот файл помогает быстро восстановить контекст проекта. Он не заменяет
[каноническое ТЗ](technical-specification.md), действующие
[ADR](README.md#authority-tier-2--current-decisions), код, конфигурацию или
результаты проверок. При конфликте AI обязан зафиксировать расхождение и
проверить первичный артефакт, а не дополнять пробел догадкой.

## 1. Продукт и стратегия

SmartupCMS — открытая self-hosted платформа контента и операционной работы для
одной организации и многих пользователей. Одна установка обслуживает одну
организацию и использует отдельный экземпляр PostgreSQL и отдельное объектное
хранилище. Продукт
объединяет пользователей и RBAC, задачи и комментарии, файлы, поиск,
уведомления, объявления, аудит и системное администрирование.

Проект развивается по принципу «углублять качество, не расширять scope».
Control Plane, fleet orchestration, runtime licensing, remote enrollment,
обязательная телеметрия и phone-home не входят в продукт. Горизонт подготовки
запуска — четыре месяца от утверждённой базовой точки; точная дата не
утверждена.

Плановые входные данные управляемого контура являются суммарными, а не
per-installation: примерно 100 установок, 500 зарегистрированных пользователей,
100 одновременно активных пользователей и 50 ГБ загрузок в месяц. Эти числа не
являются измеренным SLO или доказательством производительности.

## 2. Нормативные источники

Использовать следующий порядок приоритета:

1. прямое текущее указание пользователя;
2. [техническое задание](technical-specification.md) с идентификаторами
   `FR-*`, `NFR-*`, `AC-*`;
3. [ADR-0014](adr/ADR-0014-unified-open-source-runtime.md) и другие действующие
   ADR согласно [индексу документации](README.md);
4. код, миграции, Compose и автоматические проверки;
5. активные engineering/operations/security документы;
6. `audit/` и `docs/superpowers/` только как датированная история и evidence.

Неподтверждённые локальные audit-черновики не являются требованиями и не должны
попадать в committed-документацию или Graphify до отдельной сверки.

## 3. Карта системы

| Область | Текущая реализация |
|---|---|
| Web | Angular 22 SPA в `apps/web`; production image обслуживается NGINX |
| Backend | Java 25, Spring Boot 4.1, модульный монолит в `apps/server` |
| Shared kernel | `libs/core-types`, `libs/platform-common`, `libs/provider-spi` |
| Транзакционные данные | PostgreSQL 18 и неизменяемые Flyway migrations |
| Поиск | Typesense 27.1 как производный индекс, не источник авторизации |
| Файлы | `local_disk` или S3-compatible provider через SPI |
| Поставка | Docker Compose; отдельный one-shot `migrate`; production backup и ClamAV |
| Проверки | Maven, Angular/Vitest, Playwright, PowerShell architecture/docs/release gates |
| Локализация | Центральный PostgreSQL registry/overrides, восемь packaged-языков, русский per-key fallback |

Browser обращается только к server API. Авторизация всегда выполняется на
сервере; Typesense не принимает решений о доступе. Контроллеры валидируют и
авторизуют запрос, application services владеют use case и транзакцией, а
repositories/adapters — I/O. Детали приведены в
[карте монорепозитория](architecture/monorepo-structure.md).

## 4. Deployment и provider policy

- Smartup-managed инфраструктура использует Cloudflare как внешний
  DNS/TLS/security edge и Cloudflare R2 как целевое объектное хранилище.
- На инфраструктуре клиента оператор может выбрать собственный edge,
  `local_disk` или любой проверенный S3-compatible provider.
- Локальная/development топология может работать без ClamAV и планового backup.
  Поддерживаемая production-поставка требует fail-closed ClamAV и
  зашифрованный backup.
- Production публикует только web origin; PostgreSQL, Typesense и management
  endpoints не должны быть доступны извне.
- Внешние providers и webhooks включаются оператором; default runtime не должен
  требовать исходящего соединения.

## 5. Инварианты безопасности и данных

- Не хранить в Git `.env`, secret-файлы, customer data, дампы БД и
  расшифрованные backups.
- Проверки RBAC/ownership/data scope выполняются на сервере; UI не является
  security boundary.
- Upload проходит quarantine, проверку размера/MIME/magic bytes и malware scan
  до публикации объекта; production scanner работает fail-closed.
- Flyway migrations неизменяемы после публикации. Для upgrades применяются
  expand/contract, pre-migration encrypted backup и forward fix либо
  restore предыдущей проверенной версии.
- Release images принимаются только по immutable digest с checksums, SBOM,
  provenance и Cosign verification.

См. [threat model](security/threat-model.md),
[migration guidance](guidelines/database-migrations.md) и
[production launch checklist](ops/production-launch-checklist.md).

## 6. Последняя подтверждённая проверка

Базовый `main` и `origin/main` перед локальным patch указывали на
`1b24d3f5e63256fcb4257ae230a57c59fa73d6bf`. Для этого SHA ранее в чистом
checkout подтверждены web 26 files / 68 tests, typecheck/build, E2E
typecheck/artifact-security/theme 2/2 и здоровая Docker-топология из четырёх
runtime services.

Remote CI run `33833803385` для этого SHA красный: frontend и backend jobs
зелёные, но `release-config` упал на backup-status contract, а `security` — на
Trivy. Поэтому `1b24d3f` не является release-ready baseline.

Локальный рабочий patch от 2026-09-04 исправляет обе первопричины:

- backup-status contract использует изолированный Docker volume с production
  UID/GID 10001 вместо root-owned host bind file;
- embedded Tomcat обновлён с 11.0.24 до 11.0.25;
- Trivy проверяет resolved backend CycloneDX SBOM, web application/build и E2E dev
  toolchain раздельно, сохраняя fail-closed HIGH/CRITICAL threshold.

На этом локальном patch подтверждены:

- Maven: 214 тестов, 0 failures/errors/skipped, `BUILD SUCCESS`;
- Trivy 0.70.0: 0 HIGH/CRITICAL для backend SBOM, web включая dev
  dependencies и E2E;
- 7/7 architecture/docs/release/production contract gates;
- `actionlint` 1.7.7.

Изменения ещё не прошли remote CI на immutable commit. Не заявлять R-01
закрытым до push и полного green run. При следующей сессии сначала проверить
`HEAD`, `git status` и применимые gates; не включать в работу unrelated dirty
UI/E2E changes и локальные audit drafts без отдельного решения пользователя.

Текущий незакоммиченный localization patch добавляет Flyway `V022`, публичные
словари и защищённый admin API, редактор переводов, динамический выбор языка,
атомарную синхронизацию предпочтения с `md_users.language` и миграцию legacy
browser-пакетов. Вся
статическая Angular-копия сведена в русский каталог; неполные целевые каталоги
явно показывают coverage и используют русский fallback. На текущем patch
локально подтверждены: localization audit `976/987`, Angular 30 файлов / 87
тестов, frontend typecheck и production build (initial bundle 476.53 kB), E2E
typecheck, полный instance E2E 18/18 и Maven 229 тестов без
failures/errors/skipped. Чистые Testcontainers-БД успешно применяют 22 Flyway
миграции до `V022`; локальные Docker server/web пересобраны и healthy. E2E
возвращает исходные переводы и язык администратора (`ru`). Remote CI и commit
для этого patch ещё не подтверждены.

Текущий незакоммиченный patch страницы «Состояние» добавляет серверную отметку
`checkedAt`, параллельные проверки компонентов с настраиваемым пределом
`DWH_SYSTEM_HEALTH_TIMEOUT`, агрегированный статус установки, явную индикацию
устаревшего snapshot после ошибки обновления и семантически разные состояния
backup. Backup freshness оценивается относительно явно заданного
`DWH_BACKUP_MAX_AGE`; `0s` означает неподтверждённую политику и не даёт
зелёного статуса, превышение порога возвращает `STALE`. После объединения с
localization patch локально подтверждены: localization audit 997 используемых
ключей / 1010 русских ключей, Angular 30 файлов / 93 теста, frontend и E2E
typecheck, Maven 231 тест без failures/errors/skipped, production release-config
gate, system E2E 4/4 и полный instance E2E 20/20 на пересобранном healthy
Docker-стеке. Remote CI и immutable commit не подтверждены.

## 7. Открытые release gates

Production readiness остаётся условной, пока не закрыты:

- утверждённые p95/p99/error/saturation thresholds и распределение нагрузки по
  установкам;
- privacy/retention/legal owners и процессы delete/export/hold;
- external metrics/log collection, alert delivery и named on-call;
- изолированный DB-plus-objects restore drill с проверкой checksums и RPO/RTO;
- installation-specific domain, Cloudflare/origin policy либо альтернативный
  self-hosted edge, provider region и rollback/go-no-go owner;
- CI/release evidence, связанное с immutable remote commit и image digests.

Текущий список и доказательства находятся в
`audit/health-check-2026-09-04.md` и
[production launch checklist](ops/production-launch-checklist.md).

## 8. Рабочий протокол для AI

1. Сначала прочитать этот файл, ТЗ, индекс документации и применимые ADR.
2. Выполнить `git status --short --branch`; любые существующие изменения считать
   пользовательскими, пока не доказано обратное.
3. Для codebase-вопроса сначала использовать `graphify query`, затем проверять
   вывод по исходным файлам.
4. Не выдумывать отсутствующие SLO, даты, provider settings, владельцев или
   результаты тестов.
5. Не расширять продукт без нового решения; предпочитать минимальный
   исправляющий шаг и измеримый критерий проверки.
6. После изменений запускать наиболее узкие релевантные проверки, затем полный
   обязательный gate перед release-утверждением.
7. Если dirty worktree содержит неопубликованные файлы, Graphify для коммита
   генерировать из clean checkout целевого commit, чтобы граф был
   воспроизводимым.
8. Не выполнять push, deploy, удаление данных или изменение внешней
   инфраструктуры без явного разрешения пользователя.

Базовые команды проверки перечислены в
[testing strategy](guidelines/testing-strategy.md) и корневом
[README](../README.md#development).
