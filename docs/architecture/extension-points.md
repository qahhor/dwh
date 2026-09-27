# Точки расширения SmartupCMS

**Версия:** 1.0

**Обновлено:** 2026-09-27

**Основание:** [ADR-0016](../adr/ADR-0016-field-registry-query-dsl.md),
[ADR-0019](../adr/ADR-0019-low-code-entity-model.md),
[ADR-0011](../adr/ADR-0011-provider-spi.md) и текущий код.

SmartupCMS расширяется **модулями в коде**: модуль — пакет внутри монолита
(`com.smartup24.cms.instance.<префикс>.<модуль>`) и экран в
`apps/web/src/app/features`. Модуль объявляет Spring-бины из списка ниже, и
платформа строит из них API, экран и служебные функции. Подключаемых во время
работы плагинов (jar/bundle) нет — это сознательное решение модульного монолита
([ADR-0006](../adr/ADR-0006-modular-monolith.md)).

Сокращения путей: `S` — `apps/server/src/main/java/com/smartup24/cms/instance`,
`W` — `apps/web/src/app`.

## 1. Сущность: объявление и записи

| Точка | Где | Что даёт |
|---|---|---|
| `EntityDefinition` (`@Bean`) | `S/common/entity/EntityDefinition.java` | Одно объявление сущности: поля формы и правила (`FormField`: `TEXT`, `TEXTAREA`, `MARKDOWN`, `NUMBER`, `DATE`, `BOOLEAN`, `SELECT`, `REF`), секции, действия с правом каждое, названия права (`EntityRights`), пункт меню (`EntityMenu`), возможности (`EntityCapability`). Отдаётся `GET /api/v1/form-meta/{code}`. |
| `EntityRecords` (`@Bean`) | `S/common/entity/EntityRecords.java` | То, что знает только модуль: видимость записи в скоупе зрителя, страница списка, удаление одной записи. Из него и объявления платформа строит историю, экспорт и `POST /api/v1/entities/{code}/bulk`. |
| `EntityValidator` | `S/common/entity/EntityValidator.java` | Проверка сохранения по объявлению: 422 с ошибкой на каждом поле. Вызывается сервисом модуля. |
| `FormFieldExtender` | `S/common/entity/FormFieldExtender.java` | Поля, добавляемые в форму во время запроса. Реализация — дополнительные поля (`S/md/service/MdCustomFieldFormFields.java`). |

Возможности (`EntityCapability`) и что им нужно:

| Возможность | Требует | Что появляется |
|---|---|---|
| `CUSTOM_FIELDS` | `customEntity` (тип сущности дополнительных полей) | Дополнительные поля администратора в форме, списке, фильтре и экспорте |
| `SAVED_VIEWS` | `listCode` | Сохранённые виды списка |
| `EXPORT` | `listCode`, `EntityRecords.page` | Выгрузка списка в Excel (журнал «Мои выгрузки») |
| `HISTORY` | `auditTable`, `EntityRecords.requireVisible` | Вкладка истории изменений |
| `BULK` | действие `delete`, `EntityRecords.delete` | Удаление выбранных записей |

Объявление, которому не хватает нужного, не даёт приложению стартовать.

## 2. Списки: реестр полей

| Точка | Где | Что даёт |
|---|---|---|
| `QueryList` (`@Bean`) | `S/common/query/QueryList.java` | Список: SQL выборки, поля с типами, фильтрами, сортировкой и поиском, лимиты. Отдаётся `GET /api/v1/query-meta/{code}`; экран фильтрует и сортирует через JSON DSL, включая группы «любое из условий» и поля-ссылки (`QueryRef`). |
| `QueryList.withCustomFields` | там же | Дополнительные поля как поля списка. |
| `QueryListExtender` | `S/common/query/QueryListExtender.java` | Поля списка, добавляемые во время запроса (реализация — дополнительные поля). |
| `QueryListExporter` | `S/common/query/QueryListExporter.java` | Экспорт списка, если он не берётся из объявления (`EXPORT`). |

## 3. Права, меню, языки

| Точка | Где | Что даёт |
|---|---|---|
| `@RequiresPermission(form, action)` | `S/common/annotation/RequiresPermission.java` | Единственный источник существования права: при старте пары попадают в каталог (`S/md/service/MdFormCatalogSynchronizer.java`). |
| `EntityRights` | в объявлении | Названия формы и действий в матрице прав; тест не даёт выпустить пару без названия. |
| `EntityMenu` | в объявлении | Пункт бокового меню: маршрут, подпись, иконка, раздел, порядок, модуль-выключатель. Отдаётся `GET /api/v1/entities/menu` по правам зрителя. |
| Модуль-выключатель | `md_installed_modules`, `S/md/service/ModuleRegistryService.java` | Администратор включает и выключает модуль; экран охраняет `moduleActiveGuard`. |
| Переводы | `apps/server/src/main/resources/i18n/{ru,uz,en}.json` | Ключи модуля (`nav.<код>`, подписи полей). Русский — канонический каталог; другие языки администратор добавляет в редакторе языков. |

## 4. Фоновые задания, события, интеграции

| Точка | Где | Что даёт |
|---|---|---|
| `FndJobHandler` (`@Bean`) | `S/fnd/jobs/FndJobHandler.java` | Задание по расписанию: `code()` и `run(args)`; расписание — строка в `fnd_job_schedule` (пример — `V121__upl_apply_recovery_job.sql`), разовый запуск — `FndJobRunner.enqueueOnce`. Очередь выполняет `S/config/jobs/JobQueueWorker.java`. |
| События Spring | например, `S/ms/task/service/MsTaskService.java` → `S/ms/notify/listener/MsTaskNotificationListener.java` | Модули общаются событиями, а не вызовами соседних сервисов. |
| Поиск | `S/search/SearchChangePublisher.java` | `changed(entityType, id)` в транзакции владельца ставит запись на переиндексацию. |
| Вебхуки | `S/kwh/service/KwhWebhookService.java` | `publishEvent(type, payload)` доставляет событие подписчикам с подписью HMAC-SHA256. |
| Провайдеры | `libs/provider-spi` (`StorageProvider`, `MailProvider`, `SmsProvider`, `MessengerProvider`) | Хранилище и каналы доставки; активный провайдер выбирает `S/common/provider/ProviderRegistry.java`. |

## 5. Интерфейс

| Точка | Где | Что даёт |
|---|---|---|
| `smt-entity-form` | `W/shared/entity/smt-entity-form.component.ts` | Форма по `form-meta`; отдельное поле заменяется шаблоном `smtEntityField`. |
| `smt-entity-card` | `W/shared/entity/smt-entity-card.component.ts` | Просмотр записи по раскладке. |
| `smt-entity-toolbar` | `W/shared/entity/smt-entity-toolbar.component.ts` | Виды, экспорт и удаление выбранных — по возможностям и правам. |
| `ui-server-table` + `registryTableConfig` | `W/shared/ui/ui-server-table.component.ts`, `W/shared/ui/registry-table-config.ts` | Таблица по `query-meta`: колонки, сортировка, фильтр, курсор. |
| UI kit | `W/shared/ui-kit` | Кнопки, диалоги, таблицы, поля (в том числе `smt-dynamic-field`, `smt-data-select`), загрузка файлов. |
| Маршрут экрана | `W/app.routes.ts` | Lazy `loadComponent` с `moduleActiveGuard` и `permissionGuard`. |
| Пункты меню администратора | `md_navigation_items` | Внутренний маршрут, внешняя ссылка или встраивание (`EMBEDDED_IFRAME`, открывается на `/embed/:code`). |

## 6. Известные пробелы

Честный список того, что ещё не является точкой расширения:

1. **Поиск не подключается декларативно.** Коллекции Typesense заданы для
   задач, проектов и пользователей; новой сущности нужен код в модуле `search`.
2. **Вебхуки без источников.** Подписки и доставка работают, но модули пока
   не вызывают `publishEvent`.
3. **Модель сущности внедрена на заметках.** Задачи, проекты и пользователи ещё
   не переведены на `EntityDefinition` и `smt-entity-*`.
4. **Встраивание внешнего приложения — только iframe** из пункта меню, без SSO
   и передачи сессии.
5. **Маршрут экрана добавляется в `app.routes.ts` вручную** — динамической
   регистрации экранов нет.
6. Таблица `md_custom_modules` (прежняя модель «Plugin SDK», V017) выключена
   в V019 и кодом не используется; хранится ради истории.
