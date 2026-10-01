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
| Общие библиотеки | `libs/core-types`, `libs/platform-common`, `libs/provider-spi` |
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
- **Генератор:** `scripts/dev/create-module.ps1` — две миграции (таблица с
  `revision` и данные), пакет `api`, репозиторий, список, объявление, сервис,
  контроллер и ключи ru/uz/en по правилам фазы 3; что осталось руками, он
  печатает. Его результат проверяет `scripts/dev/test-create-module.ps1`
  (nightly).
- **Эталон:** модуль заметок (`ms/note`, `features/notes`).

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
  `WAREHOUSE_*`; старые `dwh` читаются с предупреждением до 2026-12-31; класс
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
- Генератор модулей: `scripts/dev/test-create-module.ps1`.
- Коммиты подписываются `git commit -s`; CI (`ci.yml`, `dco.yml`) запускается
  на push в main и на pull request, `nightly.yml` — по расписанию.

## 7. Точка продолжения — 2026-10-01

Фазы 0–4 [плана 10/10](plan-10-10.md) выполнены и влиты в main (2026-10-01);
следующая — фаза 5 «Low-code платформа v2», порядок задач задаёт пользователь.
Правила для всех AI-ассистентов — в [`AGENTS.md`](../AGENTS.md), карта модулей —
в [module-map.md](architecture/module-map.md).

- **Фаза 3** прошла ревью качества; её долги закрыты: `NOT_YET_LOCKED` и
  `NOT_YET_PAGED` удалены, хранилище замороженных нарушений ArchUnit пусто,
  «чужой SQL» запрещён (опубликованные представления, ADR-0026), комментарии на
  английском (выпущенные миграции заморожены контрольной суммой).
- **Фаза 4:** настройки `smc.*` / `warehouse.*` (ADR-0027, старые имена до
  2026-12-31); коды прав по модулю (ADR-0028); схема БД и шифрование секретов
  `SMC_SECRETS_KEY` (ADR-0029); `fnd` разделён на `jobs`, `warehouse`, `units`
  (ADR-0030); модуль вебхуков — `webhook`; ключи переводов
  `<module>.<screen>.<element>` (ADR-0031); «dwh» означает только хранилище
  (cookie `SMC_SESSION`, токены `smc_`, `type` ошибки — URN, метрики `smc`).

Известные пробелы платформы перечислены в
[extension-points.md](architecture/extension-points.md#7-известные-пробелы):
поиск и вебхуки не подключаются декларативно, модель сущности пока только на
заметках, маршрут экрана добавляется вручную.