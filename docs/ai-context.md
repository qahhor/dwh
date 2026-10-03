# Контекст SmartupCMS для AI-ассистентов

**Актуализировано:** 2026-10-01

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

- Сервер: `mvn -B verify` (Spotless, Checkstyle, Error Prone и NullAway,
  тесты с архитектурными правилами, JaCoCo), затем
  `scripts/quality/test-no-skipped-tests.ps1` и
  `scripts/quality/test-coverage-floors.ps1`.
- Web (`apps/web`): `npm run lint`, `npm run typecheck`, `npm run i18n:audit`,
  `npm run aria:audit`, `npm run contrast:audit`, `npm run api:audit`,
  `npm run signals:audit`, `npm test`, `npm run build`; после изменения API —
  `npm run api:types`.
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
  `scripts/architecture/test-unified-boundaries.ps1`.
- CLI `cms`: `npm test` в `tools/cms-cli`; сгенерированный модуль целиком —
  `scripts/dev/test-cms-cli.ps1` (`-SkipBuild` — без сборки Maven).
- Контракт документации (cookbook и руководство по модулям): `node --test
  scripts/docs/docs-contract.test.mjs` и `node scripts/docs/test-docs-contract.mjs`.
- Локальный запуск из исходников (план 10/10, пункт 6.5):
  `scripts/dev/run-local.sh` / `scripts/dev/run-local.ps1` (`up`, `migrate`,
  `down`, `status`; `--demo`, `--detach`, `--devtools`, `--search`; порты из
  окружения `DB_PORT`, `SERVER_PORT`, `MANAGEMENT_PORT`, `WEB_PORT`,
  `MAILPIT_*_PORT`, проект Compose `SMC_LOCAL_PROJECT`). Пароль первого
  администратора — в игнорируемом `.local/admin-password`, логи там же; `make`
  вызывает те же скрипты. Проверка «от `git clone` до UI ≤ 10 минут» —
  `scripts/dev/test-onboarding-smoke.ps1` (ночной job `onboarding`), локально
  на своих портах со сносом стенда.
- Коммиты подписываются `git commit -s`; CI (`ci.yml`, `dco.yml`) запускается
  на push в main и на pull request, `nightly.yml` — по расписанию.

## 7. Точка продолжения — 2026-10-03

Фазы 0–5 [плана 10/10](plan-10-10.md) выполнены и влиты в main; из фазы 6
выполнены 6.1–6.5 (ветка интеграции `claude/p6-int2` поверх main `01794bf7`,
2026-10-03); 6.6 (cookbook, эталонные модули, CODE_STYLE и ADR-0012 по коду) —
в ветке `claude/p6-cookbook`. Фаза 6 выполнена. **Следующее — фаза 7**
(эксплуатация и безопасность production: структурные логи, трейсинг, метрики и
SLO, заголовки nginx, сессии, threat model, DAST, хранилище pg-dwh).
Правила для всех AI-ассистентов — в [`AGENTS.md`](../AGENTS.md), карта модулей —
в [module-map.md](architecture/module-map.md).

- **Фаза 5** (2026-10-03, ADR-0032): общий runtime сущностей
  `/api/v1/entities/<код>` — объявление `EntityDefinition` и хуки вместо
  контроллера, сервиса и репозитория; типы полей, документы со строками и
  статусами, импорт, отчёты, поиск и вебхуки по возможностям; 9 сущностей на
  модели; тест-кит `EntityContractTestKit` (пункт 6.2) у каждой сущности.
- **Фаза 6, 6.3 и 6.4** (ADR-0033): публичный API — `libs/platform-api`
  (`com.smartup24.cms.platform.api..`, `@PlatformApi`, japicmp против 1.0.0,
  `scripts/api/test-platform-api-compat.ps1`); манифест модуля
  `META-INF/smartupcms/modules/<код>.json` (поля `code`, `name`, `version`,
  `minPlatform`, `dependencies`, `configuration`, `migrations`, `messages`;
  неизвестное поле останавливает старт); версия модуля — из манифеста, колонки
  `md_installed_modules.version` нет (V196); сравнение объявлений со схемой —
  `common.entity.EntitySchemaCheck` (`EntitySchemaGate` при старте,
  `EntitySchemaContractTest` в сборке); кит — артефакт `platform-testkit`;
  пример модуля вне монорепо — `examples/external-module`.
- **Фаза 6, 6.1:** CLI `cms` (`tools/cms-cli`) заменил `create-module.ps1`;
  генерирует под контракт 6.3/6.4. Проверки — `node --test` в `tools/cms-cli`,
  `scripts/dev/test-cms-cli.ps1`.
- **Фаза 6, 6.6:** [cookbook](cookbook/README.md) — 17 рецептов на эталонах
  `example.products`, `example.orders`, `example.requests` (V197, V198) и
  `examples/external-module`; фрагменты кода сверяет с файлами
  `scripts/docs/test-docs-contract.mjs`. Кит выдаёт `view` на цели ссылок и
  снимает с «пользователя без прав полей» права полей, даже если это действия
  формы. Тесты CLI считают номер миграции от манифеста.
- **Фаза 6, 6.5:** `make dev`, `scripts/dev/run-local.{sh,ps1}`, профиль
  `demo`, `.devcontainer`, `.editorconfig`, ночной job `onboarding`. Очередь
  заданий тикает только после `ApplicationReadyEvent` (гонка с созданием
  первого администратора). Профиль `devtools` не проверен: зависимость
  `spring-boot-devtools` ждёт одобрения.

- **Фаза 3** прошла ревью качества; её долги закрыты: `NOT_YET_LOCKED` и
  `NOT_YET_PAGED` удалены, хранилище замороженных нарушений ArchUnit пусто,
  «чужой SQL» запрещён (опубликованные представления, ADR-0026), комментарии на
  английском (выпущенные миграции заморожены контрольной суммой).
- **Фаза 4:** настройки `smc.*` / `warehouse.*` (ADR-0027); коды прав по
  модулю (ADR-0028); схема БД и шифрование секретов
  `SMC_SECRETS_KEY` (ADR-0029); `fnd` разделён на `jobs`, `warehouse`, `units`
  (ADR-0030); модуль вебхуков — `webhook`; ключи переводов
  `<module>.<screen>.<element>` (ADR-0031); старое имя продукта осталось только у хранилища
  (cookie `SMC_SESSION`, токены `smc_`, `type` ошибки — URN, метрики `smc`).
- **Переходные периоды отменены (2026-10-01):** установок у клиентов нет,
  поэтому старые имена конфигурации, псевдоним `fnd.migration.MigrateMain`,
  cookie и префикс токенов со старым именем, старые ключи браузера и старые
  коды прав больше не принимаются (ADR-0027 §4, ADR-0028 §5, ADR-0030 §5).

Известные пробелы платформы перечислены в
[extension-points.md](architecture/extension-points.md#7-известные-пробелы):
сверяйте их с фазой 5: часть пробелов закрыта общим runtime (ADR-0032).