# Контекст SmartupCMS для AI-ассистентов

**Актуализировано:** 2026-10-21

**Назначение:** краткий воспроизводимый handoff для следующей AI-сессии

**Статус:** справочный контекст, не нормативный источник требований

Этот файл помогает быстро восстановить контекст проекта. Он не заменяет
[каноническое ТЗ](technical-specification.md), действующие
[ADR](README.md#authority-tier-2--current-decisions), код, конфигурацию или
результаты проверок. При конфликте зафиксируйте расхождение и проверьте
первичный артефакт, а не дополняйте пробел догадкой.

## 1. Продукт

SmartupCMS — self-hosted **low-code CMS для разработчиков**. Сущность
объявляется один раз на сервере (`EntityDefinition`), а список, форма,
карточка, история, экспорт, массовые действия, пункт меню и названия прав
строятся платформой ([ADR-0019](adr/ADR-0019-low-code-entity-model.md)).
Встроенные модули — пользователи и роли, задачи и проекты, заметки, файлы,
поиск, уведомления, аудит, загрузки данных (`upl` поверх `jobs`, `warehouse`, `units`) — построены на той же
платформе. Одна установка обслуживает одну организацию. Языки интерфейса:
русский (канонический), узбекский и английский; другие администратор
добавляет в редакторе языков.

## 2. Нормативные источники

1. Прямое текущее указание пользователя.
2. [Техническое задание](technical-specification.md) (`FR-*`, `NFR-*`, `AC-*`).
3. Действующие ADR по [индексу документации](README.md).
4. Код, миграции, Compose и автоматические проверки.
5. Активные engineering/operations/security документы.

Датированные аудиты и планы агентов удалены из дерева; история — в git.

## 3. Карта системы

| Область | Реализация |
|---|---|
| Backend | Java 25, Spring Boot 4.1, модульный монолит `apps/server`, пакет `com.smartup24.cms` |
| Web | Angular 22 SPA `apps/web`, UI kit `shared/ui-kit`, компоненты сущности `shared/entity` |
| Общие библиотеки | `libs/core-types`, `libs/platform-common`; публичный API — `libs/platform-api` и `libs/provider-spi` (ADR-0033), тест-кит — `libs/platform-testkit` |
| Данные | PostgreSQL 18, неизменяемые Flyway-миграции (`V###`, манифест контрольных сумм), вторая база для загруженных данных |
| Поиск | Typesense 27.1 — производный индекс, не источник авторизации |
| Файлы | `local_disk` или S3-совместимое хранилище через SPI |
| API | REST `/api/v1`, описание генерируется из кода (`docs/api/openapi.json`), поведение — [docs/api/README.md](api/README.md) |
| Поставка | Docker Compose, отдельный шаг `migrate`, зашифрованный backup, ClamAV в production |

Модули сервера (`com.smartup24.cms.instance.*`) — назначение, таблицы,
области прав и точки входа каждого — в
[карте модулей](architecture/module-map.md); список бизнес-модулей задаёт
`ModuleBoundariesTest.MODULES`, соответствие карты и `package-info.java`
проверяет `ModuleMapTest`. Точки
расширения — в [extension-points.md](architecture/extension-points.md),
порядок работы над модулем — в
[руководстве](guidelines/module-development-guide.md).

## 4. Платформа low-code

- **Реестр полей** (ADR-0016): `QueryList` → `GET /api/v1/query-meta/{code}`;
  фильтр — JSON DSL с группами `{"any": [...]}` и полями-ссылками; keyset-курсор.
- **Сущность** (ADR-0019): `EntityDefinition` → `GET /api/v1/form-meta/{code}`;
  `EntityValidator` проверяет сохранение (422 на поле); `EntityRecords` даёт
  историю, экспорт и `POST /api/v1/entities/{code}/bulk`; `EntityRights` и
  `EntityMenu` — названия прав и `GET /api/v1/entities/menu`.
- **Web:** `smt-entity-form`, `smt-entity-card`, `smt-entity-toolbar`,
  `ui-server-table` + `registryTableConfig`.
- **CLI `cms`** (`tools/cms-cli`, Node.js без пакетов, план 10/10, пункт 6.1):
  `cms module new`, `cms entity new` (объявление, хуки, тест кита, миграции
  таблицы и прав со следующими свободными номерами в манифесте, ключи ru/uz/en,
  порог покрытия, строка карты модулей), `cms entity add-field`,
  `cms migration diff` (через `EntitySchemaDiffTest`; сравнение — то же
  `EntitySchemaCheck`, что и при старте), `cms doctor`; манифест модуля —
  `META-INF/smartupcms/modules/<область>.json` по ADR-0033; повторный
  запуск ничего не меняет, правки рукой не перезаписываются. Результат проверяет
  `scripts/dev/test-cms-cli.ps1` / `tools/cms-cli/scripts/smoke.mjs` (nightly на
  ubuntu и windows), тесты CLI — `npm test` в `tools/cms-cli` (ci).
- **Эталоны и cookbook** (план 10/10, пункт 6.6): [docs/cookbook](cookbook/README.md)
  — рецепт на задачу; эталоны модуля `example` (выключен в поставке):
  справочник `example.products`, документ со строками `example.orders`,
  документ со статусами `example.requests` (хуки, право на поле); модуль вне
  монорепо — `examples/external-module`; свой экран — заметки (`features/notes`).
  Контракт документации — `node scripts/docs/test-docs-contract.mjs` (ci).

## 5. Инварианты

- Авторизация — только на сервере (`@RequiresPermission`, скоуп данных); UI не
  является границей безопасности.
- **Ошибки** — `ApiException` с `ErrorCode`, ключом `error.<модуль>.<имя>` и
  параметрами; ключ есть в ru, uz и en; ответ — `application/problem+json`
  (ADR-0021).
- **API** — DTO в пакете `api` модуля; после изменения контроллера или DTO
  перегенерировать `docs/api/openapi.json` (`-Dopenapi.update=true`) и типы веба
  (`npm run api:types`); 201 + `Location`, 204 объявлены `@ResponseStatus`;
  ломающее изменение — метка `api-breaking` или трейлер `Api-Breaking:` и
  запись в `CHANGELOG.md` (ADR-0022, ADR-0023).
- **Блокировка** — изменение записи называет ревизию (`If-Match`,
  `Revisions.required`); без неё 428, устаревшая — 409 (ADR-0024).
- **Страницы** — растущая коллекция отдаётся `KeysetPage` (реестр полей или
  `TimePage`), `limit` выше максимума — 422.
- **Журналы и кэш** — журнальная таблица объявляет `RetentionPolicy`; имя кэша
  регистрируется в `CacheConfig` (ADR-0025).
- **Код** — комментарии по-английски со ссылками только на ADR, FR/NFR и пункты
  плана 10/10; класс до 400 строк, метод до 60; JSON-колонки через
  `JsonColumns`; `catch` не глотает ошибку ([CODE_STYLE](../CODE_STYLE.md)).
- **Миграции** — после публикации не меняются (`migration-manifest.sha256`,
  новый файл добавляется `-Dmigrations.manifest.append=true`); с V128 —
  правила ADR-0020; DDL и данные в разных файлах; изменение данных помечается
  заголовком `destructive: approved` с причиной и утвердившим.
- **Конфигурация** — продукт `smc.*` / `SMC_*`, хранилище `warehouse.*` /
  `WAREHOUSE_*`; старые имена `dwh` не читаются (установок нет, переходных
  периодов и синонимов не бывает — `AGENTS.md`, раздел 3); класс
  `@ConfigurationProperties` помечен `@Validated`; после изменения настроек —
  `-Dconfig.reference.update=true` для
  [справочника](ops/configuration-reference.md) (ADR-0027).
- **SQL** — только в репозиториях: класс `*Service` запросов не пишет
  (`ServicesRunNoSqlTest`); очередь `jobs` не зависит от `warehouse` (ADR-0030).
- Не коммитить `.env`, секреты, дампы, данные клиентов, `graphify-out/`.
- Русский каталог `apps/server/src/main/resources/i18n/ru.json` — источник
  ключей; `uz` и `en` полные; после изменений — `npm run i18n:sync-ru`.

## 6. Проверки перед пушем

- Сервер: `mvn -B clean spotless:check verify` (Spotless, Checkstyle, Error
  Prone и NullAway, тесты с архитектурными правилами, JaCoCo), затем
  `scripts/quality/test-no-skipped-tests.ps1` (пропущенные тесты Maven,
  `skip`/`only`/`todo` в тестах веба и e2e) и
  `scripts/quality/test-coverage-floors.ps1`; покрытие изменённых строк
  ветки (diff-cover 10.6.0, как в `ci.yml`, порог 80 %):
  `python -m diff_cover.diff_cover_tool apps/server/target/site/jacoco/jacoco.xml --compare-branch=origin/main --src-roots apps/server/src/main/java --fail-under=80`.
- Совместимость публичного API (`libs/platform-api`, `libs/provider-spi`):
  `scripts/api/test-platform-api-compat.ps1`.
- Web (`apps/web`): `npm run lint`, `npm run typecheck`, `npm run i18n:audit`,
  `npm run aria:audit`, `npm run contrast:audit`, `npm run api:audit`,
  `npm run signals:audit`, `npm run comments:audit`, `npm run test:coverage`
  и порог покрытия `node scripts/coverage-summary.mjs`, `npm run build`; после
  изменения API — `npm run api:types`.
- API: `scripts/api/test-api-contract.ps1` (Spectral, свежесть типов веба,
  openapi-diff против базовой ветки).
- E2E (`e2e`): `npm run typecheck`; доступность — `npm run test:a11y`; полный
  прогон на стенде Compose с почтовой заглушкой
  (`docker compose -f docker-compose.yml -f scripts/dev/e2e-mail.compose.yml up -d --wait`)
  — `scripts/dev/test-e2e.ps1` (в CI два шарда, `-Shard N/2`; `-CheckReadiness`
  в конце останавливает postgres и проверяет readiness,
  `scripts/dev/test-readiness-dependency.ps1`).
- Документация и репозиторий: `scripts/docs/test-public-docs.ps1` (каждый ADR
  в индексе, ссылки), `scripts/docs/test-repository-hygiene.ps1`,
  `scripts/architecture/test-unified-boundaries.ps1`, rulesets и триггеры
  workflow (только ручной запуск) — `scripts/github/test-rulesets.ps1`.
- CLI `cms`: `npm test` в `tools/cms-cli` (как в CI: `node --test
  'tools/cms-cli/test/**/*.test.mjs'` и `node tools/cms-cli/scripts/smoke.mjs
  --skip-build`); сгенерированный модуль целиком —
  `scripts/dev/test-cms-cli.ps1` (`-SkipBuild` — без сборки Maven).
- Контракт документации (cookbook и руководство по модулям): `node --test
  scripts/docs/docs-contract.test.mjs` и `node scripts/docs/test-docs-contract.mjs`.
- Локальный запуск из исходников (план 10/10, пункт 6.5):
  `scripts/dev/run-local.sh` / `scripts/dev/run-local.ps1` (`up`, `migrate`,
  `down`, `status`; `--demo`, `--detach`, `--search`; порты из
  окружения `DB_PORT`, `SERVER_PORT`, `MANAGEMENT_PORT`, `WEB_PORT`,
  `MAILPIT_*_PORT`, проект Compose `SMC_LOCAL_PROJECT`). Пароль первого
  администратора — в игнорируемом `.local/admin-password`, логи там же; `make`
  вызывает те же скрипты. Проверка «от `git clone` до UI ≤ 10 минут» —
  `scripts/dev/test-onboarding-smoke.ps1` (ночной job `onboarding`), локально
  на своих портах со сносом стенда.
- Коммиты подписываются `git commit -s`. GitHub Actions автоматически не
  запускаются (владелец, 2026-10-17): `ci.yml`, `dco.yml`, `codeql.yml`,
  `nightly.yml`, `scorecard.yml` — только вручную (`workflow_dispatch`),
  `release.yml`/`prerelease.yml` — по тегу версии; шлюз merge — локальные
  проверки этого раздела.
- Одна рабочая станция — не больше 2–3 параллельных AI-агентов (владелец,
  2026-10-08); тяжёлые проверки (полный verify реактора, стенды e2e, ZAP,
  Testcontainers, профили `-P*-large`) идут по одной; агента останавливают, как
  только он отчитался. JVM самого Maven ограничена `.mvn/jvm.config` (`-Xmx2g`).

## 7. Точка продолжения — 2026-10-21

Предыдущие записи (этапы 1–3 конструктора, фазы 0–7, ревью) — в архиве
[history/handoff-log.md](history/handoff-log.md): по умолчанию не читать, искать через `grep`.

**Состояние `main`** (HEAD `ca833866`, запушен): этапы 1–3 конструктора форм и отчётов сделаны — 3.1 автоматизации
([ADR-0046](adr/ADR-0046-live-form-automations.md)), 3.2 пакеты решений и шаблоны
([ADR-0047](adr/ADR-0047-solution-packages-and-templates.md)), 3.3 витрины хранилища как источник отчётов
([ADR-0048](adr/ADR-0048-warehouse-mart-reports.md)); регрессии 3.2–3.3 исправлены 2026-10-21.

**Открыто:**
- правила репозитория владельца применяются через `gh` (rulesets);
- чек-лист production «NO-GO» (`docs/ops/production-launch-checklist.md`);
- baseline japicmp 1.0.0 против platform-api 2.0.0;
- reviewer в `release.yml`; Observatory A+; gate 4;
- остаточные пробелы конструктора: файлы в строках документа, очистка отвязанных файлов, рассылка в Telegram без
  вложения, согласование только по праву.

**Следующий шаг:** план перестройки серверных модулей
([module-restructuring-plan](architecture/module-restructuring-plan.md)) ждёт решения владельца; обзор системы —
[overview](architecture/overview.md); правила для агентов — [ai-agent-brief](ai-agent-brief.md).
