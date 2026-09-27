# Контекст SmartupCMS для AI-ассистентов

**Актуализировано:** 2026-09-27

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
поиск, уведомления, аудит, загрузки данных (`upl`/`fnd`) — построены на той же
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
| Данные | PostgreSQL 18, неизменяемые Flyway-миграции (`V###`), вторая база для загруженных данных |
| Поиск | Typesense 27.1 — производный индекс, не источник авторизации |
| Файлы | `local_disk` или S3-совместимое хранилище через SPI |
| Поставка | Docker Compose, отдельный шаг `migrate`, зашифрованный backup, ClamAV в production |

Модули сервера: `common` (платформа), `md`, `kauth`, `ms`, `mf`, `audit`,
`search`, `kwh`, `fnd`, `upl`, `report`. Точки расширения — в
[extension-points.md](architecture/extension-points.md), порядок работы над
модулем — в [руководстве](guidelines/module-development-guide.md).

## 4. Платформа low-code

- **Реестр полей** (ADR-0016): `QueryList` → `GET /api/v1/query-meta/{code}`;
  фильтр — JSON DSL с группами `{"any": [...]}` и полями-ссылками; keyset-курсор.
- **Сущность** (ADR-0019): `EntityDefinition` → `GET /api/v1/form-meta/{code}`;
  `EntityValidator` проверяет сохранение (422 на поле); `EntityRecords` даёт
  историю, экспорт и `POST /api/v1/entities/{code}/bulk`; `EntityRights` и
  `EntityMenu` — названия прав и `GET /api/v1/entities/menu`.
- **Web:** `smt-entity-form`, `smt-entity-card`, `smt-entity-toolbar`,
  `ui-server-table` + `registryTableConfig`.
- **Генератор:** `scripts/dev/create-module.ps1` — миграция и пять Java-файлов.
- **Эталон:** модуль заметок (`ms/note`, `features/notes`).

## 5. Инварианты

- Авторизация — только на сервере (`@RequiresPermission`, скоуп данных); UI не
  является границей безопасности.
- Flyway-миграции после публикации не меняются; изменение данных помечается
  заголовком `destructive: approved` с причиной и утвердившим.
- Не коммитить `.env`, секреты, дампы, данные клиентов, `graphify-out/`.
- Русский каталог `apps/server/src/main/resources/i18n/ru.json` — источник
  ключей; `uz` и `en` полные; после изменений — `npm run i18n:sync-ru`.

## 6. Проверки перед пушем

- Сервер: `mvn -B verify`.
- Web (`apps/web`): `npm run typecheck`, `node scripts/signal-order-audit.mjs`,
  `npm run i18n:audit`, `npm run aria:audit`, `npm run contrast:audit`,
  `npm test`, `npm run build`; e2e — `cd e2e && npm run typecheck`.
- Документация: `scripts/docs/test-public-docs.ps1`,
  `scripts/docs/test-repository-hygiene.ps1`,
  `scripts/architecture/test-unified-boundaries.ps1`.
- Коммиты подписываются `git commit -s`; CI (`ci.yml`, `dco.yml`) запускается
  на push в main и на pull request.

## 7. Точка продолжения — 2026-09-27

Роадмап low-code (волна 9, пункты 47–58) выполнен и влит в main. После него
на отдельных ветках подготовлены:

1. `claude/cleanup-trash` — удалены датированные аудиты, планы агентов,
   дизайн-черновики, машинный `local-up.cmd`, граф Graphify из git,
   неиспользуемый код.
2. `claude/languages-ru-en-uz` — встроенные языки ru/uz/en, каталоги uz и en
   дополнены до полноты, V122 выключает прежние встроенные языки; ТЗ 1.4.
3. `claude/rename-smartup24-cms` — пакет и Maven group `com.smartup24.cms`.
4. `claude/developer-presentation` — документация для разработчиков, очистка
   полных имён классов в коде, общий воркер заданий вынесен из `upl` в `config/jobs`, меню без
   дублей для модулей с объявлением, удалён демо-пункт меню (V123).

Известные пробелы платформы перечислены в
[extension-points.md](architecture/extension-points.md#6-известные-пробелы):
поиск и вебхуки не подключаются декларативно, модель сущности пока только на
заметках, маршрут экрана добавляется вручную.
