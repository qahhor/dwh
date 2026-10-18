# Контекст SmartupCMS для AI-ассистентов

**Актуализировано:** 2026-10-18

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
  `python -m diff_cover.diff_cover_tool apps/server/target/site/jacoco/jacoco.xml --compare-branch=main --src-roots apps/server/src/main/java --fail-under=80`.
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

## 7. Точка продолжения — 2026-10-18

**Конструктор, этап 2, шаг 2.2 — документы живых форм (2026-10-18, прямо в `main` поверх `fb470411`, не запушено —
пушит координатор):** [ADR-0043](adr/ADR-0043-live-form-documents.md), ADR-0041 «Этап 2, шаг 2.2». simple-form формат 6:
`lines` (до 3 таблиц, до 20 колонок, 1–500 строк, формулы колонок над строкой и `doc.<поле>`), `entity.numbering`
(шаблон `{YYYY}`/`{YY}`/`{MM}`/`{N…}`, сброс `never`/`year`/`month`, `assignOn`), `workflow` (статусы с цветом,
`terminal`, `lockAll`/`locks`; переходы с правом `studio.<форма>`/`<код>`, условием-формулой, комментарием
`none`/`optional`/`required`, `notAuthor`, `notify`). V227 `studio_record_lines_1..3` (строки по месту таблицы, JSON,
≤ 16 КиБ), V228 `studio_records.doc_number` (уникален в форме, триггер) и `studio_form_number_counters`. Platform API
только расширен (japicmp зелёный): партиционированная сущность держит коллекции в другой таблице и процесс; строка —
JSON-свойства, `ENUM`, `REF`. Runtime: `EntityLineChecks` (ссылки и справочники строк одним запросом на поле),
`TransitionGuard` (`StudioTransitionRules`), хуки живой декларации (`StudioDocumentHooks`: номер, комментарий,
уведомление), `EntityProcessView` в form-meta, `common.entity.EntityNotice` → `MsEntityNoticeListener`; формулы —
`lines.<т>.<к>`, `lines.<т>`, типы `numbers`/`rows`, только `sum`/`count`. XLSX списка — лист на таблицу строк (ссылки
и элементы справочника по именам, `QueryListExporter.Detail.named`). Совместимость — `StudioDocumentChangeRules`.
Веб: блоки «Строки документа», «Нумерация документа», «Статусы и согласование» в редакторах, расчёт колонок и итогов в
форме, диалог комментария перехода и цвет статуса в карточке. E2E эталона:
`e2e/tests/browser/instance/studio-order-approval.spec.ts`. **Не сделано (следующий шаг):** файлы живой формы через
`mf` (JSON-свойство файла, несколько вложений), согласующий — ответственный или руководитель подразделения,
`min`/`max`/`avg` по колонкам. Локальные проверки на итоговом коде: полный reactor verify (27,6 мин; сервер 3404 теста, всего 3514, 0 пропусков), пороги покрытия (studio 98,3/93,6), diff-cover 98 %, совместимость Platform API, OpenAPI (разрыв `formulas/check` объявлен `Api-Breaking:` — шире `kind`, новые необязательные поля), веб — аудиты, forms audit 0, 2865 тестов, покрытие 92,5/90,4/87,6/82,1 и сборка, a11y 157/157, E2E 41 + 37 и карантин, docs/hygiene/rulesets, gitleaks — без находок.

**Конструктор, этап 2, шаг 2.1 — формулы и логика живых форм (2026-10-18, прямо в `main` поверх `a8ffca8c`, не
запушено — пушит координатор):** [ADR-0042](adr/ADR-0042-live-form-formulas.md), ADR-0041 «Этап 2, шаг 2.1». Язык формул
`common.formula` (лексер, парсер с границами, проверка типов, интерпретатор: `BigDecimal`, 10 знаков `HALF_UP`, семантика
null SQL, 25 функций); веб не парсит — интерпретирует проверенное дерево (`core/formula`, BigInt), общий набор случаев
`docs/api/formula-conformance.json` (сервер пишет деревья `-Dformula.update=true`). simple-form формат 5: у поля
`formula`/`defaultFormula`/`visibleIf`/`requiredIf`/`readonlyIf`, у формы `rules` (`MdSimpleFormLogic` — проверка при
подготовке, циклы вычисляемых полей). Runtime: `common.entity.EntityLogic` в декларации источника
(`LiveEntities.logic`), `EntityLogicRun` в `EntityFieldValues.prepareAll` (умолчания → вычисление → скрытие → повторное
вычисление → `requiredIf`), правила — `EntityRule` сущности (`rule_failed`, `error.studio.rule_failed`), часовой пояс —
`X-Time-Zone` (`RequestZone`). `form-meta` отдаёт деревья полям и `rules` форме; `POST /api/v1/studio/formulas/check`.
Совместимость: логика меняется свободно, поле с введёнными значениями не становится вычисляемым (`change_computed`).
Миграций нет. Веб: живой расчёт и условия в `smt-entity-form` (`core/services/form-logic.ts`), блоки «Формулы и условия» и
«Правила проверки» в редакторах. E2E эталона: `e2e/tests/browser/instance/studio-order-formulas.spec.ts`. Дальше — шаг 2.2
(строки документа, нумерация, файлы, статусы с согласованием), язык формул готов для итогов строк и условий статусов. Локальные проверки
на итоговом коде: полный reactor verify (3484 теста, 0 пропусков), пороги покрытия (studio 98,6/92,8), diff-cover 98 %,
совместимость Platform API и OpenAPI (обратно совместимо), веб — аудиты, 2842 теста, покрытие и сборка, a11y 157/157,
E2E 41 + 36 и карантин, docs/hygiene/rulesets, gitleaks — без находок.

**Конструктор, этап 1, ветка 3 — раскладка, заголовок записи и отчёты (2026-10-18, прямо в
`main` поверх `eb06c03b`, не запушено — пушит координатор):** ADR-0041, «Этап 1, ветка 3»;
этап 1 сделан, ждёт приёмки владельца. simple-form формат 4: `entity.titleField` (обязательное
текстовое поле из списка, `name` — системные name/code форматов 1–3, нет — номер записи) и `layout`
(1–10 разделов, 0 или 2–6 вкладок; компилятор: системные поля первыми, `orgUnit` последним, история —
последней вкладкой). V224: `studio_records.name`/`code` nullable, `record_number` по форме
(`studio_form_counters`, триггер). `form-meta.titleField` (`common.entity.EntityTitles`,
`StudioTitles`); ссылки на живую форму называют строки её полем заголовка (`StudioSources`). Перенос
записи между подразделениями: `orgUnit` редактируемый, `GET /api/v1/iam/org-units/scope`,
`EntitySaveChecks.unit` требует прежнее и новое подразделение в области (для всех сущностей с областью
по подразделению). Отчёты: `sort`/`limit` (`QueryAggregateOrders`, `QueryAggregate.Order`), подписи групп
`labels` (`EntityReportLabels`, словарь — `common.entity.EntityTexts`/`MdEntityTexts`), XLSX пишет
подписи; каталог `GET /api/v1/report-views?q&owner&kind` + `owned`. Веб: блок «Заголовок записи и
раскладка» в редакторах, вкладки в форме записи (`entity-form-tabs.ts`), каталог `/reports`,
порядок/top-N в конструкторе, переход к записям (`drillFilter`). E2E эталона:
`e2e/tests/browser/instance/studio-regions-report.spec.ts`. Дальше — этап 2 (формулы, условия,
нумерация, строки документа, статусы; pivot, параметры, рассылки).

**Конструктор, этап 1, ветка 2 — справочники, ссылки и область данных (2026-10-17,
`claude/stage1-references` от `739732c6`, слита в `main` — `eb06c03b`):** ADR-0041, решения владельца 1 и 4.
V221 `studio_records.org_unit_id`, V222 `studio_lists`/`studio_list_items` (коды постоянны,
значения отключают, не удаляют), V223 права `studio.lists` `view`/`manage`. API
`/api/v1/studio/lists` (If-Match, аудит). Справочник — источник перечислений runtime
(`common.entity.EntityEnumSource`, `StudioListEnums`, ссылка `studio.list.<код>`), подписи на
языке работы (`common.web.RequestLanguage`). simple-form формат 3: `select` (варианты),
`ref` на справочник или живую форму, `user`, `entity.scope` (`all`/`org_unit`/`owner`,
меняться не может). Platform API: партиционированная сущность держит `SELECT`/`ENUM`/`REF`.
Экспорт XLSX пишет имена ссылок (`EntityReads.labelled`). Вебхуки областью формы не
ограничены (решение в ADR). Веб: «Справочники» в настройках, новые типы и видимость в
редакторах. E2E эталонов: `e2e/tests/browser/instance/studio-references.spec.ts`
(чтение XLSX — `e2e/support/xlsx-read.ts`). Дальше: раскладка, отказ от name/code, отчёты.

**Конструктор, этап 1, ветка 1 — версии и архив живых форм (2026-10-17,
`claude/stage1-form-versions` от `b3c8c5a2`, слита в `main` — `739732c6`):** ADR-0041 решения 1–4, 6, 7.
V218 `studio_form_versions` (версии ≥ 2, неизменяемы; версия 1 — строка
`studio_forms`), V219 `studio_form_state` (активная версия, `active`/`archived`,
ревизия), V220 `studio_records.form_version` (ставит триггер БД). simple-form
формат 2: `hidden`, `defaultValue`, `entity.menu`; совместимость —
`StudioChangeRules` (422 `error.studio.change_incompatible`, поле + изменение).
Фиксированное умолчание читается для записей без ключа прямо в SQL JSON-свойства
(`JsonPropertyCasts`, без изменения сигнатур Platform API). Архив — общий
`EntityWriteLock` в `EntityGate` и bulk (409 `error.studio.archived`), form-meta
без действий, без меню, вне лимита 100; всего ≤ 500 форм. История живой формы
показывает только названные поля (`RecordHistorySource.namedFieldsOnly`). API —
`/api/v1/studio/forms` (список, карточка, `versions`, `archive`, `restore`, If-Match);
веб — «Мои формы» и редактор версии; клиент не шлёт Idempotency-Key этим
операциям. Дальше: справочники и ссылки, область данных, раскладка (ADR-0041,
решения владельца 1–4), выгрузка в код (решение 5).

**CI только вручную (2026-10-17, `claude/ci-manual`):** автоматические триггеры
workflow сняты, `main.json` больше не требует status checks и code scanning —
после merge администратор применяет rulesets заново (`apply-rulesets.ps1 -Apply`).

**Живые формы: устойчивость и исправления ревью (2026-10-17,
`claude/live-forms-review-fixes` поверх `d344bb4c`):** сохранённая форма, которая
не загружается, пропускается (лог, `smc_studio_forms_broken`, health `studioForms`
вне readiness), оператор выводит её карантином (V216, `/api/v1/studio/quarantine`).
Исправления ревью: история и экспорт берут источники сущностей из реестра на каждом
вызове (форма в карантине — 404, не в `GET /api/v1/history`); права некомпилируемой
формы выводятся из сохранённого кода и не устаревают при рестарте; метрики держат
последний скомпилированный каталог, уведомление перекомпилирует каталог сразу;
счётчик карантина — все строки; повтор активации формы в карантине/сломанной — 409
(`Api-Breaking`), новая форма с перекрытием ключей — 422; алерт
`SmartupcmsStudioFormsBroken` + RB-13; `ResponseStatusDeclaredTest` и
`PlatformModulesReservedTest` ужесточены. Лимит 100 форм снимает архив этапа 1
ADR-0041. Миграций нет.

**Объединение веток (2026-10-17):** владелец разрешил объединить все ветки
в `main`; прежнее ограничение «без merge» заменено этим указанием.
Публикация релиза/образов, release tags и облачный deploy не разрешены.
В основном checkout создана `codex/unify-branches` от
`d406d6963bd01c0b4359d53b251eb8286ac930e5`. Через DCO merge-коммиты включены
`codex/admin-studio`, старые release/staging ветки и пять веток Dependabot;
прочие локальные ветки уже входят в эту историю. В 23 конфликтах старого
staging сохранены проверенные современные реализации: старые исправления
уже перенесены, после слияния tree полностью совпадал с `72ebe545`.
Конфликт двух Jackson updates разрешён сохранением обеих версий:
Jackson 3.2.3 / 2.22.3; также AWS SDK 2.55.13, Spotless 3.10.4,
CodeQL action 4.38.3 и Playwright 1.64.0.

Dirty worktree `release-p0-checks` сохранён: 225 изменений сопоставлены с
историей Studio, уникальных продуктовых/тестовых правок не найдено.
Не накатывать его повторно: в частности, прежняя V204 tasks уже перенесена
в V205 с тем же содержимым. Работающее демо остаётся в прежнем отдельном
worktree; его данные и runtime не меняются объединением Git.
В `main` создан DCO merge-коммит
`17fcecd865192117b6fdc7e13b56c9e039ecbd6b` (`--no-ff`): его tree полностью
совпадает с проверенным `886d47a2c20f4166364b4cacc4d63315c0abbd42`.
Перед merge повторный fetch подтвердил включение всех локальных и удалённых
веток; исходный `origin/main` оставался `d406d696`.

На неизменяемой копии `886d47a2` выполнен свежий полный локальный CI:
116/116 обязательных checks; backend 3306, frontend 2579, accessibility 143,
live E2E 72 (37 + 35), retries=0. Пройдены четыре large-профиля, включая
50 млн записей аудита, recovery/upgrade/egress drills, CLI и onboarding
Windows/Linux; запуск с нуля — 188/228 с при лимите 600 с. Финальный validator
проверил SHA/tree, reactor snapshot, архивы Linux, шесть immutable image IDs,
свежий security review и очистку собственных CI-ресурсов. Демо readiness — UP.

CodeQL 2.27.2: 44 сигнала разобраны по текущему коду; 56 Java omissions —
неисполняемые `package-info.java`. Native Actions на Windows блокируется
ExecutionPolicy; официальный suite выполнен через JavaScript extractor,
отдельный Workflow AST proof подтвердил 8/8 workflow. Неудачный native probe
сохранён. Build-mode none не доказывает полноту внешнего Java classpath.
ZAP: разобраны 16 записей; пять ранее документированных остаточных рисков
сохранены, новых исключений не добавлено. Неавторизованный HTTP baseline
ограничен rate limiting и не заменяет staging/HTTPS-приёмку.

Evidence и итоговый отчёт:
`.local/merge-all-ci-20261016-r2/.local/merge-all-ci-20261016-r2/`.
Инвентаризация веток, fresh triage и merge/push receipt:
`.local/merge-all-20261016/`. Этот handoff обновлён после merge; для него
выполняются отдельные проверки документации. Полный CI относится к `886d47a2`,
а статус push и hosted CI следует проверять по remote refs и receipt.
`graphify-out/graph.json` сохранён, но не обновлён: исполняемый `graphify`
и модуль Python в окружении отсутствуют. Ветки и рабочие копии не удалялись.
Release tags, публикация образов, облачная приёмка и production GO не выполнены.

**Историческая подготовка PR (2026-10-16, заменена объединением выше):** владелец разрешил PR
`codex/admin-studio → main`, проверку CI и исправление подтверждённых ошибок;
на этом этапе merge, release tags и публикация релиза/образов не были разрешены.
Опубликованный исходный HEAD этой задачи — `012455c626a4c2d3b163796cf4bba90e3183d40e`,
base — `d406d6963bd01c0b4359d53b251eb8286ac930e5`.
Публичный GitHub API при подготовке не нашёл PR и Actions runs этой ветки;
CLI, GitHub connector и браузер не авторизованы. Для продолжения нужен вход
в GitHub; SSH push работает, но не даёт доступа к созданию PR через API.
Описание PR подготовлено локально; после создания обязательно проверить
все 15 required checks на фактическом head SHA, а не переносить результаты RC.

Обнаружен и исправлен пробел PR-документации: модель угроз пересмотрена
для Studio, live forms, общих отчётов/datasets/panels, отключённого LLM gateway
и signed prerelease/verifier. Это сверка описания с исходниками и тестами;
cloud signing, staging acceptance и production GO этим не доказаны.
Продолжение и профильные результаты — `.local/pr-admin-studio-20261016/EVIDENCE.md`.
При возобновлении сначала проверить авторизацию и существующий PR по ветке,
чтобы не создать дубликат. Предыдущий полный CI по-прежнему относится только
к `4a721df0`; текущие documentation-only изменения не создают новый RC.

**Локальный стенд и уборка (2026-10-16):** демо пересобрано из текущего
`codex/admin-studio`; UI `http://localhost:4201`, API `127.0.0.1:8081`,
readiness `127.0.0.1:9091`, Mailpit `localhost:8026`. UI/API/почта — HTTP 200,
readiness — UP. PostgreSQL `5434` и Typesense `8109` привязаны к loopback;
Compose project `smc-rc-demo-b859da89` сохранён вместе с базами и файлами.
На этой workstation запускать из актуального worktree через ignored
`.local/demo-instance.ps1 up -Rebuild` (штатный run-local с локальным
loopback overlay); этот экземпляр остаётся привязан к своему worktree.
Административные credentials остаются локальными и в Git не попадают.

Удалены неиспользуемые копии зависимостей старых локальных CI, устаревшие
UI-логи, Python-кэши и остатки E2E. RC/evidence, runtime/storage, секреты,
Docker volumes и журнал deployment history сохранены. `.dockerignore`
исключает deployment history и browser-test outputs; действующие документы,
fixtures, lockfiles и manifests не удалялись. Манифест уборки, команды запуска
и результаты проверок: `.local/local-start-cleanup-20261016/EVIDENCE.md`.

Владелец разрешил DCO-коммит и push рабочей ветки `codex/admin-studio`.
Это не разрешение на release tags, публикацию образов/релиза, staging deploy
или merge в main. Последующие изменения документации и build-context ignore
не создают нового RC: результаты 116/116 ниже относятся только к `4a721df0`.
SHA рабочего коммита и результат push проверять по Git и приватному receipt.

**Текущий RC и staging-план (2026-10-16):** DCO-кандидат
`4a721df037e8274c3f1be56718e0319fec88c3dc` включает STU-00…06,
prerelease pipeline/verifier (ADR-0040) и CRLF-исправление проверки workflow.
По первичному протоколу `final-proof.json` на этом SHA выполнены **116/116**
локальных checks: backend 3306, web 2579, accessibility 143, live E2E 72
(37 + 35), без retries/quarantine. Указатель на приватные доказательства —
`.local/rc-prerelease-final-20261016/EVIDENCE.md`. Результаты `bc559ddd`/108 checks
не переиспользованы; кандидат `9fceb040` отклонён из-за CRLF до нового полного CI.
CodeQL: 44 сигнала разобраны, подтверждённых блокеров нет. Actions использовал
официальный suite через JS DB + Workflow AST 8/8 после отказа native extractor
из-за Windows Execution Policy; реальный hosted signing этим не доказан.
Пять ранее документированных ZAP CSP/origin residual risks остаются видимыми.

Breaking-контракт: `If-Match` для list-views, явное `updateExisting: true` для
изменения участника проекта; API/SPI 2.0.0 и новый sealed `FieldSource`
JsonProperty требуют обновить exhaustive switches. Суффиксы 13 OpenAPI
operationId изменены, HTTP-пути сохранены.

Обновлены [staging-план](ops/staging-acceptance.md) и
[матрица Студии](ops/studio-staging-acceptance.md): PRC-01…07 проверки
подписанного prerelease, IN-01…10 доступов, STG-STU-01…17 и go/no-go.
Эта документация изменена после RC; её revision фиксировать отдельно,
target scripts запускать из чистого checkout `4a721df0`.
Подготовлена [инструкция настройки и минимальных доступов](ops/staging-access-setup.md):
GitHub environment/rulesets/consumer credentials, Hetzner SSH без обязательного
API token, Cloudflare zone read + account R2 read и раздельные S3 keys.
Approved vars до разрешения публикации отсутствуют; R2 account read не считается
metadata-only доступом. Выдача прав и облачные настройки не выполнялись.
Ресурсы/бюджет, RPO 24 ч / RTO 2 ч и upload 30/60 с утверждены ранее.
Следующий gate: конкретный свободный RC-tag/repository, protected environment
с независимым reviewer, разрешённая публикация и verifier реальных assets;
затем target preflight, функционал/нагрузка/fault/restore. GitHub settings,
подписи/registry digest и staging-доступы пока не предоставлены/не выполнены.
DOD-09/ACC-20 открыты. Assistant off/provider none; live LLM вне scope,
ACC-21 N/A для create-only definitions. Публикации/merge/deploy не было.
Более ранние записи ниже исторические и не отменяют текущий RC/CI.

**STU-06: графики, datasets и помощник (2026-10-16):** реализовано и проверено локально в той же ветке `codex/admin-studio`.
[ADR-0039](adr/ADR-0039-report-datasets-and-assistant.md), [план §15](architecture/admin-studio-plan.md#15-stu-06-дополнительные-графики-datasets-и-помощник), [рецепт](cookbook/report-datasets-and-assistant.md).

Общий renderer поддерживает line/horizontalBar с точной таблицей. Доверенный REF N:1 dataset `ms.tasks.projects` выбран владельцем: одна строка на задачу, даты проекта с отдельными RU/UZ/EN подписями, свежие права/scope/поля при metadata/run/save/catalog/panel/XLSX. Задача без доступного проекта остаётся с NULL; без target.view dataset закрыт. Период панели разрешается в каноническом dataset сохранённого отчёта.

`LlmProvider` и OpenAI-compatible HTTPS transport подключаемы и выключены по умолчанию. V215 добавляет assist; требуются оба права и текущие credentials до/после ответа. Строгий bounded ввод/ответ, таймаут, лимиты, отсутствие retry/replay/logging содержимого. Только проверенная simple-form декларация без persistence/пакета/активации; UI применяет предложение явно и защищает смену пользователя, отзыв прав, поздние ответы и импорт файла. Внешний LLM не вызывался.

Полный clean reactor на сверенной копии: **3302 тестов / 438 suites** (server 3198), failures/errors/skips=0; no-skips/floors PASS, Studio 98,2%/93,1% при floors 98%/88%. Diff coverage **97%** (2760 строк, 57 missing) с новыми Java-файлами. API/types/Spectral, API/SPI compatibility probe и документационные проверки PASS. Web **2579/349**, все audits/typecheck/lint/build/floors PASS, **3308** переводов. Browser/Axe **37/37**, затем **8/8** для финальных mobile viewport-снимков, без retries; production frontend со stub API. Реальные PostgreSQL/HTTP/XLSX проверены сервером. CLI unit **110/110**. Независимое ревью закрыто, Gitleaks исходников/ветки — no leaks.

Один ложный assertion каталога исправлен перед чистым повтором. EXPLAIN задач/проектов SELF/SUBTREE на 10 задачах проверяет корректность, не целевую ёмкость. DOD-09/ACC-20, полный release/security CI и live E2E, staging SLO/backup/restore и реальный LLM provider smoke остаются открытыми. Будущий DCO-коммит должен иметь собственный `Api-Breaking:` для list-views из STU-03. Коммит/публикация/merge не выполнялись; прежняя работа и demo JAR сохранены. Graphify недоступен, новые инструменты не устанавливались. Точная матрица и логи — в плане §15.


**STU-05: новые формы без выпуска приложения (2026-10-15):**
реализовано и проверено локально в той же ветке `codex/admin-studio`.
[ADR-0038](adr/ADR-0038-live-simple-forms.md),
[план §14](architecture/admin-studio-plan.md#14-stu-05-новые-формы-без-выпуска-приложения),
[рецепт активации и восстановления](cookbook/live-simple-forms.md).

В существующем `/settings/form-studio/new` добавлена отдельная активация
`studio.*`; CLI/Flyway остаётся по умолчанию. Фиксированное хранилище
создано V211–V214; runtime выполняет только DML, общий EntityRuntime,
QueryList, история, отчёты и экспорт сохранены. Серверный partition
разделяет записи; права и аудит создаются атомарно с определением.
Точный повтор безопасен, конфликт даёт 409. Права публикации и CRUD
разделены; первоначальный доступ только admin/chief_admin.

Активация выключена по умолчанию (`SMC_STUDIO_ACTIVATION_ENABLED=false`).
Создание разрешается отдельно от использования; module off закрывает обе
возможности. До 100 неизменяемых форм, 20 дополнительных полей и 10 типов;
без изменения/удаления схемы, hooks, REF, FILE, коллекций и глобального SEARCH.
Редактор не настраивает owner/org-unit scope. Права проверяются заново;
переводы и меню обновляются после активации и при смене прав.

Полный clean reactor на SHA-256 сверенной копии: 3249 тестов /
424 suites (server 3145), без failures/errors/skips.
No-skips/floors PASS; Studio coverage 98,6%/88%, floors 98%/88%;
diff-cover всей ветки с новыми Java-файлами 97% (2289 строк, 46 missing).
API/SPI 1.1.0, baseline 1.0.0 сохранён; japicmp и mutation probe PASS.
CLI full smoke: 110 CLI unit-тестов, 285 тестов / 31 class сгенерированной поставки и 42 теста внешнего модуля PASS, без failures/errors/skips.
2521 web-тест/342 файла, все audits/typecheck/lint/floors PASS;
финальные production build и 13 browser/Axe сценариев без retries PASS.
Browser использует stub API; реальные HTTP/DB и фоновый XLSX экспорт
проверены серверными интеграционными тестами. Docs/API/types и secrets
проверки PASS, независимое ревью закрыто.

ACC-17 проверен независимыми реестрами и реконструкцией с новым пулом БД
в одном JVM; отдельные процессы/restart/network drill остаются целевым гейтом.
Локальный каталог 100 форм: p50 9 / p95 11 / max 14 мс; это не API SLO
и не ёмкость записей. ACC-20/DOD-09, полный release/security CI, live E2E,
backup/restore и RPO/RTO целевого контура остаются открытыми. ACC-21 N/A:
эволюции опубликованной схемы нет. Будущий DCO-коммит должен назвать
breaking list-views из STU-03 в собственном `Api-Breaking:` trailer.

Коммит, публикация и merge не выполнялись, предыдущая работа сохранена.
Демо работает на прежнем JAR. Graphify отсутствует, новый инструмент
не устанавливался. Логи и точная матрица DoD/ACC — в плане §14.


**STU-04: редактор новых простых форм и доставка CLI/Flyway (2026-10-15):**
изменения в той же ветке `codex/admin-studio`, без коммита/публикации/merge.
[ADR-0037](adr/ADR-0037-simple-form-cli-delivery.md),
[план §13](architecture/admin-studio-plan.md#13-stu-04-редактор-новых-простых-форм),
[рецепт доставки](cookbook/simple-form-delivery.md).

`/settings/form-studio/new`: администратор с `md.settings.update` задаёт
новый справочник, 10 типов дополнительных полей (до 20), ru/uz/en,
порядок/required/listed; общий renderer показывает проверенный preview.
Сохранение/открытие незавершённого JSON-черновика отделено от `prepare`.
Оба stateless API ограничивают UTF-8/размер/глубину и запрещают replay;
сервер не пишет исходники/DDL и не активирует сущность.

`cms form apply package.json [--dry-run]`: независимая проверка,
существующие CLI templates → EntityDefinition/Flyway/manifest/переводы/
тест/receipt; начальные grants только admin/chief_admin. Точный повтор
сохраняет ручные правки, изменённый пакет/существующая схема блокируются
до записи. Lock/CAS и восстановление при синхронном I/O failure проверены;
отключение питания не считается атомарной транзакцией файловой системы.
Сервер и CLI читают общий ресурс запрещённых имён Java/PostgreSQL/runtime/
Windows. Исправлены `.formOnly()` для listed=false, восстановление
незавершённого ввода, отмена discard-диалога, Unicode-лимит подписи и
экранирование названия в Markdown-карте модулей.

Полный clean reactor на сверенной копии: 3187 тестов / 408 suites
(server 3088), failures/errors/skips = 0; no-skips и floors PASS;
diff-cover всей ветки с новыми Java-файлами — 97% (1842 строки, 40 missing).
Финальный полный CLI smoke: 102 CLI unit tests, 285 тестов/31 class
созданной поставки + 42 внешнего модуля PASS, без failures/errors/skips.
Полный web-набор после Unicode: 2490 тестов/340 файлов, все audits,
typecheck/lint/build и floors PASS. Browser production build со stub API:
17/17 PASS, включая реальный клавиатурный ввод Unicode, mobile и Axe.
Документационные контракты 8/23, public-docs и repository hygiene PASS;
независимое ревью закрыто. Подробные результаты и применимость DoD — в плане §13.

Ограничения: create-only, без обновления существующей схемы, hot activation
и общей серверной истории новых схем. ACC-15 реализован консервативным
отказом, ACC-17/21 N/A до появления соответствующей эволюции/активации.
Полный release CI/live E2E/staging остаются гейтами RC, этот этап их не заменяет.
Демо-сервер остаётся на старом JAR; установка новых инструментов не выполнялась.
Graphify update недоступен: `No module named graphify`.


**STU-03: общие отчёты, XLSX и именованные панели (2026-10-15):**
реализованы в той же ветке `codex/admin-studio`, без коммита/публикации/merge.
Граница — [ADR-0036](adr/ADR-0036-shared-reports-and-dashboards.md),
DoR/DoD/ACC и результаты — [план Студии §12](architecture/admin-studio-plan.md#12-stu-03-dor-и-план-исполнения).
Это изменяемые пользовательские настройки с CAS, не активация immutable-пакетов STU-02.
Каталог общих отчётов постраничный; sharing по активным ролям, свежие scope/поля
при запуске и экспорте. Панель хранит до 12 источников, порядок/ширину и mapping
общего периода (DATE включительно, INSTANT UTC). Закрытый источник не раскрывает
определение; сбой одной карточки изолирован. V210/manifest добавляют служебные
таблицы md. До 20 панелей владельца с сериализованным созданием; CAS и аудит атомарны.

Агрегированный XLSX использует исходные группы, а не «Остальные» графика;
сохраняет валюты/NULL/точность и metadata усечения. Сохранённый источник не теряет ID
при запуске/скачивании: отзыв доступа не обходится копией запроса.
Breaking API: изменения/удаление list-views требуют `If-Match`, `lockVersion`
удалён из тела; mutations list-views/dashboards не принимают Idempotency-Key.
CHANGELOG обновлён; будущий DCO-коммит должен содержать собственный `Api-Breaking:`.

Проверки STU-03: полный clean reactor PASS — 3134 теста / 404 suites
(server 3035), 0 failures/errors/skips; coverage floors PASS, diff 98%
(1614 изменённых строк с новыми Java-файлами). Полный web 2460/335 PASS,
все audits/lint/typecheck/build и floors PASS; 18 browser/a11y PASS со stub API.
После финального UI/EN sync в проверенную копию — 6 архитектурных/catalog tests PASS.
Мобильное перекрытие карточки подтверждено отдельным RED и исправлено с geometric GREEN.
Docs 8/22, hygiene/public-docs и CLI30 PASS; независимое ревью закрыто.
Логи `.local/studio03-*`, full reactor `.local/studio-03-server-verify-final.log`;
сверенная исходная копия (2923 файла) — `.local/studio-03-verify` основного checkout.

Это локальная приёмка этапа: полный release CI/live E2E/staging ещё нужны перед RC.
Демо-сервер не перезапускался, его старый JAR ещё не содержит новые API.
Graphify недоступен (`No module named graphify`), зависимости не устанавливались.
Отдельный P2: статически найденная старая гонка квот личных report/widget views;
сначала воспроизвести конкурентным тестом. Квота новых панелей проверена и защищена.
STU-04 описан выше; активацию новых сущностей не добавлять неявно.


**STU-02: черновики, версии и пакет (2026-10-15):** реализован
[ADR-0035](adr/ADR-0035-declaration-lifecycle.md) для того же прототипа
`example.products`: общий серверный черновик с If-Match/CAS, неизменяемые
версии, история страницами, скачивание и проверка пакета с SHA-256,
зависимостями и сравнением с доверенной версией в БД. V209 добавляет
служебные таблицы внутри md; EntityDefinition/EntityRuntime и рабочие
формы не активируются. Все семь API-путей повторно проверяют полномочия,
модуль и видимость; глобальный idempotency replay исключён.
UI различает локальный ввод/сохранённую ревизию, сохраняет ввод при 409,
подтверждает reload и отдельно восстанавливается после изменения зависимостей.

Итог: полный чистый Maven reactor 3057 тестов / 396 suites
PASS (server 2958, без failures/errors/skips), SHA256-проверенная копия
2858 файлов; no-skips/floors PASS, branch diff coverage 98%.
2410 web-тестов / 325 файлов PASS, все web-аудиты/typecheck/build PASS;
10/10 Chromium/a11y со stub API PASS. OpenAPI 11 PASS, web types и
совместимость API с main PASS. Docs contract 8/22, hygiene/public-docs и
CLI unit 30/30 PASS. Последние интеграционные исправления закрыты
155 целевыми тестами и повторным полным прогоном; baseline/floors не ослаблялись.
Полный журнал DoR/DoD/ACC и ограничения — [план §11](architecture/admin-studio-plan.md#11-stu-02-план-исполнения-и-dor).

ACC-10 и часть ACC-11 о подготовке проверены; активация, межконтурный импорт,
ACC-17/21 и полный release CI/live E2E/staging остаются будущими гейтами.
V209 нельзя откатить одним старым бинарником: SchemaVersionGate проверяет
схему; порядок forward fix/подтверждённого restore описан в ADR.
Демо не перезапускалось, прежний JAR сохранён. Коммит/публикация/merge не
выполнялись. Graphify update недоступен (`No module named graphify`),
зависимости не добавлены. Следовавший за ним STU-03 описан выше.
Логи: `.local/studio-02-server-verify-final.log`, `studio-02-final-summary.json`,
`studio-02-diff-coverage.log`, `studio-02-web-coverage.log`,
`studio-02-browser-final.log`; отчёты reactor — `.local/studio-02-verify`
основного checkout.

**STU-01: декларация формы и прототип (2026-10-15):** реализованы
[контракт v1](architecture/admin-studio-contract.md), JSON Schema/пример и
`Настройки → Студия форм` (`/settings/form-studio`) для `example.products`.
Администратор меняет ru/uz/en, секции/порядок, проверяет через сервер,
скачивает и импортирует JSON. Renderer общий; значения preview синтетические,
обычная форма/данные/EntityDefinition не меняются. На момент STU-01 черновики в БД были отложены; STU-02 выше
добавляет их хранение, а активация остаётся будущим этапом. Лимиты: 32 KiB UTF-8, глубина 8, 8 секций, 64 поля;
allowlist, точное покрытие доступного layout и актуальные права обязательны.
GET `/api/v1/form-studio/prototype` и POST `/api/v1/form-studio/preview`
требуют `md.settings.update`, активного `example` и доступа к форме.
`@NoIdempotency` запрещает запись/replay preview, включая варианты URL;
импорт проверяется сервером как исходный JSON. Ошибки не теряют черновик,
несохранённый ввод защищён, экспорт доступен только после проверки.

STU-01 локальные результаты: 2376 web-тестов / 322 файла PASS,
lines 92,42% / branches 81,83%, floors PASS; целевой серверный набор 128 PASS,
OpenAPI 8 PASS и web types обновлены; проверка совместимости с main PASS. Browser/a11y 6/6 со stub API PASS;
независимое ревью закрыто. Полный `clean spotless:check verify` PASS на
SHA256-проверенной копии: 3002 теста / 391 suite, без failures/errors/skipped;
server — 2903. No-skips, module coverage floors PASS, branch diff coverage 97%.
Первый clean был заблокирован JAR работающего демо. Полный прогон выявил
привязку UI к entity code: устранена без расширения архитектурных исключений.
Два новых OpenAPI-теста воспроизвели строковый enum числовой версии и отсутствие
required; после исправления DTO чистый целевой прогон 11/11 PASS. Логи —
.local/studio-01-server-verify-final.log, studio-01-openapi-schema-red.log и
studio-01-openapi-schema-green-clean.log. Исходная копия с JaCoCo/Surefire —
.local/studio-01-verify основного checkout. Полный release CI не заявляется.
Демо пока использует прежний JAR; live/staging-приёмка прототипа не заявляется.
Окончательные проверки/DoD и их ограничения — [план §10](architecture/admin-studio-plan.md#10-реализация-stu-01).
Коммит, публикация и merge не выполняются.

**Студия: план и STU-00 (2026-10-15):** ветка
codex/admin-studio от main доведена до существующего RC b859da898b98;
предыдущие незакоммиченные UI-изменения сохранены.
Рабочий [план](architecture/admin-studio-plan.md) содержит 8 пунктов DoR,
10 пунктов DoD, 21 ACC и этапы STU-00…06. Он не меняет статус ТЗ/ADR
автоматически; новые сущности без deploy требуют отдельного решения.

STU-00 реализован локально: ошибка списка виджетов имеет отдельные
loading/error/empty состояния и доступный retry через UI kit.
Графики различают raw ID групп, не складывают непредставимые средние,
минимумы, максимумы и разные валюты; сохраняют точную таблицу при fallback,
включая виджеты. Явная валютная группировка тоже проверяется; неполная
матрица неаддитивных групп не создаёт искусственные нули. NULL KPI — «—».
API, Java-код и миграции не изменялись.

Проверки окончательного изменения: 2351 web unit test / 318 файлов PASS,
28 новых регрессий; coverage lines 92,37%, branches 81,90%, floors PASS.
Production build, lint, web/E2E typecheck, шесть web-аудитов PASS.
10 Chromium/a11y сценариев production-build со stub API PASS (6 новых,
обе темы, mobile, клавиатура). Docs contract (8 тестов / 22 документа),
public-docs и hygiene PASS. Дефекты сначала воспроизведены RED; независимое
ревью закрыто после исправления явной валюты и неполных групп. Логи в
игнорируемой .local/studio-*.log. Полный release CI и live/staging-приёмка
не повторялись; коммита, публикации и merge нет. Graphify update недоступен
(No module named graphify), новых инструментов не устанавливали.
Демо-контейнеры этим этапом не пересобирались; проверена production web-сборка
на отдельном браузерном стенде. Результаты STU-01…04 описаны выше; следующие этапы — по своему DoR.


**Раздел «Примеры» (тот же рабочий UI-кандидат, 2026-10-14):**
`example.orders`, `example.products`, `example.requests` перенесены из
`workspace` в `examples`; раздел появляется по активному модулю и правам
просмотра, работает в обычном меню и rail. `module="example"`, маршруты,
серверные ограничения и отключённое по умолчанию состояние сохранены,
миграций нет. Устранена обходная ссылка на `example` в общем разделе модулей,
когда сервер не возвращает ни одной сущности из-за отсутствия прав.
RU/UZ/EN, packaged Russian, cookbook и описания меню обновлены.
Проверки именно этого изменения: 102 unit-теста навигации/модулей,
18 браузерных сценариев со stub API (обе темы, клавиатура, мобильное меню,
модуль выключен, нет прав), 10 серверных тестов (`EntityGateModulesTest`,
`ExampleOrderDocumentIntegrationTest`) — PASS. Web/E2E typecheck, lint,
i18n audit, production build, Java Spotless, docs contract и гигиена — PASS.
Регрессии группировки и обходной ссылки сначала падали, затем прошли.
Демо `localhost:4201` пересобрано; вручную открыты все три списка из «Примеров»,
в «Модулях» доступен переключатель `example`. Данные сохранены, новых миграций
нет; ошибок/предупреждений браузерной консоли при проверке нет. Временный
viewport сброшен. Graphify снова недоступен (`No module named graphify`).
Полный release CI здесь не повторялся; коммита, публикации и merge нет.


**UI/UX форм (рабочее дерево, 2026-10-14):** ветка `codex/auth-ui-compact`
поверх `b859da89` содержит компактный вход/восстановление и текущие исправления
общих таблиц, пагинации, клавиатурного изменения колонок, вкладок и мобильных
контролов. Заметки и дополнительные поля различают ошибку загрузки и пустой
результат; ответ старой страницы заметок не попадает в новый фильтр. Настройки
поиска сохраняются через native form, отчёты используют общий UI-кит;
полноэкранный iframe помещается на 360×640. Уточнены
[единый стандарт](guidelines/forms-one-page.md) и
[реестр форм](guidelines/forms-catalog.md). Архитектура платформы сохранена; изменения контракта участников описаны ниже.
Дополнительный проход: поиск разрешённых пунктов меню с сохранением прежнего
раскрытия; компактные фильтры и создание задачи с сохранением свёрнутых значений,
сводкой и раскрытием серверных ошибок; явно обозначенный полный экспорт.
Общая metadata-форма распределяет ширину по типу/длине поля и использует одну
колонку до 600px ширины контейнера. Фокус ошибок поддерживает составные поля
и contenteditable overrides; у input один контур, меню ограничено viewport.
Стандарт форм обновлён до 1.4; удалены устаревшие строки и две записи i18n-baseline.
Проход наполненного демо: 23 раздела меню на 1280px/360px, восемь вкладок
настроек, создание девяти metadata-сущностей, просмотр и доступное
редактирование их записей; общие фильтры, колонки, импорт и отчёты.
Исправлены переполнение страницы скрытой таблицей диаграммы и checkbox,
высота rail-меню на низком экране, мобильные действия файлов/заметок/аудита/
дерева/динамических полей, switch 44px, вложенный h2. Ошибка чтения меню
не выглядит пустым каталогом. Drawer начинает фокус с содержимого, опасное
переключение роли — с продолжения редактирования. Поиск участников
повторяется после очистки; загрузка, ошибка и пустой результат различаются.

Участники: UI использует поддерживаемые R/W (по умолчанию R). Подтверждён
и устранён случайный upsert существующего участника вне первых 50 строк:
`add_member` теперь add-only по умолчанию, duplicate — field422 без изменения
доступа, ревизии и аудита; явное обновление требует boolean `updateExisting: true`.
UI отправляет false и отображает `params.userId` под полем. Семантика API
изменилась: запись в CHANGELOG/руководстве есть; будущему коммиту требуется
`Api-Breaking` trailer. OpenAPI и web-типы регенерированы, структурного diff нет.

Проверки рабочего дерева: 317 файлов / 2320 web unit-тестов, покрытие строк
92,35% и все пороги — PASS; typecheck, lint, web audits, production build,
E2E typecheck, 94 браузерных сценария a11y/адаптивности со stub API — PASS.
Сервер: 68 выбранных тестов проектов, прав, пагинации участников и OpenAPI,
Spotless/Checkstyle — PASS, пропусков нет. Новые регрессии сначала подтвердили
ошибки, затем прошли после исправления. Один ранний unit timeout отчётов
и ERR_NO_BUFFER_SPACE Chromium не повторились в отдельных и полных прогонах;
они не объявлены проблемами базовой ветки.
Демо localhost:4201 пересобрано после финальных проверок: UI HTTP200, health UP,
обе БД без новых миграций; диалог участников показывает Чтение/Редактирование.
Viewport браузера возвращён к обычному размеру, ошибок консоли при проверке нет.
Документация и гигиена репозитория проверены. Graphify не обновлён: в локальном
Python отсутствует модуль; новые инструменты для этого не устанавливались.
Это не полный release CI: полный server verify и оба E2E shard с реальным API
в этой UI-задаче не повторялись. Сохранение/удаление записей и изменение прав
вручную на наполненном демо не выполнялось; запись проверялась на изолированной
тестовой БД. Изменения не закоммичены, публикации и merge нет. Для нового RC
нужен отдельный полный CI на его SHA; прежний RC не включает эти изменения.

**P1 — целостность значений:** поверх RC `ea7e778b` в ветке
`codex/preproduction-readiness` исправлены потеря точности неизменённого
DATETIME в общей форме (включая строки коллекций), отправка readonly-полей
в PATCH, переполнение числового REF и обязательность полей после hooks.
Числовые ссылки проверяются по `QueryRef.keyField=id` до hooks; строки со
знаком проходят тот же диапазон `long`, текстовые `code` сохраняются.
Итоговая запись после hook/action проходит правила полей и условий видимости;
временное очищение с восстановлением и запись readonly из hook разрешены.
Добавлены unit/HTTP/browser-регрессии, архитектура и миграции сохранены.
Пакет фиксируется отдельным DCO-коммитом поверх исходного RC. Результаты
повторного полного локального CI привязываются к SHA нового кандидата
в отдельном локальном отчёте; прежние проверки рабочего дерева не заменяют
этот прогон. Публикация и merge не выполнялись.

**Последовательное закрытие аудитов:** в том же кандидате добавлены проверка
текущих прав FILE/IMAGE при скачивании и повторном прикреплении, обязательный
HTTPS webhook, recovery-алерты 14/20 часов и явный same-host bind профиль
`docker-compose.local-storage.yml`. Receipt алерта остаётся `UNVERIFIED`,
даже когда endpoint принял событие; preflight/failure отчёты отделяют
`automatedStatus` от итоговой приёмки. Восстановление managed установки
требует архивов CMS, DWH и объектов одного комплекта. Исправлены описания
API-токенов (права владельца) и OAuth metadata (SSO-вход не реализован).
Браузерные проверки усилены сверкой скачанных байтов и русского fallback.
Новые локальные проверки ещё не заменяют перечисленные ниже target gates.

**Состав pre-production-кандидата:** изменения собраны в ветке
`codex/preproduction-readiness` от main `d406d696`. В нём собраны изменения
RC `aa098672`, staging `26fbf590` и форм без изменения исходных checkout.
Исправлены независимые DB credentials, account/IP login budget, отзыв
доступа существующих SSE-соединений; новые миграции V206–V208. Миграция
отключаемого tasks-модуля — V205, RC V204 не переписана.
Подготовлены Ansible deployment, OPS recovery coordinator с ограничением
scratch и read-back, Alertmanager/blackbox/textfile collector, русский
[handbook](ops/production-handbook-ru.md), [Ansible guide](ops/ansible-deployment.md)
и [launch checklist](ops/production-launch-checklist.md). Результаты текущей
проверки и gap-анализ — локальные audit artifacts, не требования продукта.
Точный SHA и результаты повторного CI фиксируются в отдельном локальном
release report. До подтверждения release gates и target drills статус **NO-GO**.
Реальный staging, SSH/secret provisioning, доставка alert/dead-man,
полный R2 recovery/restore и capacity ещё не подтверждены. Публикация,
merge и внешнее развёртывание не выполнялись и не разрешены.

**Staging после RC `aa098672`:** владелец утвердил конфигурацию, бюджет,
RPO 24 ч / RTO 2 ч и отдельный upload SLO 30/60 с. Ветка
`codex/staging-acceptance` от RC обновляет `NFR-PERF-02`, capacity runner,
HTTP histogram и Prometheus; обычный API сохраняет 300/1000 мс, upload errors
остаются в доступности. Утверждённые значения, шаблоны и незакрытые target
gates — [staging annex](ops/staging-acceptance.md). Требуются новый SHA/образы
и свежие проверки; старый полный CI RC не является проверкой этих изменений.
Staging identity/secrets и реальные drills ещё не готовы; coordinator/cache
guard и короткий alert route подготовлены в текущем кандидате. Закупки, публикация
и merge по-прежнему не разрешены. Исторические результаты ниже сохранены
для трассируемости; текущие результаты писать в отдельный release report.

**История подготовки финального release candidate:** владелец разрешил DCO-коммит
накопленных исправлений и повтор полного локального CI на его SHA; публикация
и merge не разрешены. Результаты ниже относятся к предыдущему незакоммиченному
кандидату. Итоговый отчёт нового прохода должен содержать точный commit SHA,
проверку чистоты checkout, DCO trailers, команды и результаты, включая
ограничения локального воспроизведения. Отчёт хранится отдельно от исходников,
чтобы его запись после проверок не меняла проверенный commit. Staging и
решение о production выпуске по-прежнему не подтверждены.

**P0-06, локальная проверка завершена:** без staging, публикации и merge.
Исправлены checksum/path/full-bundle проверки release verifier, точность
Cosign identity и удаление старого PASS при отказе; 12 регрессий прошли.
Release drill корректно получает ошибку PowerShell и проверяет код вместе
с причиной отказа; в CI добавлены оба набора и полная Git-история этого job.
Свежий полный reactor: 2781 тест/375 suites/8 модулей, 0 failures/errors/skips;
floors, SPI/API и diff coverage 95% успешны. Web после отдельного `npm ci`:
2177 тестов/313 файлов, все audits/build и floors успешны; E2E 69 и a11y 46
прошли без retries. Trivy dependency/runtime gates, Gitleaks всей истории,
ZAP (0 High, прежние предупреждения), миграции/headers/readiness успешны.
CodeQL: прежние 28 Java/13 JS сигналов с теми же fingerprint, Actions 0.
Actions suite использует свежую совместимую JS-базу со всеми 7 workflows:
нативный extractor ограничен локальной Windows policy, её не меняли.
Полный readiness drill, CLI-сборка внутреннего и внешнего модулей, combined
restore, no-default-egress, production upgrade и live API smoke прошли.
Исправлен только диагностический вывод числа OpenAPI-маршрутов; отдельная
проверка строки даёт 171. Onboarding из чистого клона: 175 с из 600,
UI/API/readiness/admin login/16 demo-записей; кеши workstation прогреты.
Runtime fixture совпадает с кандидатом, его commit не является release SHA.
Независимое ревью: 0 Critical/Important, 1 Minor исправлен (fetch-depth);
поздняя правка вывода и документация проверены исполнителем. Evidence содержит
матрицу AC-01…AC-13, UTC/команды/логи и хеш diff; кандидат не закоммичен.
**Production NO-GO:** итоговый SHA/DCO/hosted CI, опубликованные подписи,
provenance/платформы, целевой контур и решения владельца остаются открытыми.
Основной локальный проект на `localhost:4200` продолжает работать.
Подробности и границы — в [плане P0/P1](ops/release-readiness-plan.md).

**P0-05, локальная часть:** staging ещё не готов. В capacity runner исправлены
UTC-окно и ложный PASS на NaN/пустых/недопустимых отрицательных метриках;
пороги k6 включают границу, в том числе нулевой бюджет ошибок. Prometheus
показывает нулевую долю ошибок при API-трафике без 5xx; отсутствующие API-ряды
не заменяются нулём. 22 регрессии runner с настоящим evaluator k6 без сети
и правила 33 алертов прошли; новая регрессия включена в CI. Полный локальный
capacity-guards, 26 тестов метрик/трассировки и 13 интеграционных тестов
scrape/S3/ClamAV прошли. Также прошли все 7 тестов четырёх больших профилей:
UPL по 1 млн строк в heap 512 МиБ (пик 152/335 МиБ), импорт 10 000 строк
за 22,9 с, очистка 1 млн raw-строк за 24 мс, аудит 50 млн строк с p95
8/8/<1 мс для списка/истории/итогов. Всего 143 серверных теста без ошибок
и пропусков. Большие профили используют тестовый PostgreSQL 14.22 без
durability; их замеры не являются ёмкостью production PostgreSQL 18.
SLO-документ приведён к NFR-PERF-02: только SSE исключён из задержки API,
рекомендация исключать медленные загрузки/выгрузки удалена.
Архитектура приложения и её API не менялись.
Нагрузка целевой установки, 4h soak, согласованные пороги насыщения и
доставка алерта оператору по-прежнему требуют staging и не приняты.

**Продолжение P0-03/P0-04:** владелец подтвердил, что целевой staging ещё не
готов. На отдельном локальном контуре текущий JAR прошёл реальный ClamAV
1.5.4: чистый upload/download и восстановление после отказа успешны; EICAR
и ZIP отклонены с 422, остановленный scanner — 503, метаданных/байтов и
остатков карантина после отказов нет. Локальные security headers прошли;
опубликован только web на loopback, это не доказательство target HTTPS.
Далее P0-04 выявил и исправил потерю UTC/формата даты при чтении manifest и
отсутствие восстановления ролей до проверки CMS/Flyway. Используется
существующий `backup-bootstrap`; модель ролей сохранена. Расширенный
`test-recovery.ps1` прошёл: обе БД, объекты, app/migrator permissions,
checksum/missing-key/future-date отрицательные случаи. Полная схема
кандидата также восстановлена с совпадением контрольных строк и DWH-данных,
двух объектов и их checksum, без missing/orphan; локальный RTO 18 с на
маленьком наборе не является production SLO. Финальные 42 теста повторного
исполнения прошли без failures/errors/skips: сбой после COPY без дубликатов,
lease takeover/fencing, стабильные идентификаторы повторной доставки у
HTTP-получателя и почтового провайдера. Production configuration и managed
acceptance contract прошли. Recovery-тест сам собирает образы репозитория.
Полный reactor/web/E2E/CodeQL из предыдущего прохода не запускались повторно:
изменены восстановительные скрипты и тесты. Target S3/R2, TLS/edge,
alert delivery, утверждённые RPO/RTO и нагрузка остаются открытыми.

**P0-03, локальные исправления:** на той же ветке исправлены выдача готового экспорта и
идемпотентного ответа после отзыва доступа. Сохранённые копии проверяют
текущий контекст прав, доступ к строкам и состояние модулей; экспорт —
также видимые колонки. V204 хранит зависимости записей ответа. Пустая
необязательная REF очищается без HTTP 500. Свежий review выявил лишнее
требование `view` при replay назначений IAM и комментариев; два теста
воспроизвели 409 и после исправления прошли. Целевой набор — 75/75,
включая миграционные правила. Первый полный прогон нашёл отсутствие
таймаутов новой V204; заголовок исправлен, контрольная сумма заново
добавлена штатным `MigrationManifestTest` (старые миграции не изменены).
Финальный `clean spotless:check verify` успешен: 2780/2780, 375 suites,
все 8 модулей, без ошибок и пропусков; добавлено 12 тестов. Diff coverage
текущей ветки — 189/198 строк (95%), пороги не ослаблены. API-контракт,
CLI (30 тестов и smoke), docs/hygiene/rulesets прошли. Trivy 0.70.0 по
новому SBOM (237 компонентов) не нашёл исправляемых HIGH/CRITICAL.
CodeQL Java security-extended: 124 запроса, прежние 28 сырых сигналов,
новых нет; один log sink сместился по строке с тем же fingerprint.
Новые исходники включены в базу; ограничение build-mode none/inferred
classpath сохраняется. Web/E2E/ZAP и прочие незатронутые проверки P0-02
в этом шаге заново не запускались. ClamAV на целевой установке, её
HTTPS/headers/внутренние порты и CI финального SHA не подтверждены.

**Рабочий пакет подготовки релиза:** ветка `codex/release-readiness` от
`d406d696`, пока не в `main`. По поручению владельца создан
[план P0/P1](ops/release-readiness-plan.md), привязанный к AC-01…AC-13.
Первый пакет исправляет инструкции outbox/секретов и локализацию статического
каталога прав: существующие `formNameKey`/`actionNameKey`, 76 ключей ru/uz/en,
сохранение приоритета названий сущности. Экран ролей и панель персональных
прав обновляют подписи при смене словаря, не сбрасывая несохранённые права.
Миграций, новых API-полей и изменений авторизации нет.

**Продолжение P0-02:** исправлены подтверждённые ошибки backup regression
(чтение приватных файлов тем же UID), двух web i18n-контрактов, E2E-проверки
ротированного секрета (20 символов вместо 28) и a11y fixture server
(нормализация Windows-пути `WEB_DIST`). Архитектура и пороги сохранены.
Полный `clean spotless:check verify` в отдельном worktree: 2768 тестов без
пропусков, coverage floors и SPI mutation gate успешны. После разрешения
владельца установлены необходимые зависимости CI. На Node 24.21.0: web
2177/2177, floors, build и audits успешны; после исправления логирования
diff-cover 10.6.0 — 100% (61/61), включая новые Java-файлы.
Штатные E2E-шарды на свежих БД и образах из исходников — 36+33=69,
a11y — 46/46, Playwright Chromium 153.0.8010.12, без retries и карантина.
Release config, backup regression, readiness, headers и observability успешны.
API contract и CLI 30/30 + smoke без сборки повторены; CLI также проверен
в Linux (Alpine). Штатный SBOM CycloneDX 2.9.1: 237 компонентов, версии
совпадают с набором полного backend-прогона.
Подробная матрица и ограничения — в [плане P0/P1](ops/release-readiness-plan.md).
Trivy 0.70.0 для SBOM/npm и 0.74.0 для 6 runtime-образов на свежих базах:
0 HIGH/CRITICAL с исправлением; dependency scans включают dev dependencies
и стандартный поиск секретов. ZAP web/API: 0 High и FAIL, 1 Medium,
7 Low, 6 Informational строк без новых подавлений; Medium по CSP описан в ADR-0034.
25 CodeQL-сигналов логирования разобраны по источникам и общей границе вывода
(24 URI, один `Accept`). Подтверждён CRLF из декодированного числового параметра
через MVC и `GlobalExceptionHandler`; точный URI/header-триггер каждого сигнала
не доказан. Dev-консоль теперь использует штатный `SafeTextLogFormatter`:
маскирование компонентов, затем экранирование всего события, включая стек.
ECS дополнительно экранирует U+2028/U+2029. API и архитектура сохранены.
14 новых случаев и соседние тесты — 64/64; исходный вывод давал 4 падения
из 8 проверок. Для всех 25 сигналов сохранены отдельные отчёты Codex Security.
Подавлений CodeQL нет; повторный чистый backend прошёл 2768/2768, 372 suites,
все 8 модулей, no-skips и coverage floors успешны. Свежая Java-база CodeQL
включает новые файлы; все 124 запроса успешны, те же 28 сигналов, новых нет.
Локальный triage 25 log-injection сигналов закрыт проверенной границей вывода
на исправленном кандидате; raw-предупреждения сохранены. Это не доказательство
25 отдельных атак или 25 ложных срабатываний исходного кода. После нехватки
Java heap при 2 ГБ повтор анализа той же базы с 1 потоком и 6 ГБ успешен.
`build-mode: none` сохраняет предупреждение об inferred classpath.
Первый полный прогон дал один `BindException` PostgreSQL-соединения в
`AuthenticationGenerationHttpTest`; отдельное сравнение — 35/35 на чистом
`main` и 35/35 на кандидате, без retries; повторный полный прогон также зелёный.
Причина коллизии сокета не установлена; первое падение сохранено в результатах.

**Открыто в P0-02:** исходный лог падения оргструктуры пока не получен.
Keyboard/discard-сценарий прошёл 24 раза в диагностическом Chrome 154
(8 с CPU ×6) и ещё 9 раз в штатном Playwright Chromium, без retries.
Причина прежнего сбоя не установлена и исправление не объявлено.
Rulesets GitHub по read-only API пусты; classic protection без авторизации
неизвестна. Последний проверенный CI `main` (run `38107212537`) красный
на backup/restore, E2E пропущены; это не прогон текущего diff. Нужны итоговый
SHA и CI на нём. P0-02 и разрешение production остаются открытыми.
Изменения не закоммичены; QA Compose project остановлен, пользовательский
локальный сервер не останавливался.
Обновление локального графа не выполнено: Python-модуль `graphify` отсутствует.
**Текущая рабочая ветка `codex/framework-forms` (не влита):** по решению
владельца поставка — ядро + отключаемые tasks/projects, notes и upl.
Одностраничный [стандарт форм](guidelines/forms-one-page.md) и подробный
[forms-ux-standard](guidelines/forms-ux-standard.md) описывают одинаковое
поведение общего runtime и специальных редакторов. Исправлены адресные
ошибки полей, сохранение ввода после отказа, блокировка повторного сохранения
и закрытия, даты таблиц и перенос подписей. Удалены неиспользуемый
`@angular/material` и дублирующий API-клиент настроек модулей. Данные и
прикладные возможности сохранены. Отключение модуля закрывает custom API,
entity runtime и idempotency replay; принятые UPL jobs завершаются.
Области `mf`, `notify`, `webhook` явно относятся к системным манифестам.
Три специальных вопроса об отмене изменений IAM заменены общим confirm:
безопасный начальный фокус, сохранение черновика при возврате и отмена
устаревшего вопроса при отзыве прав/уничтожении экрана. Семь дублирующих
ключей переводов удалены из ru/uz/en.
Повторный проход: общий диалог начинает клавиатурный обход с содержимого,
сохраняя крестик в заголовке; Markdown-поле имеет одно доступное имя.
Заметки и настройки поиска используют числовые даты и 24-часовое время.
Проход меню: закрытые разделы исключены из клавиатурного обхода и имеют
различимые имена; компактное меню отмечает текущий маршрут, обновляет
открытый раздел из действующего каталога, поддерживает стрелки и возврат
фокуса по Escape. Карточка задачи сохраняет активный пункт. Мобильное меню
удерживает фокус и принимает клики поверх фона; переход фокусирует
содержимое. Граница 767/768 px согласована, раскрытая панель на планшете
занимает свою ширину. Ссылка настроек без tab открывает панель по умолчанию.

Дополнительный проход форм: [реестр](guidelines/forms-catalog.md) связывает
девять сущностей, все маршруты и вложенные редакторы с исходниками и
применимыми операциями; компоненты и пользовательские формы не смешиваются
в одном счётчике. Исправлены отказ загрузки настроек уведомлений (форма
больше не подменяет сохранённые настройки значениями по умолчанию), pending,
возврат фокуса и мобильная матрица трёх каналов. Редактор переводов использует
общие кнопки, native form submit и подтверждение выхода; во время сохранения
ввод/импорт/закрытие блокируются. Переход между вкладками и со страницы
защищает языковой черновик, выбранная вкладка соответствует содержимому.
Удалены собственные стили кнопок и три неиспользуемых ключа переводов;
baseline ключей уменьшен на `common.saving`.

На этом дополнительном проходе: 2 245 web-тестов в 315 файлах, все web audits,
пороги покрытия и build; 74 browser/a11y-сценария в двух темах, включая
14 сценариев уведомлений и переводов; 3 серверных теста `MdI18nCatalogTest`.
Ручная проверка — production build с mock API на 360×800 и 1280×800:
черновик сохраняется при отказе от выхода, каналы не обрезаются, фокус
возвращается, ошибок/предупреждений в консоли нет. Это не ручная приёмка
всех комбинаций прав и данных. Graphify по-прежнему недоступен (`No module
named graphify`); новый инструмент не устанавливался.

При повторе real E2E получено точное падение `org-structure.spec.ts:529`:
после POST 201 повторный GET дерева оставался в состоянии загрузки.
Убрана гонка порядка действий теста: сетевой перехват снимается после
появления сохранённой строки, а не во время запуска GET. Проверка строки,
таймаут и запрет retries сохранены; продуктовое дерево не менялось.
После изменения 10 последовательных повторов всей группы — 70/70.
Внутренняя причина зависания браузерного запроса не доказана: упрощённый
HTTP/Playwright reproducer прошёл 100 повторов без отказа.
Финальные оба E2E-шарда после этого изменения прошли: 37 + 34 сценария,
каждый на новой отдельной Compose-базе, без retries и quarantine.
Временные стенды остановлены; исходный пользовательский стенд не менялся.
Повторены типизация E2E, docs contract, public docs, hygiene и проверка
полного рабочего diff на секреты. Это проверки рабочей ветки, не нового RC.

Эта ветка начинается от main `d406d696`, а RC `aa098672` и staging-работа
`26fbf590` сохранены отдельно. Не считать её новым RC. При последующем
объединении разрешить конфликт номера новой невыпущенной миграции
`V204__tasks_optional_module.sql` с RC `V204__idempotency_record_access.sql`,
не изменяя выпущенные миграции; затем проверить единый SHA. Публикация и
merge владельцем не разрешены. Локальная инвентаризация и результаты
проверок находятся в игнорируемой `.local/forms-review/`; это рабочие
доказательства, а не публичная спецификация.

Проверки до дополнительного прохода реестра 2026-10-12: полный Maven reactor — 2 837 тестов,
web — 2 239 тестов, a11y — 60, оба E2E-шарда на отдельных чистых базах —
37 + 34, без retries и quarantine. Пороги покрытия, web audits, build,
OpenAPI/types, docs/hygiene/rulesets и CLI unit/smoke прошли; CLI smoke
выполнен с `--skip-build`. Diff coverage серверных изменений — 100%.
Это не повтор release CI на объединённом SHA и не staging-приёмка.
После унификации IAM confirm, фокуса диалогов, дат и Markdown-подписей
повторены web/a11y и оба E2E-шарда на новых чистых базах; после очистки
каталогов — 15 серверных тестов переводов. Все прошли.
Для меню добавлены 14 браузерных сценариев в двух темах. После последнего
уточнения мобильной вёрстки повторены 87 unit-тестов оболочки, 14 сценариев
меню, типизация, lint и сборка; второй E2E-шард уже на этой сборке. Первый
шард выполнен до последнего уточнения класса компактной панели. Backend
в проходе меню не менялся; Maven относится к предыдущей проверке форм.

Тег `gate-2026-10-11` отмечает состояние main после работ 2026-10-10…11:
стандарт форм ([forms-ux-standard](guidelines/forms-ux-standard.md),
утверждён владельцем 2026-10-10; базовый список `forms-audit` пуст — новое
нарушение роняет `npm run lint`); правки по визуальной проверке (заголовок
сущности из `form-meta.titleKey`, названия областей прав
`iam.roles.area.<область>`, запрет внутренних номеров работ в текстах
каталога, шапки списков по `ui-page-header`); формы множественного числа
(`<ключ>.one|few|many|other` + `count`); названия модулей из каталогов
(`<код>.module.name`, поле `name` манифеста удалено); один активный пункт
меню (самый длинный маршрут), «Announcements» / «System status», иконки
чекбоксов кита; опрос заданий поиска уважает `Retry-After`, чтения
управления поиском — в бюджете пользователя (ADR-0008 §2.2); все 8
обновлений Dependabot (Maven 3.10.0, Angular 22.2.2, jsdom 30 и др.);
hardened-образ ClamAV (`deploy/images/clamav`, шесть образов релиза).

**Открыто в main:** локализация форм/действий `MdFormCatalog` закрывается
рабочим пакетом выше; заголовок «Новая запись» вместо «Новая задача» требует названия
сущности в единственном числе в `platform-api` (решение владельца);
прежний сигнал e2e `org-structure.spec.ts:529` относится к старому прогону;
в текущем кандидате устранён тестовый перехват после ожидания строки, оба
шарда прошли без retries (исходная ошибка Chromium не установлена);
администратор GitHub применяет `.github/rulesets/main.json` (нужен
`gh auth login`; после этого прямой push в main закрыт); Observatory A+ —
шаг релиза; гейт 4 — три недели зелёного CI. Параллельно не больше 2–3
агентов, тяжёлые проверки по одной (`AGENTS.md` §6).

### Предыдущая точка — 2026-10-10

Решения владельца продукта 2026-10-10 выполнены веткой `claude/leftovers`
поверх main `2f0233da` (`CHANGELOG.md` — «Owner decisions of 2026-10-10»):
пороги задержки API p95 ≤ 300 мс и p99 ≤ 1 с утверждены (`NFR-PERF-02`,
[SLO](ops/slo.md)); экспортёры хоста и PostgreSQL — необязательный overlay
`deploy/observability/docker-compose.observability.yml`, правила
`rules/infrastructure.yml`, runbook RB-10 и RB-11, роль мониторинга
создаёт оператор (SQL в SLO, раздел 7); запрос комментария задачи принимает
только `textMarkdown` (`commentMarkdown` удалён, `Api-Breaking`).

Пункты глубокого изучения после фазы 7 закрыты веткой интеграции
`claude/open-int` поверх main `2e9c8619` (2026-10-09; раздел «Фаза 7 —
статус» плана, `CHANGELOG.md` — «Open items after phase 7»): уведомления и
выгрузка задач на языке получателя, кириллица в литералах запрещена и в
`libs/*`; цикл `ms.task` ↔ `ms.notify` разорван событием
`TaskDeadlineApproaching`; напоминание одно на кластер (advisory-блокировка);
SSE на всех узлах через `NOTIFY` на `smc_cache`; секреты без `v1:`
останавливают старт (`StoredSecretsCheck`, V203 очищает заглушки SSO; старый
локальный стенд — `run-local.ps1 down -Volumes`); правила модулей проверяют
импорты (`ModuleSourceBoundariesTest`), `config` и инфраструктурные модули,
чужой SQL — в любом классе с SQL и в `config` (`MdInstanceService`); `MdPref`
и `KauthPref` — в `api`; пороги покрытия webhook, mf, ms.notify, ms.task
подняты.

Фазы 0–7 [плана 10/10](plan-10-10.md) выполнены. Фаза 7 и исправления
платформы влиты веткой интеграции `claude/p7-int` поверх main `56609c90`
(2026-10-08): JSON-логи ECS с маскированием и строгим `traceparent`
(`X-Client-Code` убран), трейсинг OpenTelemetry выключен по умолчанию (span
JDBC, комментарий в SQL, трасса вебхука), гистограммы и метрики,
[SLO](ops/slo.md), правила и дашборды `deploy/observability`, runbook
RB-01…RB-09, заголовки nginx и сроки сессий (ADR-0034, V202), модель угроз
2.0 и лимиты xlsx/ClamAV, ZAP baseline в e2e, секции `raw.rows` по загрузке
(миграция pg-dwh V004); платформа — 422 `entity_state_locked`, проверка значений хуков,
`EntitySqlBoundariesTest`, автономный `--external`.

**Следующее** (раздел «Фаза 7 — статус» плана): онлайн-проверка Mozilla
Observatory A+ — шаг релиза; строки журнала при старте без `trace_id`
(записано); HIGH в образах экспортёров ждут пересобранных upstream-релизов
(SLO, раздел 7); открытые критерии ревью фаз 0–6. Гейт 4: все строки таблицы
«Что значит 10/10» зелёные в CI три недели подряд. Администратор GitHub
применяет `.github/rulesets/main.json` заново (обязательная проверка
`observability`).
Правила для всех AI-ассистентов — в [`AGENTS.md`](../AGENTS.md), карта модулей —
в [module-map.md](architecture/module-map.md).

- **Ревью фаз 0–6** (2026-10-03, исправления влиты веткой `claude/rf-int`
  2026-10-07 поверх main `ca7efde7`): см. раздел «Ревью фаз 0–6» плана и
  `CHANGELOG.md`. Вебхук: каталог `GET /api/v1/webhooks/events` (`*`, события
  сущностей, действия и переходы), 422 `error.webhook.event_unknown`, подпись
  `X-Signature-SHA256` от `<timestamp>.<тело>`; `/api/v1/openapi.json` — только
  вошедшим; `ProductionStartGuard` (нужен `SMC_PUBLIC_URL` вне dev/test); один
  `EntityGate` на все пути сущности (выключенный модуль — 404); ключи
  `error.fnd.*` стали ключами модулей; права аудит-партиций — V200. Профиль
  `devtools` одобрен владельцем продукта (2026-10-07). Администратор GitHub
  должен заново применить `.github/rulesets/main.json` (обязательные проверки
  CLI `cms`).

- **Фаза 5** (2026-10-03, ADR-0032): общий runtime сущностей
  `/api/v1/entities/<код>` — объявление `EntityDefinition` и хуки вместо
  контроллера, сервиса и репозитория; типы полей, документы со строками и
  статусами, импорт, отчёты, поиск и вебхуки по возможностям; 9 сущностей на
  модели; тест-кит `EntityContractTestKit` (пункт 6.2) у каждой сущности.
- **Фаза 6, 6.3 и 6.4** (ADR-0033): публичный API — `libs/platform-api`
  (`com.smartup24.cms.platform.api..`, `@PlatformApi`, japicmp против 1.0.0,
  `scripts/api/test-platform-api-compat.ps1`); манифест модуля
  `META-INF/smartupcms/modules/<код>.json` (поля `code`, `version`,
  `minPlatform`, `dependencies`, `areas`, `configuration`, `migrations`,
  `messages`; неизвестное поле останавливает старт; название модуля — ключи
  `<код>.module.name` (обязателен в ru/uz/en) и `<код>.module.description`
  в каталогах, 2026-10-11); версия модуля — из манифеста, колонки
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
  первого администратора). Профиль `devtools` проверен: опциональная зависимость
  `spring-boot-devtools` работает при старте через `make dev` / `run-local.ps1 -DevTools`.

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

### Подписанный staging prerelease — продолжение после bc559ddd

Историческая запись реализации до `4a721df0`; текущий RC/CI и следующий gate
указаны в начале §7. Упоминание будущего SHA ниже относится к тому этапу.

Реализован [ADR-0040](adr/ADR-0040-signed-staging-prereleases.md): отдельный `prerelease.yml` для
`vX.Y.Z-rc.N`, общий bundle builder, подпись/attestation SHA256SUMS,
verifier с явным channel и обязательным expected source SHA, Ansible staging
guard. Это новые исходники после bc559ddd; прежний CI не является проверкой
следующего SHA. Изменения прошлой staging-документации сохранены.
Настройки GitHub/публикация/merge/deploy не выполняются; следующий gate —
новый DCO RC, полный CI и отдельно разрешённый live signing/publish rehearsal.

Проверки новой поставки локальны, без OIDC issuance/publish: release Python
13/13, verifier 32/32, production Python 64/64 (включая Ansible 45/45),
credential/backup Node 52/52; Ansible 2.21.5 syntax-check PASS. Реальные
tracked sources упакованы в оба канала: 204 файла, 27 checksums, пять env
templates, tar/zip content parity и shell mode 0755 PASS. Public docs,
docs unit 8/8, контракт 25 документов, hygiene, rulesets, architecture
boundaries и staging/managed/capacity contracts PASS. Независимое ревью
producer/verifier не выявило подтверждённых Blocker/High. Graphify недоступен
(`No module named graphify`); установка не выполнялась.

CodeQL security-extended: Python 0 findings (содержимое всех восьми изменённых
Python-файлов совпадает с source archive); официальный Actions pack 0 findings,
извлечение через JavaScript database, AST всех 8/8 workflow подтверждён отдельным
запросом и hash inventory. Это не отдельный native Actions extractor run.
Gitleaks текущих изменений и штатный full-history release-gates drill PASS.
Приватный протокол текущей незакоммиченной поставки:
`.local/prerelease-20261016/EVIDENCE.md`; он не заменяет полный CI будущего SHA.
