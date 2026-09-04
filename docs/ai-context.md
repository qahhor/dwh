# Контекст SmartupCMS для AI-ассистентов

**Актуализировано:** 2026-09-05

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

Опубликованный `main`/`origin/main` указывает на immutable commit
`6606a7a723a3bbbb67d26135aad2ef150990bdbb`. Remote CI
[run `33915401176`](https://github.com/qahhor/dwh/actions/runs/33915401176)
завершён `success`: backend, frontend, release-config, security и clean-deploy
browser E2E jobs зелёные.

`57efcd77` закрывает локальную реализацию `P0-14`:

- ADR-0013 фиксирует единый контракт `ALL/SUBTREE/UNITS/SELF` для задач,
  комментариев и файлов; недоступный прямой идентификатор возвращает `404`;
- task/file repositories применяют row-scope в SQL до пагинации и подсчётов,
  HTTP controllers всегда передают идентификатор аутентифицированного
  пользователя;
- назначения участников проверяются по actor scope, а замена роли
  пересчитывает permissions и effective data scope в одной транзакции;
- Flyway `V024` добавляет обратный индекс вложений комментариев;
- Maven: 262 теста, 0 failures/errors/skipped; Angular: 31 test files / 107
  tests, typecheck, localization audit `1009/1022` и production build; E2E
  config/typecheck/artifact-security и семь architecture/docs/release/
  production gates зелёные.

Первый E2E-дефект (общий expensive bucket для `/api/v1/audit/**` и
`/api/v1/search/**`) исправлен в `5eba93f`; локально Maven и browser E2E 24/24
были зелёными. Артефакт run `33909868657` выявил второй дефект: все
неаутентифицированные запросы делили строгий IP bucket, поэтому параллельные
browser contexts за одним CI/corporate NAT получали `429` при загрузке публичных
i18n-словарей и показывали raw translation keys на форме входа. Commit `6606a7a`
выделяет `GET /api/v1/i18n/languages` и locale dictionaries в независимый
настраиваемый `public-read` bucket; новый regression test сначала красный
(`404` ожидался, получен `429`), после исправления зелёный 5/5. Полный Maven
suite с PostgreSQL/MinIO Testcontainers: 264 теста, 0 failures/errors/skipped;
Angular: 31 файлов / 107 тестов, typecheck/build/i18n audit green; пересобранный
Docker runtime: Playwright 24/24 green.

Commit `6606a7a` также реализует локально проверяемую часть `P0-15`: fail-closed managed
preflight/host contracts, digest-only deployment evidence, 100-user/20-upload/
4h k6 profiles, runtime storage/scanner latency metrics, failure drills,
encrypted object backup, combined isolated restore и published-release
verification. Это код и процедура, не доказательство реального окружения.

## 7. Открытые release gates

Production readiness остаётся условной, пока не закрыты:

- утверждённые p95/p99/error/saturation thresholds и распределение нагрузки по
  установкам;
- privacy/retention/legal owners и процессы delete/export/hold;
- external metrics/log collection, alert delivery и named on-call;
- изолированный DB-plus-objects restore drill с проверкой checksums и RPO/RTO;
- installation-specific domain, Cloudflare/origin policy либо альтернативный
  self-hosted edge, provider region и rollback/go-no-go owner;
- опубликованный stable release со связанными SBOM/provenance/signatures и
  пятью image digests.

Для выполнения target-only managed gate отсутствуют: Hetzner staging host,
production/staging hostname, Cloudflare zone/token, отдельные application и
recovery R2 buckets/credentials, alert receiver и named on-call, утверждённые
SLO/RPO/RTO thresholds, 100 staging load-user tokens и опубликованный release
tag с пятью digest-addressed images. Эти пункты остаются `UNVERIFIED` и блокируют
GO; локальная эмуляция не может изменить их статус.

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
